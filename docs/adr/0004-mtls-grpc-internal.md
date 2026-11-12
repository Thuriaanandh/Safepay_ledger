# ADR 0004: Mutual TLS (mTLS) for Internal Service-to-Service gRPC Communication

## Status
Accepted

## Context
In modern Zero-Trust architectures (and especially under PCI-DSS / financial payment security standards), the internal network perimeter is assumed to be hostile. Perimeter-only security (firewalls/VPCs) is insufficient because:
1. Compromised workloads in the cluster can eavesdrop on plaintext internal RPC traffic (sniffing payment amounts, account IDs, and user metadata).
2. Man-in-the-Middle (MITM) attacks can tamper with internal payment commands.
3. Rogue pods or unauthorized internal services can spoof requests to `ledger-service` without passing through edge authentication and idempotency filters.

## Decision
We enforce **Mutual TLS (mTLS)** for all internal communication between `payment-api` and `ledger-service` over gRPC:
1. **Cryptographic Protection**:
   - TLS 1.3 encryption for all gRPC frames in transit over port `9091`.
   - Protects against eavesdropping, tampering, and replay attacks.
2. **Mutual Peer Authentication**:
   - The server (`ledger-service`) presents its X.509 certificate verifying it is the genuine ledger service.
   - The client (`payment-api`) presents its X.509 certificate verifying it is an authorized caller.
   - Both services validate peer certificates against a shared, trusted internal Certificate Authority (CA). Unauthenticated clients are rejected at the TLS handshake level before any application code executes.
3. **PKI & Certificate Lifecycle**:
   - In local development and automated CI, certificates are issued by an internal Root CA with appropriate Subject Alternative Names (`dns:localhost`, `dns:ledger-service`, `ip:127.0.0.1`).
   - In Kubernetes (Phase 6), `cert-manager` manages certificates dynamically using an internal `ClusterIssuer`, mounting rotating TLS secrets directly into service pods.
4. **Packet Inspection Proof**:
   - Network capture via **Wireshark** on port 9091 (`tcp.port == 9091`) demonstrates:
     - Client Hello and Server Hello TLS handshakes.
     - Certificate exchange and mutual cipher suite negotiation (`TLS_AES_256_GCM_SHA384`).
     - All subsequent gRPC payload frames appear as encrypted `Application Data` without exposing financial payloads in plaintext.

## Consequences
- **Positive**:
  - Full end-to-end cryptographic encryption in transit.
  - Zero-Trust peer identity verification.
  - Rejection of unauthorized internal network calls before RPC invocation.
- **Negative / Tradeoffs**:
  - Minor CPU overhead for TLS handshake symmetric key establishment (mitigated via persistent HTTP/2 keep-alive connections).
  - Certificate management overhead and expiration monitoring.
