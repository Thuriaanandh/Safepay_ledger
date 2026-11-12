package com.safepay.ledger.service;

import com.safepay.ledger.domain.TransactionStatus;

public record TransferResult(
    String transferId,
    TransactionStatus status,
    long amountCents,
    String currency,
    String errorMessage,
    long timestamp
) {}
