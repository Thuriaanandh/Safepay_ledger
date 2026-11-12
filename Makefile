.PHONY: help core obs obs-down k8s k8s-down down clean build test stats

help:
	@echo "SafePay Ledger Build & Lifecycle Targets:"
	@echo "  make core     - Start Postgres, Kafka (KRaft), LocalStack (DynamoDB)"
	@echo "  make obs      - Start Prometheus, Grafana, Jaeger (Phase 5)"
	@echo "  make obs-down - Stop observability stack"
	@echo "  make k8s      - Spin up kind cluster with cert-manager (Phase 6)"
	@echo "  make k8s-down - Tear down kind cluster"
	@echo "  make down     - Stop all running containers"
	@echo "  make build    - Build all modules with Gradle"
	@echo "  make test     - Run JUnit tests"
	@echo "  make stats    - Display docker memory usage"

core:
	docker compose -f docker-compose.yml up -d
	@echo "Core dependencies running. Run 'make stats' to monitor RAM."

obs:
	docker compose -f docker-compose.obs.yml up -d
	@echo "Observability stack running (Prometheus:9090, Grafana:3000, Jaeger:16686)."

obs-down:
	docker compose -f docker-compose.obs.yml down

k8s:
	kind create cluster --name safepay --config k8s/kind-config.yaml
	@echo "kind cluster 'safepay' created."

k8s-down:
	kind delete cluster --name safepay

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
