# SafePay Ledger - Run a microservice with OpenTelemetry Java Agent attached
# Usage: .\scripts\run-with-otel.ps1 -Service payment-api (or ledger-service, notification-worker, reconciliation)

param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("payment-api", "ledger-service", "notification-worker", "reconciliation")]
    [string]$Service,

    [int]$Port = 0
)

$PkiDir = "$PSScriptRoot\..\infra\certs"
$OtelJar = "$PSScriptRoot\..\infra\otel\opentelemetry-javaagent.jar"

if (-not (Test-Path $OtelJar)) {
    Write-Host "Downloading OpenTelemetry Java Agent..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path "$PSScriptRoot\..\infra\otel" | Out-Null
    curl.exe -L -o $OtelJar "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.8.0/opentelemetry-javaagent.jar"
}

Write-Host "Launching $Service with OpenTelemetry Agent and Jaeger Exporter..." -ForegroundColor Green

$env:OTEL_SERVICE_NAME = $Service
$env:OTEL_TRACES_EXPORTER = "otlp"
$env:OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4318"
$env:OTEL_EXPORTER_OTLP_PROTOCOL = "http/protobuf"
$env:OTEL_METRICS_EXPORTER = "none" # Prometheus scrapes Micrometer directly
$env:OTEL_LOGS_EXPORTER = "none"    # Logback handles structured JSON

$JvmOpts = "-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -javaagent:$OtelJar"

if ($Port -gt 0) {
    .\gradlew ":$Service:bootRun" --args="--server.port=$Port" -Dorg.gradle.jvmargs="$JvmOpts"
} else {
    .\gradlew ":$Service:bootRun" -Dorg.gradle.jvmargs="$JvmOpts"
}
