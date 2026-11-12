package com.safepay.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Standard API Error Response")
public record ErrorResponseDto(

    @Schema(description = "HTTP Status Code", example = "422")
    int status,

    @Schema(description = "Detailed error message", example = "Insufficient funds in source account")
    String message,

    @Schema(description = "Error category or type", example = "Unprocessable Entity")
    String error,

    @Schema(description = "Timestamp in epoch milliseconds", example = "1700000000000")
    Long timestamp
) {}
