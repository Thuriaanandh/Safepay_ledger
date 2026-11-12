package com.safepay.payment.idempotency;

public record IdempotencyResult(
    Outcome outcome,
    Integer responseCode,
    String responseBody,
    String errorMessage
) {
    public enum Outcome {
        ACQUIRED,
        CACHED,
        IN_PROGRESS,
        CONFLICT
    }

    public static IdempotencyResult acquired() {
        return new IdempotencyResult(Outcome.ACQUIRED, null, null, null);
    }

    public static IdempotencyResult cached(int responseCode, String responseBody) {
        return new IdempotencyResult(Outcome.CACHED, responseCode, responseBody, null);
    }

    public static IdempotencyResult inProgress() {
        return new IdempotencyResult(Outcome.IN_PROGRESS, 409, null, "Concurrent transfer with this idempotency key is currently in progress");
    }

    public static IdempotencyResult conflict(String message) {
        return new IdempotencyResult(Outcome.CONFLICT, 422, null, message);
    }
}
