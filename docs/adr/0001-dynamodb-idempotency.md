# ADR 0001: DynamoDB for Atomic Conditional Idempotency Keys

## Status
Accepted

## Context
Payment applications experience client retries when network connections drop, timeouts occur, or users double-tap transfer buttons. If 50 identical transfer requests hit the API simultaneously, processing every request would double-charge the sender and corrupt account balances.

We evaluated three potential idempotency stores:
1. **Relational Database Table (PostgreSQL)**:
   - *Risk*: A storm of concurrent duplicate requests creates transaction lock contention on the relational database, consuming database connections and risking connection pool exhaustion.
2. **In-Memory Cache (Redis)**:
   - *Risk*: Redis `SETNX` lacks built-in request payload verification in a single atomic primitive, requires custom Lua scripts, and in-memory persistence models (AOF/RDB) risk losing recent keys during broker failovers.
3. **NoSQL Document Store (Amazon DynamoDB)**:
   - *Advantage*: Native atomic conditional writes (`attribute_not_exists(idempotency_key)`), built-in Time-To-Live (TTL) automatic key eviction, consistent reads, and linear partition scalability.

## Decision
We chose **DynamoDB** as our fast-path idempotency guard:
1. **Atomic Acquisition**:
   - Every request to `POST /v1/transfers` requires an `Idempotency-Key` header.
   - The API issues a `PutItem` with condition `attribute_not_exists(idempotency_key)` and `status = IN_PROGRESS`.
   - Only ONE thread succeeds; all concurrent retry threads receive `ConditionalCheckFailedException`.
2. **Payload Parameter Validation**:
   - The SHA-256 hash of the request payload is stored in the record.
   - If a client attempts to reuse an existing idempotency key with different payment details (e.g. changing amount or recipient), the API rejects the request with `422 Unprocessable Entity` rather than returning a mismatched cached receipt.
3. **Cached Response Replay**:
   - Once the downstream transaction commits, the record is updated to `COMPLETED` along with the HTTP response code and response JSON.
   - Subsequent retries with the same key immediately return the cached response with zero downstream ledger load.
4. **Automated Expiration via TTL**:
   - Each item includes a `ttl` epoch timestamp set to $24 \text{ hours}$ in the future. DynamoDB automatically evicts expired records without requiring application cron jobs or database table vacuuming.

## Consequences
- **Positive**:
  - 100% deduplication of concurrent retry storms.
  - PostgreSQL is shielded from retry traffic; only verified unique transfers reach the ledger.
  - Native TTL handles housekeeping automatically.
- **Negative / Tradeoffs**:
  - Requires maintaining DynamoDB infrastructure (LocalStack locally, AWS in cloud).
  - An in-flight request failure requires explicit cleanup or timeout handling before the client can retry.
