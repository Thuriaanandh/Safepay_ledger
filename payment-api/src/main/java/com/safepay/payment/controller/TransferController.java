package com.safepay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.payment.client.LedgerGrpcClient;
import com.safepay.payment.dto.ErrorResponseDto;
import com.safepay.payment.dto.TransferRequestDto;
import com.safepay.payment.dto.TransferResponseDto;
import com.safepay.payment.idempotency.IdempotencyResult;
import com.safepay.payment.idempotency.IdempotencyService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/transfers")
@Tag(name = "Transfers", description = "Duplicate-Proof P2P Transfer API")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final IdempotencyService idempotencyService;
    private final LedgerGrpcClient ledgerGrpcClient;
    private final ObjectMapper objectMapper;

    public TransferController(
        IdempotencyService idempotencyService,
        LedgerGrpcClient ledgerGrpcClient,
        ObjectMapper objectMapper
    ) {
        this.idempotencyService = idempotencyService;
        this.ledgerGrpcClient = ledgerGrpcClient;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Initiate an Idempotent P2P Transfer",
        description = "Processes a money transfer between accounts. Guarantee: Exactly-once execution in effect via atomic DynamoDB conditional write on Idempotency-Key."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Transfer successfully created and committed", content = @Content(schema = @Schema(implementation = TransferResponseDto.class))),
        @ApiResponse(responseCode = "200", description = "Idempotent replay: cached transfer response returned", content = @Content(schema = @Schema(implementation = TransferResponseDto.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload parameters", content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "404", description = "Source or destination account not found", content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "409", description = "Concurrent transfer with this idempotency key in progress", content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "422", description = "Insufficient funds or parameter conflict with existing idempotency key", content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "504", description = "Ledger gRPC deadline exceeded", content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
    })
    public ResponseEntity<?> initiateTransfer(
        @Parameter(description = "Unique client-generated idempotency key (UUID recommended)", required = true, example = "ik-7f93b821-411a-4d2b")
        @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,

        @Valid @RequestBody TransferRequestDto request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().body(
                new ErrorResponseDto(400, "Idempotency-Key header is required", "Bad Request", Instant.now().toEpochMilli())
            );
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                new ErrorResponseDto(400, "Failed to serialize request body", "Bad Request", Instant.now().toEpochMilli())
            );
        }

        // 1. Atomic Idempotency Check in DynamoDB
        IdempotencyResult lockResult = idempotencyService.acquire(idempotencyKey, payloadJson);

        if (lockResult.outcome() == IdempotencyResult.Outcome.CACHED) {
            log.info("Idempotent replay for key: {}", idempotencyKey);
            try {
                TransferResponseDto cachedResponse = objectMapper.readValue(lockResult.responseBody(), TransferResponseDto.class);
                return ResponseEntity.status(lockResult.responseCode()).body(cachedResponse);
            } catch (Exception e) {
                // If cached body was an error
                return ResponseEntity.status(lockResult.responseCode()).body(lockResult.responseBody());
            }
        }

        if (lockResult.outcome() == IdempotencyResult.Outcome.IN_PROGRESS) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ErrorResponseDto(409, lockResult.errorMessage(), "Conflict", Instant.now().toEpochMilli())
            );
        }

        if (lockResult.outcome() == IdempotencyResult.Outcome.CONFLICT) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ErrorResponseDto(422, lockResult.errorMessage(), "Unprocessable Entity", Instant.now().toEpochMilli())
            );
        }

        // 2. Lock Acquired: Dispatch to Ledger Service via gRPC
        String transferId = UUID.randomUUID().toString();
        try {
            TransferResponseDto responseDto = ledgerGrpcClient.processTransfer(transferId, idempotencyKey, request);
            String responseJson = objectMapper.writeValueAsString(responseDto);

            // 3. Mark idempotency as COMPLETED
            idempotencyService.recordSuccess(idempotencyKey, HttpStatus.CREATED.value(), responseJson);

            return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
        } catch (StatusRuntimeException e) {
            HttpStatus status = mapGrpcStatusToHttp(e.getStatus().getCode());
            String desc = e.getStatus().getDescription() != null ? e.getStatus().getDescription() : e.getMessage();
            ErrorResponseDto errorDto = new ErrorResponseDto(status.value(), desc, status.getReasonPhrase(), Instant.now().toEpochMilli());

            // 4. Record terminal failure in idempotency store
            try {
                String errorJson = objectMapper.writeValueAsString(errorDto);
                idempotencyService.recordFailure(idempotencyKey, status.value(), errorJson);
            } catch (Exception ignored) {}

            return ResponseEntity.status(status).body(errorDto);
        } catch (Exception e) {
            log.error("Unexpected error processing transfer for key {}: {}", idempotencyKey, e.getMessage(), e);
            ErrorResponseDto errorDto = new ErrorResponseDto(500, "Internal error: " + e.getMessage(), "Internal Server Error", Instant.now().toEpochMilli());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorDto);
        }
    }

    private HttpStatus mapGrpcStatusToHttp(Status.Code code) {
        return switch (code) {
            case FAILED_PRECONDITION -> HttpStatus.UNPROCESSABLE_ENTITY; // 422 (e.g. Insufficient funds)
            case NOT_FOUND -> HttpStatus.NOT_FOUND;                     // 404
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;            // 400
            case ABORTED -> HttpStatus.CONFLICT;                        // 409 (Optimistic lock conflict)
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;       // 504
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;         // 503
            default -> HttpStatus.INTERNAL_SERVER_ERROR;                // 500
        };
    }
}
