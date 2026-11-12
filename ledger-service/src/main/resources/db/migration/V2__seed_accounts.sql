-- Seed Accounts for SafePay Ledger Local Environment and Verification
-- Fixed UUIDs ensure deterministic testing across environments

INSERT INTO accounts (id, account_number, account_type, currency, balance_cents, status, version)
VALUES 
    ('00000000-0000-0000-0000-000000000001', 'ACC-PLATFORM-RESERVE', 'ASSET', 'USD', 100000000, 'ACTIVE', 0),
    ('00000000-0000-0000-0000-000000000002', 'ACC-ALICE-USD',        'LIABILITY', 'USD', 500000,    'ACTIVE', 0),
    ('00000000-0000-0000-0000-000000000003', 'ACC-BOB-USD',          'LIABILITY', 'USD', 200000,    'ACTIVE', 0),
    ('00000000-0000-0000-0000-000000000004', 'ACC-CHARLIE-USD',      'LIABILITY', 'USD', 0,         'ACTIVE', 0)
ON CONFLICT (account_number) DO NOTHING;
