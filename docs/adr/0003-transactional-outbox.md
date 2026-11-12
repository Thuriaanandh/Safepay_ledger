# ADR 0003: Transactional Outbox Pattern for Zero Dual-Write Inconsistencies

## Status
Accepted

## Context
When a payment transfer completes in `ledger-service`, downstream services (such as merchant webhooks, notification engines, and user push alerts) must be notified via Apache Kafka. 

In a distributed architecture, attempting to write to both PostgreSQL and Kafka directly from the business service introduces the notorious **Dual-Write Problem**:
1. **Database Commits First, Kafka Fails**:
   - The ledger transaction commits successfully in PostgreSQL.
   - The Kafka broker becomes temporarily unreachable or the service crashes before the Kafka ACK.
   - *Result*: The money has moved, but no event is ever published. Downstream systems never notify the merchant or user, leading to "money debited but no order placed" support escalations.
2. **Kafka Publishes First, Database Rollback**:
   - The service publishes an event to Kafka first.
   - PostgreSQL subsequently encounters an optimistic lock failure or database constraint violation and rolls back the ledger transaction.
   - *Result*: The merchant receives a webhook claiming money arrived, but no ledger entries were ever committed (phantom funds / double crediting).

Two-Phase Commit (2PC / XA transactions) across heterogeneous distributed storage engines (PostgreSQL and Kafka) introduces high latency, blocking coordinators, and single points of failure.

## Decision
We implemented the **Transactional Outbox Pattern**:
1. **Single ACID Transaction Boundary**:
   - When `LedgerService.processTransfer(...)` executes, it writes the ledger entries, updates account balances, and inserts an event record into an `outbox_events` table inside the *exact same* local PostgreSQL ACID transaction.
   - If the database transaction rolls back, the outbox event is also rolled back. If it commits, the outbox event is guaranteed to be saved.
2. **Asynchronous Outbox Publisher Relay**:
   - An asynchronous background worker (`OutboxPublisher`) periodically queries `PENDING` events from PostgreSQL and publishes them to Kafka with `transferId` as the partition key.
   - Upon receiving an acknowledgment from Kafka, the relay updates the event status to `PUBLISHED`.
   - If Kafka is temporarily down, events remain `PENDING` with retry backoff, guaranteeing **At-Least-Once Delivery**.
3. **Idempotent Consumer Deduplication**:
   - Because at-least-once delivery can produce duplicate messages (e.g. if the relay crashes after publishing to Kafka but before updating PostgreSQL), downstream consumers (`notification-worker`) implement consumer-side deduplication.
   - A `ConsumerDeduplicationService` records processed `transferId`s, dropping duplicate Kafka messages before any webhook is dispatched.
4. **Resilience4j Webhook Dispatch & Dead-Letter Topic (DLQ)**:
   - Downstream webhook delivery is guarded with Resilience4j Retry (exponential backoff + jitter) and Circuit Breaker.
   - Poison messages that fail all retries are automatically routed to `transfer-events-dlq` to prevent consumer group head-of-line blocking.

## Consequences
- **Positive**:
  - Completely eliminates the dual-write problem. Zero lost events.
  - Decouples transaction latency from Kafka broker network latency.
  - Guarantees strict ordering per transfer by partitioning by `transferId`.
- **Negative / Tradeoffs**:
  - Introduces slight asynchronous latency (e.g., ~500ms) between database commit and message delivery.
  - Downstream consumers must be strictly idempotent.
