# ADR 0006: Kubernetes Plain YAML Orchestration and cert-manager PKI

## Status
Accepted

## Context
Deploying SafePay Ledger to a local Kubernetes environment (`kind`) requires packaging the Java microservices into secure container images, provisioning cloud infrastructure as code (DynamoDB idempotency store), orchestrating service discovery, and managing mutual TLS certificates across service boundaries.

The requirements strictly enforce:
1. Docker: multi-stage, non-root, distroless base.
2. Orchestration: Kubernetes via `kind` with plain YAML manifests (strictly NO Helm, NO Kustomize).
3. Certificate management: cert-manager with a self-signed Root CA issuer.
4. IaC: Terraform for DynamoDB + least-privilege IAM policies.
5. Strict memory budget: ~4.5 GB max for Docker / WSL2 on an 8 GB host machine.

## Decision

### 1. Multi-Stage Distroless Packaging
We adopted `gcr.io/distroless/java21-debian12:nonroot` as the runtime base image for all 4 microservices (`payment-api`, `ledger-service`, `notification-worker`, `reconciliation`):
- **Attack Surface Minimization:** Distroless images contain no shells (`sh`, `bash`), package managers (`apt`, `apk`), or operating system utilities. If an attacker discovers an arbitrary code execution vulnerability (e.g., Log4Shell-style exploits), they cannot execute interactive shell payloads or download external binaries.
- **Non-Root Execution:** Containers execute strictly under UID/GID `65532:65532` (`nonroot`), preventing root escalation attacks.
- **Hardware Budget:** Memory limits are enforced at the JVM level via `JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"`.

### 2. Plain Kubernetes Manifests (No Helm / No Kustomize)
We chose plain, explicit Kubernetes YAML manifests stored in `k8s/`:
- **Readability & Auditability:** In financial payments teams, compliance audits mandate knowing the exact configuration running on a cluster. Templating engines (Helm) introduce indirection, whereas plain YAML guarantees WYSIWYG transparency.
- **Production Resilience:**
  - **Liveness & Readiness Probes:** Bound to Spring Boot Actuator endpoints (`/actuator/health/liveness`, `/actuator/health/readiness`).
  - **PodDisruptionBudget (PDB):** Mandates `minAvailable: 1` during node maintenance, preventing service downtime.
  - **HorizontalPodAutoscaler (HPA):** Auto-scales `payment-api` from 1 to 2 replicas based on CPU threshold (75%).
  - **Resource Bounds:** Total requested RAM across all services is capped at ~1.4 GB, with hard limits at ~2.5 GB.

### 3. Automated PKI with cert-manager
Instead of manually distributing static certificates in Kubernetes, we deployed `cert-manager`:
- A self-signed `ClusterIssuer` bootstraps a Root CA (`safepay-root-ca-secret`).
- A subordinate `ClusterIssuer` (`safepay-ca-issuer`) automatically signs and rotates leaf certificates for `ledger-service` and `payment-api`.
- Certificates are injected directly as Kubernetes TLS secrets (`ledger-service-tls`, `payment-api-tls`) and mounted into `/etc/certs/` inside the respective pods.

### 4. Infrastructure as Code (Terraform + LocalStack)
We configured Terraform in `terraform/` managing:
- DynamoDB table `safepay_idempotency_keys` with TTL (`expires_at`), point-in-time recovery, and server-side encryption.
- Least-privilege IAM policy restricting `payment-api` to `dynamodb:GetItem`, `dynamodb:PutItem`, and `dynamodb:UpdateItem`.

## Consequences
- **Positive:**
  - Full parity between local development and cloud-native Kubernetes environments.
  - Zero vulnerability noise from base OS packages due to distroless images.
  - Automated, self-healing TLS certificate rotation via cert-manager.
  - Predictable CPU and RAM usage safely within host laptop limits.
- **Negative / Trade-offs:**
  - Plain YAML requires duplicating shared environment variables across manifests (mitigated by ConfigMaps/Secrets).
  - Distroless images lack shells, requiring ephemeral debug containers (`kubectl debug`) if live container inspection is required.
