# expense_tracker
A simple personal expense and balance tracker.

```
Android app ──(bank SMS only)──▶ Supabase edge function `ingest-sms` ──▶ Postgres
  capture + 6-month backfill        parses with supabase/functions/_shared/sms_parser.ts    raw_sms, transactions, instruments
  offline outbox, WorkManager        (row-level security: each login sees only its rows)
```

| Folder | What |
|---|---|
| `android/` | Kotlin + Compose app. Captures bank SMS (never OTPs or personal chats), queues them offline and uploads them. |
| `supabase/migrations/` | Database schema |
| `supabase/functions/ingest-sms/` | Stores and parses uploaded SMS; `{"reparse": true}` re-runs the parser over everything |
| `supabase/functions/_shared/sms_parser.ts` | Bank SMS formats: Scapia/Federal, Yes Bank, Axis, HDFC, ICICI, HSBC e-mandate, IDFC FASTag |
| `tests/` | Parser tests against anonymised real SMS (`node --test tests/*.test.ts`) |

## Builds
- **Backend:** each push to `main` runs the parser tests, applies migrations and deploys the edge function (`.github/workflows/backend.yml`).
- **APK:** each push builds the app and publishes it to the **apk-latest** release (`.github/workflows/android.yml`).

Required repository secrets (Settings → Secrets and variables → Actions):

| Secret | Where to find it |
|---|---|
| `SUPABASE_ACCESS_TOKEN` | supabase.com → Account → Access Tokens → Generate |
| `SUPABASE_DB_PASSWORD` | The database password set when the project was created (Project Settings → Database to reset) |
| `SUPABASE_ANON_KEY` | Project Settings → API Keys → `anon` / publishable key |
| `ANDROID_DEBUG_KEYSTORE_B64` | Base64 of a fixed signing keystore, so new APKs install over old ones |

## Adding an SMS format
1. Add the masked SMS and its expected values to `tests/fixtures/sms_samples.json`.
2. Add or adjust a parser in `sms_parser.ts` and register it in `PARSERS`.
3. Run `node --test tests/*.test.ts`, then push. In the app, **Re-parse all** applies the fix to stored SMS.

Personal data (real card numbers, SMS) is never committed; card details live in the `instruments` table.
