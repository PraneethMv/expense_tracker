// Bank / card SMS parser. Runtime-agnostic (Deno edge function + Node tests).
// One function per SMS format; each receives whitespace-normalised text.
// To add a format: add a masked sample to tests/fixtures/sms_samples.json with its expectation,
// then add/adjust a parser below and register it in PARSERS.

export type Kind = "debit" | "credit" | "mandate_notice" | "wallet_debit" | "unparsed";

export interface ParsedSMS {
  kind: Kind;
  amount: string | null; // decimal string, e.g. "1100.00" - never a float
  parser: string;
  issuer?: string;
  account_type?: "credit_card" | "bank_account" | "fastag";
  last4?: string;
  network?: string;
  channel?: "card" | "upi" | "mandate" | "toll";
  merchant_raw?: string;
  merchant?: string;
  gateway?: string;
  payee_vpa?: string;
  reference?: string;
  txn_time?: string | null; // ISO-8601 UTC
  txn_date_only?: boolean;
  time_from_receipt?: boolean;
  due_date?: string; // YYYY-MM-DD
  balance_after?: string;
  available_limit?: string;
  instrument_id?: string | null;
  extra: Record<string, unknown>;
}

export interface Instrument {
  id: string;
  issuer: string;
  type: string;
  network?: string | null;
  last4?: string[] | null;
}

// ------------------------------------------------------------------ helpers
const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];
const GATEWAYS: Record<string, string> = { PYU: "PayU", PAYU: "PayU", RAZ: "Razorpay", RZP: "Razorpay" };
const AMT = String.raw`([\d,]+(?:\.\d+)?)`;
const IST_OFFSET_MIN = 330;

export function normalizeWs(t: string): string {
  return t.replace(/ /g, " ").replace(/\s+/g, " ").trim();
}

export function amount(s: string): string {
  const v = s.replace(/,/g, "").replace(/\.$/, "");
  if (!/^\d+(\.\d+)?$/.test(v)) throw new Error(`bad amount ${s}`);
  return v;
}

const mon = (s: string) => MONTHS.indexOf(s.slice(0, 3).toUpperCase()) + 1;
const yy = (s: string) => (s.length <= 2 ? 2000 + Number(s) : Number(s));

/** Build a UTC ISO string from an IST wall-clock time. */
export function ist(y: number, m: number, d: number, hh = 0, mi = 0, ss = 0): string {
  return new Date(Date.UTC(y, m - 1, d, hh, mi, ss) - IST_OFFSET_MIN * 60_000).toISOString();
}

function istDate(iso: string): string {
  return new Date(new Date(iso).getTime() + IST_OFFSET_MIN * 60_000).toISOString().slice(0, 10);
}

export function cleanMerchant(raw: string): { merchant: string; gateway?: string } {
  let s = raw.trim().replace(/^[._\-* ]+|[._\-* ]+$/g, "").trim();
  let gateway: string | undefined;
  const m = s.match(/^([A-Za-z]{2,5})\*(.+)$/);
  if (m) {
    const p = m[1].toUpperCase();
    gateway = GATEWAYS[p] ?? p;
    s = m[2].trim();
  }
  return { merchant: s.replace(/\s+/g, " "), gateway };
}

const re = (src: string) => new RegExp(src, "i");

// ------------------------------------------------------------------ Federal (Scapia)
// "Hi! Your txn of ₹100.00 at <merchant> on your Scapia Federal RuPay credit card was successful. ..."
// "Your txn of ₹979.00 at Raz*<merchant> on your Scapia Federal Visa credit card earned you 10% rewards! ..."
// No date and no last-4: time = receipt time, card identified by network.
const SCAPIA = re(
  String.raw`Your txn of (?:₹|Rs\.?|INR) ?${AMT} at (.+?) on your Scapia Federal (RuPay|Visa|Mastercard) credit card (was successful|earned you (\d+)% rewards)`,
);
function federalScapia(t: string): ParsedSMS | null {
  const m = t.match(SCAPIA);
  if (!m) return null;
  const { merchant, gateway } = cleanMerchant(m[2]);
  const network = m[3].toUpperCase();
  const p: ParsedSMS = {
    kind: "debit", amount: amount(m[1]), parser: "federal.scapia_spend",
    issuer: "FEDERAL", account_type: "credit_card", network,
    // Observed: RuPay-on-UPI alerts say "was successful"; card alerts say "earned you X% rewards".
    channel: m[4].toLowerCase().startsWith("was") && network === "RUPAY" ? "upi" : "card",
    merchant_raw: m[2], merchant, gateway, extra: {},
  };
  if (m[5]) p.extra.reward_pct = Number(m[5]);
  return p;
}

// ------------------------------------------------------------------ Yes Bank savings
// "YES BANK Ac X7777 debited for INR 1,100.00 on 07NOV26 12:49. UPI:<ref>/To:<vpa>. Bal INR 12,345.67. ..."
const YES_AC = re(
  String.raw`YES BANK A/?c X(\d{3,6}) (debited|credited) (?:for|with) INR ${AMT} on (\d{2})([A-Z]{3})(\d{2}) (\d{2}):(\d{2})\.(.*?)Bal INR ${AMT}`,
);
const YES_UPI = /UPI:(\d+)\/(To|From):(\S+?)\.?\s*$/i;
function yesAccount(t: string): ParsedSMS | null {
  const m = t.match(YES_AC);
  if (!m) return null;
  const [, last, dir, amt, d, mo, y, hh, mi, rest, bal] = m;
  const p: ParsedSMS = {
    kind: dir.toLowerCase() === "debited" ? "debit" : "credit",
    amount: amount(amt), parser: `yes.account_${dir.toLowerCase()}`,
    issuer: "YES", account_type: "bank_account", last4: last.slice(-4),
    txn_time: ist(yy(y), mon(mo), +d, +hh, +mi), balance_after: amount(bal), extra: {},
  };
  const u = rest.trim().match(YES_UPI);
  if (u) {
    p.channel = "upi";
    p.reference = u[1];
    p.payee_vpa = u[3].toLowerCase();
    p.merchant_raw = u[3];
    p.merchant = u[3].toLowerCase();
  } else {
    p.extra.details = rest.trim(); // NEFT/IMPS/ATM - not sampled yet
  }
  return p;
}

// ------------------------------------------------------------------ Axis credit cards
// Multi-line: "Spent INR 80\nAxis Bank Card no. XX4444\n02-07-26 21:03:44 IST\nMERCHANT\nAvl Limit: INR 50000.5 ..."
// One-line:   "Spent INR 279 using Axis Bank Credit Card no. XX3333 on 18-10-26 04:07:27 IST at GOOGLEPLAY. Avl limit: INR ..."
// Dates are DD-MM-YY. Merchant is truncated by Axis to ~11 chars.
const AXIS = re(
  String.raw`Spent INR ${AMT} (?:using )?Axis Bank (?:Credit )?Card no\. XX(\d{4}) (?:on )?(\d{2})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2}) IST (?:at )?(.+?)\.? Avl limit: INR ${AMT}`,
);
function axisCard(t: string): ParsedSMS | null {
  const m = t.match(AXIS);
  if (!m) return null;
  const [, amt, last4, d, mo, y, hh, mi, ss, merch, lim] = m;
  const { merchant, gateway } = cleanMerchant(merch);
  return {
    kind: "debit", amount: amount(amt), parser: "axis.cc_spend",
    issuer: "AXIS", account_type: "credit_card", last4, channel: "card",
    merchant_raw: merch, merchant, gateway,
    txn_time: ist(yy(y), +mo, +d, +hh, +mi, +ss), available_limit: amount(lim), extra: {},
  };
}

// ------------------------------------------------------------------ HDFC credit card
// "Spent Rs.295 On HDFC Bank Card 1111 At Swiggy Food On 2026-09-30:22:24:08.Not You? ..."
const HDFC_CC = re(
  String.raw`Spent Rs\.? ?${AMT} On HDFC Bank Card (\d{4}) At (.+?) On (\d{4})-(\d{2})-(\d{2}):(\d{2}):(\d{2}):(\d{2})`,
);
function hdfcCard(t: string): ParsedSMS | null {
  const m = t.match(HDFC_CC);
  if (!m) return null;
  const [, amt, last4, merch, y, mo, d, hh, mi, ss] = m;
  const { merchant, gateway } = cleanMerchant(merch);
  return {
    kind: "debit", amount: amount(amt), parser: "hdfc.cc_spend",
    issuer: "HDFC", account_type: "credit_card", last4, channel: "card",
    merchant_raw: merch, merchant, gateway,
    txn_time: ist(+y, +mo, +d, +hh, +mi, +ss), extra: {},
  };
}

// ------------------------------------------------------------------ ICICI credit card
// "INR 173.00 spent using ICICI Bank Card XX5555 on 02-Nov-26 on AMAZON PAY IN G. Avl Limit: INR 1,00,000.00. ..."
const ICICI_CC = re(
  String.raw`INR ${AMT} spent using ICICI Bank Card XX(\d{4}) on (\d{2})-([A-Za-z]{3})-(\d{2}) on (.+?)\. Avl Limit: INR ${AMT}`,
);
function iciciCard(t: string): ParsedSMS | null {
  const m = t.match(ICICI_CC);
  if (!m) return null;
  const [, amt, last4, d, mo, y, merch, lim] = m;
  const { merchant, gateway } = cleanMerchant(merch);
  return {
    kind: "debit", amount: amount(amt), parser: "icici.cc_spend",
    issuer: "ICICI", account_type: "credit_card", last4, channel: "card",
    merchant_raw: merch, merchant, gateway,
    txn_time: ist(yy(y), mon(mo), +d), txn_date_only: true, available_limit: amount(lim), extra: {},
  };
}

// ------------------------------------------------------------------ HSBC e-mandate pre-debit notice
// "Your YOUTUBE Merchant payment e-mandate MIN – <id> for Rs 299 is due on 30/9/2026 and will be processed
//  through HSBC card ending with 6666." -> not an expense; feeds "upcoming charges".
const HSBC_MANDATE = re(
  String.raw`Your (.+?) Merchant payment e-mandate MIN ?[–—-] ?(\S+) for Rs\.? ?${AMT} is due on (\d{1,2})/(\d{1,2})/(\d{4}) and will be processed through HSBC card ending with (\d{4})`,
);
function hsbcMandate(t: string): ParsedSMS | null {
  const m = t.match(HSBC_MANDATE);
  if (!m) return null;
  const [, merch, mid, amt, d, mo, y, last4] = m;
  const { merchant, gateway } = cleanMerchant(merch);
  return {
    kind: "mandate_notice", amount: amount(amt), parser: "hsbc.emandate_notice",
    issuer: "HSBC", account_type: "credit_card", last4, channel: "mandate",
    merchant_raw: merch, merchant, gateway, reference: mid,
    due_date: `${y}-${mo.padStart(2, "0")}-${d.padStart(2, "0")}`, extra: {},
  };
}

// ------------------------------------------------------------------ IDFC FIRST FASTag
// "INR 20 toll paid from IDFC FIRST Bank Tag <tag> for vehicle no. <veh> at <plaza> on 30/10/2026 09:36. Avbl. Bal.: INR195.00."
const IDFC_TOLL = re(
  String.raw`INR ${AMT} toll paid from IDFC FIRST Bank Tag (\S+) for vehicle no\. (\S+) at (.+?) on (\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2})\. Avbl\. Bal\.: ?INR ?${AMT}`,
);
function idfcFastag(t: string): ParsedSMS | null {
  const m = t.match(IDFC_TOLL);
  if (!m) return null;
  const [, amt, tag, vehicle, plaza, d, mo, y, hh, mi, bal] = m;
  return {
    kind: "wallet_debit", amount: amount(amt), parser: "idfc.fastag_toll",
    issuer: "IDFC", account_type: "fastag", last4: tag.slice(-4), channel: "toll",
    merchant_raw: plaza, merchant: plaza.trim(), reference: tag,
    txn_time: ist(+y, +mo, +d, +hh, +mi), balance_after: amount(bal),
    extra: { vehicle, tag },
  };
}

export const PARSERS = [federalScapia, yesAccount, axisCard, hdfcCard, iciciCard, hsbcMandate, idfcFastag];

// ------------------------------------------------------------------ entry point
const IGNORE = /\bOTP\b|one[- ]time password|verification code|minimum amount due|payment of .* is due/i;
const MONEYISH = /(₹|\bRs\.?|\bINR)\s?[\d,]+/i;
const TXN_WORDS = /debited|credited|spent|txn|transaction|paid|refund|reversed|withdrawn|received/i;

export function resolveInstrument(p: ParsedSMS, instruments: Instrument[]): string | null {
  for (const inst of instruments) {
    if (inst.issuer !== p.issuer || inst.type !== p.account_type) continue;
    if (p.last4 && (inst.last4 ?? []).includes(p.last4)) return inst.id;
    // Scapia SMS carry no last-4; the network identifies the card.
    if (!p.last4 && inst.network && inst.network === p.network) return inst.id;
  }
  return null;
}

/** Returns null when the SMS is not a money message (OTP, promo, chat). */
export function parseSms(body: string, receivedAt: string | null, instruments: Instrument[] = []): ParsedSMS | null {
  const text = normalizeWs(body);
  if (!text || IGNORE.test(text)) return null;
  let p: ParsedSMS | null = null;
  for (const fn of PARSERS) {
    p = fn(text);
    if (p) break;
  }
  if (!p) {
    if (MONEYISH.test(text) && TXN_WORDS.test(text)) {
      return { kind: "unparsed", amount: null, parser: "none", txn_time: receivedAt, extra: {} };
    }
    return null;
  }
  if (p.txn_time == null) {
    p.txn_time = receivedAt;
    p.time_from_receipt = true;
  } else if (p.txn_date_only && receivedAt && istDate(receivedAt) === istDate(p.txn_time)) {
    // ICICI gives only a date; use the receipt time when it's the same day.
    p.txn_time = receivedAt;
    p.txn_date_only = false;
  }
  p.instrument_id = resolveInstrument(p, instruments);
  return p;
}
