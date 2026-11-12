# scripts/generate-pki.ps1
# Generates Root CA, Server (ledger-service), and Client (payment-api) certificates using JDK keytool.

$ErrorActionPreference = "Stop"

$keytool = "C:\Program Files\Java\jdk-21\bin\keytool.exe"
if (-not (Test-Path $keytool)) {
    $javaCmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($javaCmd) { $keytool = $javaCmd.Source }
}

$outDir = "$PSScriptRoot\..\infra\certs"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force }

$storePass = "safepay_secret"

Write-Host "1. Generating Root CA..."
$caKeystore = "$outDir\ca.p12"
if (Test-Path $caKeystore) { Remove-Item $caKeystore -Force }
& $keytool -genkeypair -alias ca -keyalg RSA -keysize 2048 -validity 3650 `
    -keystore $caKeystore -storepass $storePass -storetype PKCS12 `
    -dname "CN=SafePay Internal Root CA, O=SafePay, C=US" `
    -ext "BasicConstraints:critical=ca:true"

& $keytool -exportcert -alias ca -keystore $caKeystore -storepass $storePass -rfc `
    -file "$outDir\ca.crt"

Write-Host "2. Generating ledger-service Server Certificate..."
$ledgerKeystore = "$outDir\ledger-service.p12"
if (Test-Path $ledgerKeystore) { Remove-Item $ledgerKeystore -Force }
& $keytool -genkeypair -alias ledger-service -keyalg RSA -keysize 2048 -validity 365 `
    -keystore $ledgerKeystore -storepass $storePass -storetype PKCS12 `
    -dname "CN=ledger-service, O=SafePay, C=US" `
    -ext "SAN=dns:localhost,dns:ledger-service,ip:127.0.0.1"

$ledgerCsr = "$outDir\ledger.csr"
& $keytool -certreq -alias ledger-service -keystore $ledgerKeystore -storepass $storePass `
    -file $ledgerCsr -ext "SAN=dns:localhost,dns:ledger-service,ip:127.0.0.1"

& $keytool -gencert -alias ca -keystore $caKeystore -storepass $storePass `
    -infile $ledgerCsr -outfile "$outDir\ledger-service.crt" -rfc -validity 365 `
    -ext "SAN=dns:localhost,dns:ledger-service,ip:127.0.0.1"

Write-Host "3. Generating payment-api Client Certificate..."
$paymentKeystore = "$outDir\payment-api.p12"
if (Test-Path $paymentKeystore) { Remove-Item $paymentKeystore -Force }
& $keytool -genkeypair -alias payment-api -keyalg RSA -keysize 2048 -validity 365 `
    -keystore $paymentKeystore -storepass $storePass -storetype PKCS12 `
    -dname "CN=payment-api, O=SafePay, C=US" `
    -ext "SAN=dns:localhost,dns:payment-api,ip:127.0.0.1"

$paymentCsr = "$outDir\payment.csr"
& $keytool -certreq -alias payment-api -keystore $paymentKeystore -storepass $storePass `
    -file $paymentCsr -ext "SAN=dns:localhost,dns:payment-api,ip:127.0.0.1"

& $keytool -gencert -alias ca -keystore $caKeystore -storepass $storePass `
    -infile $paymentCsr -outfile "$outDir\payment-api.crt" -rfc -validity 365 `
    -ext "SAN=dns:localhost,dns:payment-api,ip:127.0.0.1"

# Clean up CSRs
Remove-Item $ledgerCsr, $paymentCsr -Force -ErrorAction SilentlyContinue

Write-Host "Certificates generated successfully in $outDir"
