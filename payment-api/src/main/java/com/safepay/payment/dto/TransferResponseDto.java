package com.safepay.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "P2P Payment Transfer Response")
public record TransferResponseDto(

    @Schema(description = "Unique ledger transfer ID", example = "550e8400-e29b-41d4-a716-446655440000")
    String transferId,

    @Schema(description = "Client provided idempotency key", example = "ik-alice-bob-992")
    String idempotencyKey,

    @Schema(description = "Transfer execution status", example = "COMPLETED")
    String status,

    @Schema(description = "Transfer amount in integer minor units (cents)", example = "10000")
    Long amountCents,

    @Schema(description = "Currency code", example = "USD")
    String currency,

    @Schema(description = "Creation timestamp in epoch milliseconds", example = "1700000000000")
    Long createdAt
) {}
