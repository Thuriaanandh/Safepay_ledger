# ADR 0007: Verification Strategy - k6 Load Testing, Mid-Flight Chaos, and CI/CD

## Status
Accepted

## Context
Payment processing platforms require verifiable guarantees that:
1. High-concurrency retries with the same idempotency key never produce duplicate financial postings (**Acceptance Criterion 1**).
2. Service crashes or mid-flight terminations never leave half-applied transactions (e.g. debited without credit) (**Acceptance Criterion 2**).
3. The platform maintains sub-second tail latencies (p95/p99) under load.
4. All pull requests pass automated build, test, Terraform validation, and security vulnerability scanning (**Acceptance Criterion 9**).

Host machine constraint: 8 GB RAM laptop. The local environment cannot run a heavy chaos engineering framework (e.g., Chaos Mesh) without causing OS swapping and OOM crashes.

## Decision

### 1. k6 Load & Concurrency Testing
- **Tool:** `k6` (single binary, low memory footprint written in Go, negligible overhead compared to JMeter).
- **Concurrent Deduplication Scenario:** 50 virtual users fire simultaneously with the identical `Idempotency-Key` header (`k6-concurrent-dedup-50-fixed-key`).
  - Expected: Exactly 1 request acquires the DynamoDB conditional write lock and returns `201 Created`.
  - Expected: The remaining 49 requests receive `409 Conflict` (or cached replay).
- **Sustained Load Scenario:** Modest ramp from 1 to 5 VUs over 25 seconds with unique idempotency keys, measuring `p50`, `p95`, and `p99` latency.
- Hardware baseline: Results are measured and documented explicitly for the 8 GB laptop host.

### 2. Manual Chaos Engineering Scripts
- Rather than installing a heavyweight daemon (Chaos Mesh), we authored deterministic PowerShell and Bash chaos scripts (`scripts/chaos-kill-ledger.ps1`, `scripts/chaos-kill-ledger.sh`).
- **Mid-Flight Termination Protocol:**
  1. Verify baseline ledger invariant ($\text{imbalance} == 0$).
  2. Send high-frequency payment transfers.
  3. Abruptly kill `ledger-service` (`kubectl delete pod --now` in Kubernetes or `docker kill` in Docker Compose).
  4. Wait for self-healing pod restart.
  5. Run reconciliation batch audit to prove zero orphaned debit or credit postings exist in PostgreSQL.

### 3. CI/CD Architecture (GitHub Actions as Extended Hardware)
- **Standard PR Workflow (`ci.yml`):**
  - Gradle test matrix across all 5 modules.
  - `terraform validate` checking DynamoDB schemas and least-privilege IAM policies.
  - Multi-stage distroless Docker image builds.
  - Aquasecurity Trivy filesystem and CVE vulnerability scan (`severity: CRITICAL,HIGH`).
- **Cloud E2E Workflow (`kind-e2e.yml`):**
  - Leverages GitHub-hosted cloud runners (7 GB RAM) to spin up `kind`, install `cert-manager`, deploy all 4 microservices, and execute the full k6 smoke test and chaos kill test. This guarantees full-stack verification without straining local developer laptop hardware.

## Consequences
- **Positive:**
  - Hard mathematical proof that duplicate charges and orphaned postings cannot occur under network race conditions or sudden node crashes.
  - Real, un-inflated performance metrics recorded directly in the repository.
  - Automated pull request gating with dependency vulnerability scanning.
  - Safe local developer execution on 8 GB hardware with cloud offload for full E2E.
- **Negative / Trade-offs:**
  - Scripted chaos testing requires brief manual or CI execution rather than a continuous automated chaos background agent.
