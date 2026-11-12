# Threat Model: SafePay Ledger

## Overview
SafePay Ledger handles critical financial transactions (P2P transfers). This document outlines potential security risks using the **STRIDE** methodology (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege) and the countermeasures implemented in the system.

## Trust Boundaries
1. **Public Internet / Client to `payment-api`**: Untrusted client boundary. Protected via TLS 1.3, RS256 JWT tokens, strict schema validation, and rate limiting.
2. **Internal Service-to-Service (`payment-api` <-> `ledger-service`)**: Protected via mutual TLS (mTLS) with certificates issued by an internal CA (cert-manager), preventing eavesdropping and unauthorized service calls.
3. **Internal Event Broker (`ledger-service` -> Kafka -> `notification-worker`)**: Message broker isolation, strict topic authorization, and transactional event publishing.
4. **Data Tier (PostgreSQL, DynamoDB)**: Dedicated database credentials, non-root processes, network isolation.

## STRIDE Analysis Matrix

| Threat Category | Description | SafePay Mitigation |
|---|---|---|
| **Spoofing** | Attacker impersonates an authorized client or internal service | Client: RS256 JWT validation against trusted public keys.<br>Internal: Mutual TLS (mTLS) requiring trusted x509 certs. |
| **Tampering** | Man-in-the-middle alters transfer amount or recipient in transit | mTLS encryption in transit across all internal gRPC channels; TLS on external REST endpoints. |
| **Repudiation** | Client claims a transfer was initiated twice by mistake | Client-supplied unique `Idempotency-Key` persisted with atomic conditional write; immutable double-entry ledger entries. |
| **Information Disclosure** | Sensitive payment data or PII leaked in application logs | Structured JSON logging with strict masking filters (zero card data/PII logged). |
| **Denial of Service** | High volume of duplicate requests starving the ledger | Fast-path idempotency rejection in DynamoDB before hitting PostgreSQL; gRPC deadlines and Resilience4j circuit breakers. |
| **Elevation of Privilege** | Compromised worker service executes arbitrary balance modifications | Least-privilege IAM roles and isolated database credentials per service. |
