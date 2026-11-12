# Architecture Decision Records (ADRs)

This directory documents the key architectural and design decisions for SafePay Ledger:

1. [ADR 0001: DynamoDB for Atomic Conditional Idempotency Keys](0001-dynamodb-idempotency.md) - Enforcing fast-path deduplication via `attribute_not_exists(idempotency_key)` to protect core relational databases.
2. [ADR 0002: Double-Entry Bookkeeping & Optimistic Locking](0002-double-entry-bookkeeping.md) - Financial balance arithmetic with ACID isolation, debit/credit invariant ($\sum \text{Postings} = 0$), and `@Version` concurrency control.
3. [ADR 0003: Transactional Outbox Pattern for Zero Dual-Write](0003-transactional-outbox.md) - Atomic event persistence in PostgreSQL with an asynchronous relay to Apache Kafka KRaft.
4. [ADR 0004: Internal gRPC with mTLS and PKI](0004-mtls-grpc-internal.md) - Zero-trust inter-service RPC encryption, X.509 certificate authentication, and Wireshark TLS 1.3 packet proof.
5. [ADR 0005: Distributed Observability and Ledger Invariant Monitoring](0005-observability-distributed-tracing.md) - OpenTelemetry Java Agent bytecode tracing across asynchronous outbox boundaries, Micrometer Prometheus metrics, and automated Grafana dashboards.
6. [ADR 0006: Kubernetes Plain YAML Orchestration and cert-manager PKI](0006-kubernetes-manifests-and-cert-manager.md) - Multi-stage non-root distroless containerization, declarative plain YAML manifests, and automated cert-manager CA issuance.
7. [ADR 0007: Verification Strategy - k6 Load Testing, Mid-Flight Chaos, and CI/CD](0007-load-and-chaos-testing.md) - Concurrency stress testing, mid-flight process termination recovery, and dual-track GitHub Actions CI pipelines.
