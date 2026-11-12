-- Schema for SafePay Double-Entry Ledger and Transactional Outbox

-- 1. Accounts Table
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    account_number VARCHAR(64) NOT NULL UNIQUE,
    account_type VARCHAR(32) NOT NULL, -- ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
    currency VARCHAR(3) NOT NULL,       -- USD, EUR, etc. (ISO 4217)
    balance_cents BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, FROZEN, CLOSED
    version BIGINT NOT NULL DEFAULT 0,  -- Optimistic locking
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_positive_balance CHECK (
        account_type != 'LIABILITY' OR balance_cents >= 0
    )
);

CREATE INDEX idx_accounts_account_number ON accounts(account_number);

-- 2. Ledger Transactions (Group of matching double-entry postings)
CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    transfer_id VARCHAR(128) NOT NULL UNIQUE, -- External correlation / transfer reference
    description TEXT,
    status VARCHAR(32) NOT NULL,              -- POSTED, REJECTED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_transactions_transfer_id ON ledger_transactions(transfer_id);

-- 3. Ledger Postings (Immutable debit/credit legs of a transaction)
CREATE TABLE ledger_postings (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    direction VARCHAR(16) NOT NULL,           -- DEBIT, CREDIT
    amount_cents BIGINT NOT NULL,
    sequence_num INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_posting_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT chk_posting_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX idx_ledger_postings_tx_id ON ledger_postings(transaction_id);
CREATE INDEX idx_ledger_postings_account_id ON ledger_postings(account_id);

-- 4. Transactional Outbox Table (Zero dual-write guarantee)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,      -- e.g., 'TRANSFER'
    aggregate_id VARCHAR(128) NOT NULL,       -- e.g., transfer_id
    event_type VARCHAR(64) NOT NULL,          -- e.g., 'TRANSFER_COMPLETED', 'TRANSFER_FAILED'
    payload TEXT NOT NULL,                    -- JSON serialized event
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, PUBLISHED, FAILED
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_events_status_created ON outbox_events(status, created_at);
