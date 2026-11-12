# Operational Runbook: SafePay Ledger

This runbook guides on-call and platform engineers when diagnosing, isolating, and remediating production incidents in SafePay Ledger.

---

## 1. Stuck Transfer (Client Timeout / In-Flight Status)

### Symptoms
- Client receives `504 Gateway Timeout` or `UNAVAILABLE` gRPC deadline exceeded.
- Subsequent calls with identical `Idempotency-Key` return `409 Conflict` (Transfer In-Progress).

### Diagnosis
1. Query the DynamoDB idempotency table for the key:
   ```bash
   aws --endpoint-url=http://localhost:4566 dynamodb get-item \
     --table-name safepay_idempotency_keys \
     --key '{"idempotency_key": {"S": "<KEY>"}}'
   ```
2. Verify status:
   - If `IN_PROGRESS` and expired according to TTL, check `ledger-service` logs for transaction timeout.
3. Check PostgreSQL for recorded transaction:
   ```sql
   SELECT * FROM ledger_transactions WHERE transfer_reference = '<REF>';
   ```

### Remediation
- If the transfer was committed in PostgreSQL, run the state sync job or let the next client retry retrieve the cached response from the idempotency store.
- If the transfer timed out before database commit, allow TTL to expire or mark as failed to let client retry safely.

---

## 2. Growing Outbox Table (Outbox Relay Lag)

### Symptoms
- Grafana alert `OutboxBacklogGrowing` triggers.
- Notifications are delayed; users do not receive transfer receipts.

### Diagnosis
1. Inspect the count of unpublished outbox events in PostgreSQL:
   ```sql
   SELECT count(*) FROM outbox_events WHERE status = 'PENDING';
   ```
2. Check `ledger-service` outbox relay logs for Kafka publish errors:
   - Broker unreachable / connection refused
   - Serialization errors

### Remediation
1. Verify Kafka broker status:
   ```bash
   docker stats safepay-kafka
   ```
2. If Kafka was temporarily down, the relay will automatically resume publishing with exponential backoff once Kafka recovers.

---

## 3. Poison Messages & Notification Worker Dead-Letter Queue (DLQ)

### Symptoms
- Kafka consumer group lag increasing on `transfer-notifications`.
- Messages routed to `transfer-notifications-dlq`.

### Diagnosis
1. Check Kafka consumer logs for deserialization or downstream webhook 5xx responses.
2. Read the DLQ header for failure reason:
   ```bash
   kafka-console-consumer --bootstrap-server localhost:9092 \
     --topic transfer-notifications-dlq --from-beginning --property print.headers=true
   ```

### Remediation
1. Fix downstream mock endpoint or schema issue.
2. Replay messages from DLQ to the main topic once the root cause is resolved.
