// Run: node --test tests/   (Node 22+, native TypeScript type-stripping)
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { parseSms, type Instrument } from "../supabase/functions/_shared/sms_parser.ts";

const dir = new URL("./fixtures/", import.meta.url);
const samples = JSON.parse(readFileSync(new URL("sms_samples.json", dir), "utf8"));
const instruments: Instrument[] = JSON.parse(readFileSync(new URL("instruments.test.json", dir), "utf8"));
const RX = "2026-11-07T07:30:00.000Z"; // 13:00 IST receipt time

const sameInstant = (a: string, b: string) => assert.equal(new Date(a).getTime(), new Date(b).getTime(), `${a} vs ${b}`);

for (const [i, s] of samples.entries()) {
  test(`sample ${i + 1}: ${s.source}`, () => {
    const p = parseSms(s.body, RX, instruments);
    assert.ok(p, "should parse");
    assert.equal(p.kind, s.expect.kind);
    assert.equal(p.amount, s.expect.amount);
    assert.equal(p.instrument_id, s.expect.instrument_id);
    assert.equal(p.merchant, s.expect.merchant);
    sameInstant(p.txn_time!, s.expect.txn_time_ist ?? RX);
  });
}

test("field details", () => {
  const yes = parseSms(samples[4].body, RX, instruments)!;
  assert.equal(yes.reference, "111111111111");
  assert.equal(yes.balance_after, "12345.67");
  assert.equal(yes.channel, "upi");
  assert.equal(parseSms(samples[17].body, RX, instruments)!.available_limit, "100000.00");
  assert.equal(parseSms(samples[15].body, RX, instruments)!.gateway, "PayU");
  const hsbc = parseSms(samples[12].body, RX, instruments)!;
  assert.equal(hsbc.due_date, "2026-09-30");
  assert.equal(hsbc.reference, "TESTMANDATE1");
  const scapia = parseSms(samples[3].body, RX, instruments)!;
  assert.equal(scapia.extra.reward_pct, 10);
  assert.equal(scapia.gateway, "Razorpay");
  assert.equal(scapia.time_from_receipt, true);
});

test("ICICI same-day uses receipt time", () => {
  const rx = "2026-11-02T13:35:00.000Z"; // 19:05 IST on 2 Nov
  const p = parseSms(samples[17].body, rx, instruments)!;
  sameInstant(p.txn_time!, rx);
  assert.equal(p.txn_date_only, false);
});

test("OTP ignored", () => {
  assert.equal(parseSms("123456 is your OTP for txn of INR 500.00 at AMAZON on HDFC Bank Card 2222", RX), null);
});

test("non-money ignored", () => {
  assert.equal(parseSms("Your Swiggy order is on the way!", RX), null);
});

test("unknown money SMS -> unparsed", () => {
  const p = parseSms("Rs 499.00 refunded to your RBL Bank Credit Card XX1234 by NETFLIX", RX)!;
  assert.equal(p.kind, "unparsed");
  assert.equal(p.txn_time, RX);
});

test("unknown card number is not misassigned", () => {
  const body = samples[13].body.replace("1111", "9999");
  assert.equal(parseSms(body, RX, instruments)!.instrument_id, null);
});
