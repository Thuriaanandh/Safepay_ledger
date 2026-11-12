.PHONY: help core obs obs-down k8s k8s-deploy k8s-down down clean build test stats docker-build tf-init tf-validate load chaos

help:
	@echo "SafePay Ledger Build & Lifecycle Targets:"
	@echo "  make core         - Start Postgres, Kafka (KRaft), LocalStack (DynamoDB)"
	@echo "  make obs          - Start Prometheus, Grafana, Jaeger (Phase 5)"
	@echo "  make obs-down     - Stop observability stack"
	@echo "  make k8s          - Spin up kind cluster with cert-manager (Phase 6)"
	@echo "  make k8s-deploy   - Deploy plain Kubernetes manifests to kind"
	@echo "  make k8s-down     - Tear down kind cluster"
	@echo "  make tf-init      - Initialize Terraform against LocalStack"
	@echo "  make tf-validate  - Validate Terraform syntax and policies"
	@echo "  make docker-build - Build distroless Docker images for all services"
	@echo "  make load         - Run k6 load test and 50 concurrent deduplication check"
	@echo "  make chaos        - Run manual chaos kill test on ledger-service"
	@echo "  make down         - Stop all running containers"
	@echo "  make build        - Build all modules with Gradle"
	@echo "  make test         - Run JUnit tests"
	@echo "  make stats        - Display docker memory usage"

core:
	docker compose -f docker-compose.yml up -d
	@echo "Core dependencies running. Run 'make stats' to monitor RAM."

obs:
	docker compose -f docker-compose.obs.yml up -d
	@echo "Observability stack running (Prometheus:9090, Grafana:3000, Jaeger:16686)."

obs-down:
	docker compose -f docker-compose.obs.yml down

docker-build:
	docker build -t payment-api:latest -f payment-api/Dockerfile .
	docker build -t ledger-service:latest -f ledger-service/Dockerfile .
	docker build -t notification-worker:latest -f notification-worker/Dockerfile .
	docker build -t reconciliation:latest -f reconciliation/Dockerfile .

tf-init:
	cd terraform && terraform init

tf-validate:
	cd terraform && terraform validate

k8s:
	kind create cluster --name safepay --config k8s/kind-config.yaml
	@echo "Installing cert-manager..."
	kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.16.1/cert-manager.yaml
	@echo "Waiting for cert-manager webhook to become available..."
	kubectl wait --for=condition=Available deployment/cert-manager-webhook -n cert-manager --timeout=120s
	@echo "kind cluster 'safepay' with cert-manager is ready. Run 'make k8s-deploy' to apply workloads."

k8s-deploy:
	kind load docker-image payment-api:latest --name safepay
	kind load docker-image ledger-service:latest --name safepay
	kind load docker-image notification-worker:latest --name safepay
	kind load docker-image reconciliation:latest --name safepay
	kubectl apply -f k8s/00-namespace.yaml
	kubectl apply -f k8s/01-postgres.yaml
	kubectl apply -f k8s/02-kafka.yaml
	kubectl apply -f k8s/03-localstack.yaml
	kubectl apply -f k8s/04-cert-manager.yaml
	kubectl apply -f k8s/05-ledger-service.yaml
	kubectl apply -f k8s/06-payment-api.yaml
	kubectl apply -f k8s/07-notification-worker.yaml
	kubectl apply -f k8s/08-reconciliation-cronjob.yaml
	@echo "SafePay Ledger microservices deployed to kind cluster."

k8s-down:
	kind delete cluster --name safepay

load:
	k6 run k6/transfer-load-test.js

chaos:
	powershell -ExecutionPolicy Bypass -File scripts/chaos-kill-ledger.ps1 -Target docker

down:
	docker compose -f docker-compose.yml down
	docker compose -f docker-compose.obs.yml down

clean:
	gradlew clean

build:
	gradlew build -x test

test:
	gradlew test

stats:
	docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"
