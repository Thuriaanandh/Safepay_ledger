# ADR 0002: Double-Entry Bookkeeping & Optimistic Concurrency Control

## Status
Accepted

## Context
Payment engines cannot afford lost updates, race conditions, or unexplained balance drift. Naive payment implementations maintain a single `balance` column on a user row and update it with `balance = balance - amount`. This pattern has severe distributed systems vulnerabilities:
1. **Unbalanced Books**: If a database write fails midway, money can disappear from one account without appearing in another, violating conservation of value.
2. **Race Conditions / Lost Updates**: Concurrent transactions reading and overwriting balances can silently overwrite each other's debits or credits.
3. **Audit Impossibility**: Single-balance tables only store current state, destroying the historical audit trail needed for regulatory compliance and dispute resolution.

## Decision
1. **Double-Entry Ledger Architecture**:
   - Every financial transaction (`ledger_transactions`) is composed of at least two immutable postings (`ledger_postings`): one or more DEBIT legs and one or more CREDIT legs.
   - Core Invariant: Across any transaction (and across the entire ledger at all times), $\sum \text{Debits} == \sum \text{Credits}$.
   - Postings are strictly append-only and immutable. Balance adjustments require a compensating double-entry posting, never an `UPDATE` or `DELETE` on historical entries.
2. **Optimistic Locking via `@Version`**:
   - Instead of pessimistic database row locks (`SELECT ... FOR UPDATE`), which cause deadlocks when concurrent transfers touch accounts in reverse order (A $\to$ B vs. B $\to$ A), we use optimistic locking (`version BIGINT NOT NULL DEFAULT 0`).
   - If two concurrent threads attempt to modify the same account simultaneously, the database update checks `WHERE id = ? AND version = ?`. The second thread fails with `OptimisticLockingFailureException`, which is mapped to gRPC `Status.ABORTED` allowing client retry.
3. **Overdraft Protection at Business and Database Tiers**:
   - Business tier checks available balance before debiting.
   - Database schema enforces `CHECK (account_type != 'LIABILITY' OR balance_cents >= 0)` as a failsafe defense-in-depth barrier.

## Consequences
- **Positive**:
  - Exact financial accountability: Every cent transferred has a source and destination.
  - Zero lock contention / deadlocks between concurrent transfers touching distinct accounts.
  - Total ledger invariant can be audited continuously by a reconciliation job.
- **Negative / Tradeoffs**:
  - Under extreme contention on a single hot account (e.g., flash sale merchant), optimistic lock retries may spike. In production, this is mitigated via balance sharding or queuing requests per account.
