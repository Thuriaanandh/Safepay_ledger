package com.safepay.ledger.service;

public record TransferCommand(
    String transferId,
    String sourceAccountNumber,
    String destinationAccountNumber,
    long amountCents,
    String currency,
    String description
) {
    public TransferCommand {
        if (transferId == null || transferId.isBlank()) {
            throw new IllegalArgumentException("transferId cannot be empty");
        }
        if (sourceAccountNumber == null || sourceAccountNumber.isBlank()) {
            throw new IllegalArgumentException("sourceAccountNumber cannot be empty");
        }
        if (destinationAccountNumber == null || destinationAccountNumber.isBlank()) {
            throw new IllegalArgumentException("destinationAccountNumber cannot be empty");
        }
        if (sourceAccountNumber.equals(destinationAccountNumber)) {
            throw new IllegalArgumentException("Source and destination accounts cannot be identical");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency cannot be empty");
        }
    }
}
