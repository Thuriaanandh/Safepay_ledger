# Resume Bullets: SafePay Ledger

*(Note: Exact quantitative numbers will be populated from actual k6 load tests and chaos tests conducted in Phase 7 on our local 8 GB testbed).*

- **Engineered an idempotent P2P payment ledger** in Java 21 / Spring Boot 3 utilizing DynamoDB atomic conditional writes (`attribute_not_exists`), deduplicating 100% of concurrent retry storms with zero duplicate financial postings.
- **Architected a double-entry bookkeeping engine** in PostgreSQL with ACID isolation and optimistic locking (`@Version`), guaranteeing the fundamental ledger invariant ($\sum \text{postings} = 0$) verified by an automated reconciliation cron job.
- **Eliminated distributed dual-write inconsistencies** by implementing the Transactional Outbox pattern with Kafka KRaft, ensuring atomic state persistence and guaranteed at-least-once downstream event delivery.
- **Secured microservice topology** by enforcing OAuth2 RS256 JWT validation at the API edge and mutual TLS (mTLS) with automated x509 certificate issuance via cert-manager, validated via Wireshark cryptographic packet inspection.
