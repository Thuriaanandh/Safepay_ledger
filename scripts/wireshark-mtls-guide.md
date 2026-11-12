# Wireshark Verification Guide: Proving mTLS Encryption in SafePay Ledger

This guide demonstrates how to use **Wireshark** to prove that internal gRPC microservice traffic between `payment-api` and `ledger-service` is encrypted in transit using mutual TLS (mTLS).

---

## 1. Prerequisites
- Wireshark installed on your machine (`C:\Program Files\Wireshark\Wireshark.exe`).
- SafePay core services running (`make core` or local services on port 8080 and 9091).

---

## 2. Setting Up Wireshark Capture

1. Launch Wireshark.
2. Under **Capture**, double-click on the **Adapter for loopback traffic capture** (or `Npcap Loopback Adapter` on Windows).
3. In the display filter bar at the top, type the following filter and hit **Enter**:
   ```
   tcp.port == 9091
   ```
   *(Port 9091 is the internal gRPC port used by `ledger-service`).*

---

## 3. Generating Traffic

In PowerShell or terminal, fetch a self-issued RS256 token and execute an authenticated transfer:

```powershell
# 1. Fetch test JWT token
$authResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/token" -Method Get
$token = $authResponse.access_token

# 2. Send authenticated transfer with unique Idempotency-Key
$idempotencyKey = [System.Guid]::NewGuid().ToString()
$body = @{
    sourceAccountId = "ACC-ALICE-USD"
    destinationAccountId = "ACC-BOB-USD"
    amountCents = 25000
    currency = "USD"
    description = "Wireshark packet capture verification"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/v1/transfers" `
    -Method Post `
    -Headers @{
        "Authorization" = "Bearer $token"
        "Idempotency-Key" = $idempotencyKey
        "Content-Type" = "application/json"
    } `
    -Body $body
```

---

## 4. Packet Analysis in Wireshark

In the Wireshark packet list, observe the following protocol exchange:

| Frame # | Protocol | Info Summary | What it Proves |
|---|---|---|---|
| **1** | TCP | `SYN` / `SYN-ACK` / `ACK` | Standard TCP 3-way handshake on port 9091. |
| **2** | TLSv1.3 | `Client Hello` | `payment-api` initiates TLS negotiation, advertising supported cipher suites (e.g. `TLS_AES_256_GCM_SHA384`). |
| **3** | TLSv1.3 | `Server Hello` | `ledger-service` selects cipher suite and requests client certificate (`Certificate Request`). |
| **4** | TLSv1.3 | `Certificate, Certificate Verify, Finished` | Both sides mutually authenticate by presenting their internal CA-signed X.509 certs. |
| **5+** | TLSv1.3 | `Application Data` | **Encrypted gRPC protobuf frames.** |

### Cryptographic Proof:
- Click on any **`Application Data`** packet.
- Inspect the **Packet Bytes** pane at the bottom:
  - Notice that all bytes are high-entropy encrypted ciphertext.
  - No account numbers (`ACC-ALICE-USD`), no amounts (`25000`), and no gRPC method names (`ProcessTransfer`) are readable.
  - This proves that even an attacker sniffing local network packets cannot read or tamper with financial transaction data.
