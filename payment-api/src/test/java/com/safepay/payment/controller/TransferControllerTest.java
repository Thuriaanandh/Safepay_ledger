package com.safepay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.payment.client.LedgerGrpcClient;
import com.safepay.payment.config.SecurityConfig;
import com.safepay.payment.dto.TransferRequestDto;
import com.safepay.payment.dto.TransferResponseDto;
import com.safepay.payment.idempotency.IdempotencyResult;
import com.safepay.payment.idempotency.IdempotencyService;
import com.safepay.payment.security.JwtTokenProvider;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransferController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class})
@AutoConfigureMockMvc(addFilters = false)
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IdempotencyService idempotencyService;

    @MockBean
    private LedgerGrpcClient ledgerGrpcClient;

    private TransferRequestDto validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new TransferRequestDto(
            "ACC-ALICE-USD",
            "ACC-BOB-USD",
            500_00L,
            "USD",
            "Test Transfer"
        );
    }

    @Test
    @DisplayName("REST: Should successfully initiate transfer and return 201 Created")
    void testInitiateTransferSuccess() throws Exception {
        when(idempotencyService.acquire(eq("ik-101"), anyString())).thenReturn(IdempotencyResult.acquired());

        TransferResponseDto responseDto = new TransferResponseDto(
            "tx-uuid-101",
            "ik-101",
            "COMPLETED",
            500_00L,
            "USD",
            1700000000000L
        );
        when(ledgerGrpcClient.processTransfer(anyString(), eq("ik-101"), any(TransferRequestDto.class)))
            .thenReturn(responseDto);

        mockMvc.perform(post("/v1/transfers")
                .header("Idempotency-Key", "ik-101")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transferId").value("tx-uuid-101"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.amountCents").value(50000));

        verify(idempotencyService).recordSuccess(eq("ik-101"), eq(201), anyString());
    }

    @Test
    @DisplayName("REST: Should return cached response on idempotent retry without calling gRPC")
    void testIdempotentCachedReplay() throws Exception {
        TransferResponseDto cachedDto = new TransferResponseDto(
            "tx-cached-001",
            "ik-cached-1",
            "COMPLETED",
            500_00L,
            "USD",
            1700000000000L
        );
        String cachedJson = objectMapper.writeValueAsString(cachedDto);

        when(idempotencyService.acquire(eq("ik-cached-1"), anyString()))
            .thenReturn(IdempotencyResult.cached(201, cachedJson));

        mockMvc.perform(post("/v1/transfers")
                .header("Idempotency-Key", "ik-cached-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transferId").value("tx-cached-001"))
            .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Crucial: gRPC client must NOT be called on cache hit
        verifyNoInteractions(ledgerGrpcClient);
    }

    @Test
    @DisplayName("REST: Should return 409 Conflict when request is currently IN_PROGRESS")
    void testConcurrentInProgressConflict() throws Exception {
        when(idempotencyService.acquire(eq("ik-in-flight"), anyString()))
            .thenReturn(IdempotencyResult.inProgress());

        mockMvc.perform(post("/v1/transfers")
                .header("Idempotency-Key", "ik-in-flight")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.message").value(containsString("currently in progress")));

        verifyNoInteractions(ledgerGrpcClient);
    }

    @Test
    @DisplayName("REST: Should return 422 Unprocessable Entity when gRPC returns FAILED_PRECONDITION (insufficient funds)")
    void testInsufficientFundsFromGrpc() throws Exception {
        when(idempotencyService.acquire(eq("ik-insufficient"), anyString())).thenReturn(IdempotencyResult.acquired());

        when(ledgerGrpcClient.processTransfer(anyString(), eq("ik-insufficient"), any(TransferRequestDto.class)))
            .thenThrow(new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Insufficient funds in account")));

        mockMvc.perform(post("/v1/transfers")
                .header("Idempotency-Key", "ik-insufficient")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.message").value(containsString("Insufficient funds")));

        verify(idempotencyService).recordFailure(eq("ik-insufficient"), eq(422), anyString());
    }

    @Test
    @DisplayName("REST: Should return 504 Gateway Timeout when gRPC deadline exceeded")
    void testGrpcDeadlineExceeded() throws Exception {
        when(idempotencyService.acquire(eq("ik-timeout"), anyString())).thenReturn(IdempotencyResult.acquired());

        when(ledgerGrpcClient.processTransfer(anyString(), eq("ik-timeout"), any(TransferRequestDto.class)))
            .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Deadline exceeded")));

        mockMvc.perform(post("/v1/transfers")
                .header("Idempotency-Key", "ik-timeout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isGatewayTimeout())
            .andExpect(jsonPath("$.status").value(504));
    }

    @Test
    @DisplayName("REST: Should reject request when Idempotency-Key header is missing")
    void testMissingIdempotencyHeader() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")));
    }
}
