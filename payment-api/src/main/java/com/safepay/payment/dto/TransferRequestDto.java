package com.safepay.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "P2P Payment Transfer Request")
public record TransferRequestDto(

    @Schema(description = "Source account number to debit", example = "ACC-ALICE-USD")
    @NotBlank(message = "Source account ID is required")
    String sourceAccountId,

    @Schema(description = "Destination account number to credit", example = "ACC-BOB-USD")
    @NotBlank(message = "Destination account ID is required")
    String destinationAccountId,

    @Schema(description = "Transfer amount in integer minor units (cents)", example = "10000")
    @NotNull(message = "Amount in cents is required")
    @Positive(message = "Amount in cents must be strictly positive")
    Long amountCents,

    @Schema(description = "ISO-4217 Currency Code", example = "USD")
    @NotBlank(message = "Currency is required")
    String currency,

    @Schema(description = "Optional transaction description or payment memo", example = "Dinner reimbursement")
    String description
) {}
