# ADR 0005: Distributed Observability and Ledger Invariant Monitoring

## Status
Accepted

## Context
In a distributed payment transfer system ("SafePay Ledger"), financial transactions execute asynchronously across multiple network boundaries and failure domains:
1. `payment-api` receives synchronous client REST requests and enforces idempotency in DynamoDB.
2. `payment-api` invokes `ledger-service` via synchronous gRPC over mutual TLS.
3. `ledger-service` commits double-entry balance transfers and persists Transactional Outbox events into PostgreSQL.
4. An outbox background relay polls uncommitted events and produces to Apache Kafka.
5. `notification-worker` consumes Kafka events and invokes downstream webhook partners protected by Resilience4j.
6. A reconciliation job continuously verifies the ledger invariant ($\sum \text{Debits} == \sum \text{Credits}$).

If a transfer stalls, drops, or fails halfway, traditional single-service logging fails to reveal the root cause. Moreover, asynchronous messaging across the Transactional Outbox table risks fragmenting distributed traces.

## Decision
1. **Tracing: OpenTelemetry Java Agent $\to$ Jaeger (All-in-One)**
   - Utilize bytecode auto-instrumentation via `opentelemetry-javaagent.jar` attached to the JVM.
   - Trace context is propagated across HTTP (`traceparent`), gRPC metadata, and explicitly serialized across the PostgreSQL Transactional Outbox table into Kafka record headers (`traceparent`).
   - This ensures **Acceptance Criterion 6**: A single distributed trace spans `REST -> gRPC -> Postgres -> Kafka -> consumer`.
   - Hardware budget: Jaeger runs in-memory with a max of 10,000 traces, capped at 256MB RAM (`make obs`).

2. **Metrics: Micrometer $\to$ Prometheus $\to$ Grafana**
   - Microservices expose pull-based Prometheus metrics at `/actuator/prometheus`.
   - Latency distribution configured with percentiles-histogram (`p50`, `p95`, `p99`).
   - Custom business metrics:
     - Outbox lag: `safepay_outbox_pending_events`
     - DLQ poison count: `safepay_notifications_dlq_total`
     - Webhook retry rate: `resilience4j_retry_calls_total`
     - Circuit breaker state: `resilience4j_circuitbreaker_state`
     - Ledger imbalance: `safepay_ledger_imbalance_cents`
   - Pre-configured Grafana dashboard auto-provisioned from `infra/grafana/dashboards/safepay-dashboard.json`.
   - Prometheus alert rules configured in `infra/prometheus/alert_rules.yml` for immediate alerting on ledger discrepancies.

3. **Continuous Reconciliation Job**
   - Runs as a periodic scheduled worker (or Kubernetes CronJob in production) executing aggregate accounting queries:
     $$\text{Imbalance} = |\sum \text{Debits} - \sum \text{Credits}|$$
   - Exports `safepay_ledger_imbalance_cents`. If this value exceeds 0, Prometheus fires `LedgerImbalanceDetected` with `severity: critical`.

## Consequences
- **Positive:**
  - Zero code invasiveness for database and network tracing via OpenTelemetry bytecode instrumentation.
  - End-to-end auditability and latency bottleneck visibility across asynchronous boundaries.
  - Provable ledger integrity via automated continuous reconciliation.
  - Memory usage strictly bounded: Prometheus TSDB retention limited to 2h (200MB), Jaeger limited to 256MB RAM.
- **Negative / Trade-offs:**
  - The OpenTelemetry agent introduces a minor startup delay (~2-3s) during classloading.
  - Relaying W3C `traceparent` headers through PostgreSQL requires explicit payload or header mapping in the outbox publisher.
