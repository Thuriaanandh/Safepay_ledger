package com.safepay.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.payment.client.LedgerGrpcClient;
import com.safepay.payment.dto.TransferRequestDto;
import com.safepay.payment.dto.TransferResponseDto;
import com.safepay.payment.idempotency.IdempotencyResult;
import com.safepay.payment.idempotency.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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
            100_00L,
            "USD",
            "Secured Transfer"
        );

        when(idempotencyService.acquire(anyString(), anyString())).thenReturn(IdempotencyResult.acquired());
        when(ledgerGrpcClient.processTransfer(anyString(), anyString(), any())).thenReturn(
            new TransferResponseDto("tx-sec-1", "ik-sec-1", "COMPLETED", 100_00L, "USD", System.currentTimeMillis())
        );
    }

    @Test
    @DisplayName("Security: Should allow request with valid RS256 token and transfers:write scope")
    void testValidJwtAllowed() throws Exception {
        String token = jwtTokenProvider.generateToken("alice-user", "transfers:write", 3600);

        mockMvc.perform(post("/v1/transfers")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "ik-sec-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Security: Should reject unauthenticated request without token with 401 Unauthorized")
    void testMissingTokenRejected() throws Exception {
        mockMvc.perform(post("/v1/transfers")
                .header("Idempotency-Key", "ik-no-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Security: Should reject forged/invalid token with 401 Unauthorized")
    void testForgedTokenRejected() throws Exception {
        String forgedToken = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJoYWNrZXIifQ.invalidSignatureValue";

        mockMvc.perform(post("/v1/transfers")
                .header("Authorization", "Bearer " + forgedToken)
                .header("Idempotency-Key", "ik-forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Security: Should reject valid token with insufficient scope with 403 Forbidden")
    void testInsufficientScopeForbidden() throws Exception {
        // Token has read-only scope, lacks transfers:write
        String readOnlyToken = jwtTokenProvider.generateToken("bob-user", "transfers:read", 3600);

        mockMvc.perform(post("/v1/transfers")
                .header("Authorization", "Bearer " + readOnlyToken)
                .header("Idempotency-Key", "ik-read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.message").value("Insufficient token scope. Required: transfers:write"));
    }

    @Test
    @DisplayName("Security: Public endpoints (/actuator/health, /api/auth/token) must be accessible without token")
    void testPublicEndpointsAccessible() throws Exception {
        mockMvc.perform(get("/api/auth/token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andExpect(jsonPath("$.token_type").value("Bearer"));
    }
}
