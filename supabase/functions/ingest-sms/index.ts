// POST /functions/v1/ingest-sms
//   { "messages": [{ "hash", "sender", "body", "sent_at", "received_at" }, ...] }   -> store + parse new SMS
//   { "reparse": true, "after_id": 0 }                                              -> re-run parser over stored SMS
// Auth: the caller's Supabase access token (Authorization: Bearer ...). All DB access runs as that user,
// so row-level security applies exactly as it does for the app.
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.45.4";
import { parseSms, type Instrument } from "../_shared/sms_parser.ts";

const MAX_BATCH = 200;
const REPARSE_PAGE = 300;

interface InMsg { hash: string; sender: string; body: string; sent_at?: string | null; received_at: string }
interface RawRow { id: number; body: string; received_at: string; parse_status: string }

const json = (status: number, data: unknown) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "POST only" });
  const auth = req.headers.get("authorization");
  const apikey = req.headers.get("apikey") ?? Deno.env.get("SUPABASE_ANON_KEY");
  if (!auth || !apikey) return json(401, { error: "missing credentials" });

  const db = createClient(Deno.env.get("SUPABASE_URL")!, apikey, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false },
  });
  const { data: who, error: authErr } = await db.auth.getUser(auth.replace(/^Bearer\s+/i, ""));
  if (authErr || !who?.user) return json(401, { error: "invalid session" });

  let payload: { messages?: InMsg[]; reparse?: boolean; after_id?: number };
  try {
    payload = await req.json();
  } catch {
    return json(400, { error: "invalid json" });
  }

  const { data: instruments, error: instErr } = await db.from("instruments").select("id,issuer,type,network,last4");
  if (instErr) return json(500, { error: instErr.message });

  if (payload.reparse) {
    const after = payload.after_id ?? 0;
    const { data: rows, error } = await db.from("raw_sms")
      .select("id,body,received_at,parse_status").gt("id", after).order("id").limit(REPARSE_PAGE);
    if (error) return json(500, { error: error.message });
    const stats = await parseRows(db, rows as RawRow[], instruments as Instrument[]);
    const last = rows.length ? rows[rows.length - 1].id : null;
    return json(200, { ...stats, next_after_id: rows.length === REPARSE_PAGE ? last : null });
  }

  const msgs = (payload.messages ?? []).filter((m) => m && m.hash && m.body && m.received_at);
  if (!msgs.length) return json(400, { error: "no messages" });
  if (msgs.length > MAX_BATCH) return json(413, { error: `max ${MAX_BATCH} messages per call` });

  // Upsert returns existing rows too (with their current status), so re-sent SMS are not parsed twice.
  const { data: rows, error } = await db.from("raw_sms")
    .upsert(msgs.map((m) => ({
      hash: m.hash, sender: m.sender ?? "", body: m.body, sent_at: m.sent_at ?? null, received_at: m.received_at,
    })), { onConflict: "user_id,hash" })
    .select("id,body,received_at,parse_status");
  if (error) return json(500, { error: error.message });

  const pending = (rows as RawRow[]).filter((r) => r.parse_status === "pending");
  const stats = await parseRows(db, pending, instruments as Instrument[]);
  return json(200, { received: msgs.length, new: pending.length, ...stats });
});

async function parseRows(db: SupabaseClient, rows: RawRow[], instruments: Instrument[]) {
  const stats = { parsed: 0, unparsed: 0, ignored: 0, errors: [] as string[] };
  const txns: Record<string, unknown>[] = [];
  const status: Record<string, number[]> = { parsed: [], unparsed: [], ignored: [] };
  const parserOf = new Map<number, string>();

  for (const r of rows) {
    let p;
    try {
      p = parseSms(r.body, r.received_at, instruments);
    } catch (e) {
      stats.errors.push(`raw ${r.id}: ${(e as Error).message}`);
      status.unparsed.push(r.id);
      continue;
    }
    if (!p) { status.ignored.push(r.id); continue; }
    if (p.kind === "unparsed") { status.unparsed.push(r.id); continue; }
    status.parsed.push(r.id);
    parserOf.set(r.id, p.parser);
    txns.push({
      raw_sms_id: r.id, source: "sms", kind: p.kind, amount: p.amount,
      instrument_id: p.instrument_id ?? null, issuer: p.issuer ?? null, account_type: p.account_type ?? null,
      last4: p.last4 ?? null, network: p.network ?? null, channel: p.channel ?? null,
      merchant_raw: p.merchant_raw ?? null, merchant: p.merchant ?? null, gateway: p.gateway ?? null,
      payee_vpa: p.payee_vpa ?? null, reference: p.reference ?? null, txn_time: p.txn_time ?? null,
      time_from_receipt: !!p.time_from_receipt, due_date: p.due_date ?? null,
      balance_after: p.balance_after ?? null, available_limit: p.available_limit ?? null,
      parser: p.parser, extra: p.extra ?? {},
    });
  }

  if (txns.length) {
    const { error } = await db.from("transactions").upsert(txns, { onConflict: "raw_sms_id" });
    if (error) return { ...stats, errors: [...stats.errors, error.message] };
  }
  // An SMS that no longer parses (e.g. after a reparse) should not keep a stale transaction.
  const dropped = [...status.unparsed, ...status.ignored];
  if (dropped.length) await db.from("transactions").delete().in("raw_sms_id", dropped);

  const now = new Date().toISOString();
  for (const [s, ids] of Object.entries(status)) {
    if (!ids.length) continue;
    if (s === "parsed") {
      // group by parser name so each row records which pattern matched
      const byParser = new Map<string, number[]>();
      for (const id of ids) byParser.set(parserOf.get(id)!, [...(byParser.get(parserOf.get(id)!) ?? []), id]);
      for (const [parser, pids] of byParser) {
        await db.from("raw_sms").update({ parse_status: s, parser, parsed_at: now }).in("id", pids);
      }
    } else {
      await db.from("raw_sms").update({ parse_status: s, parser: null, parsed_at: now }).in("id", ids);
    }
    (stats as Record<string, unknown>)[s] = ids.length;
  }
  return stats;
}
