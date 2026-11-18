-- Step 1 schema: cards/accounts, raw SMS as received, parsed transactions.
-- Every row belongs to a user; row-level security limits each login to its own rows.

create table public.instruments (
  user_id      uuid not null default auth.uid() references auth.users on delete cascade,
  id           text not null,                 -- e.g. 'hdfc-cc', 'yes-savings'
  name         text not null,
  issuer       text not null,                 -- HDFC, AXIS, FEDERAL, ICICI, HSBC, YES, RBL, CANARA, IDFC
  type         text not null check (type in ('credit_card', 'bank_account', 'fastag')),
  network      text,                          -- only needed when the SMS has no last-4 (Scapia)
  last4        text[] not null default '{}',  -- every number the card has had
  role         text,                          -- main | subscriptions | ancillary | wallet
  credit_limit numeric(14, 2),                -- lets the app compute outstanding = limit - available
  created_at   timestamptz not null default now(),
  primary key (user_id, id)
);

create table public.raw_sms (
  id           bigint generated always as identity primary key,
  user_id      uuid not null default auth.uid() references auth.users on delete cascade,
  hash         text not null,                 -- sha256(sender|body|sent_ms) computed on the phone
  sender       text not null,
  body         text not null,
  sent_at      timestamptz,
  received_at  timestamptz not null,
  parse_status text not null default 'pending'
               check (parse_status in ('pending', 'parsed', 'unparsed', 'ignored')),
  parser       text,
  parsed_at    timestamptz,
  created_at   timestamptz not null default now(),
  unique (user_id, hash)
);
create index raw_sms_status_idx on public.raw_sms (user_id, parse_status);

create table public.transactions (
  id               bigint generated always as identity primary key,
  user_id          uuid not null default auth.uid() references auth.users on delete cascade,
  raw_sms_id       bigint unique references public.raw_sms on delete set null,
  source           text not null default 'sms',        -- sms | statement | manual
  kind             text not null check (kind in ('debit', 'credit', 'mandate_notice', 'wallet_debit')),
  amount           numeric(14, 2) not null,
  currency         text not null default 'INR',
  instrument_id    text,                                -- null = card/account not recognised yet
  issuer           text,
  account_type     text,
  last4            text,
  network          text,
  channel          text,
  merchant_raw     text,
  merchant         text,
  gateway          text,
  payee_vpa        text,
  reference        text,
  txn_time         timestamptz,
  time_from_receipt boolean not null default false,
  due_date         date,
  balance_after    numeric(14, 2),
  available_limit  numeric(14, 2),
  category         text,                                -- filled by the rules engine (step 2)
  parser           text,
  extra            jsonb not null default '{}',
  created_at       timestamptz not null default now()
);
create index transactions_time_idx on public.transactions (user_id, txn_time desc);

alter table public.instruments  enable row level security;
alter table public.raw_sms      enable row level security;
alter table public.transactions enable row level security;

create policy "own rows" on public.instruments  for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own rows" on public.raw_sms      for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own rows" on public.transactions for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

grant select, insert, update, delete on public.instruments, public.raw_sms, public.transactions to authenticated;
revoke all on public.instruments, public.raw_sms, public.transactions from anon;
