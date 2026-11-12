package com.safepay.ledger.exception;

public class LedgerInvariantViolationException extends RuntimeException {
    public LedgerInvariantViolationException(String message) {
        super(message);
    }
}
