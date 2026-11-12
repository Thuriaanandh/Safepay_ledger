package com.safepay.payment.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(dynamoDbClient, "safepay_idempotency_keys");
    }

    @Test
    @DisplayName("Should successfully acquire lock on first request")
    void testAcquireLockSuccess() {
        when(dynamoDbClient.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        IdempotencyResult result = idempotencyService.acquire("ik-test-1", "{\"amount\":100}");

        assertThat(result.outcome()).isEqualTo(IdempotencyResult.Outcome.ACQUIRED);
        verify(dynamoDbClient).putItem(any(PutItemRequest.class));
    }

    @Test
    @DisplayName("Should return cached response when existing record is COMPLETED")
    void testReturnCachedResponse() {
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
            .thenThrow(ConditionalCheckFailedException.builder().message("Key already exists").build());

        String payload = "{\"amount\":100}";
        String hash = idempotencyService.computeSha256(payload);

        Map<String, AttributeValue> item = Map.of(
            "idempotency_key", AttributeValue.builder().s("ik-test-2").build(),
            "request_hash", AttributeValue.builder().s(hash).build(),
            "status", AttributeValue.builder().s("COMPLETED").build(),
            "response_code", AttributeValue.builder().n("201").build(),
            "response_body", AttributeValue.builder().s("{\"transferId\":\"tx-1\"}").build()
        );

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());

        IdempotencyResult result = idempotencyService.acquire("ik-test-2", payload);

        assertThat(result.outcome()).isEqualTo(IdempotencyResult.Outcome.CACHED);
        assertThat(result.responseCode()).isEqualTo(201);
        assertThat(result.responseBody()).isEqualTo("{\"transferId\":\"tx-1\"}");
    }

    @Test
    @DisplayName("Should return IN_PROGRESS when concurrent request is running")
    void testReturnInProgress() {
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
            .thenThrow(ConditionalCheckFailedException.builder().message("Key already exists").build());

        String payload = "{\"amount\":100}";
        String hash = idempotencyService.computeSha256(payload);

        Map<String, AttributeValue> item = Map.of(
            "idempotency_key", AttributeValue.builder().s("ik-test-3").build(),
            "request_hash", AttributeValue.builder().s(hash).build(),
            "status", AttributeValue.builder().s("IN_PROGRESS").build()
        );

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());

        IdempotencyResult result = idempotencyService.acquire("ik-test-3", payload);

        assertThat(result.outcome()).isEqualTo(IdempotencyResult.Outcome.IN_PROGRESS);
        assertThat(result.responseCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("Should detect parameter mismatch when key is reused with different payload")
    void testDetectParameterMismatch() {
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
            .thenThrow(ConditionalCheckFailedException.builder().message("Key already exists").build());

        // Stored hash differs from incoming payload
        Map<String, AttributeValue> item = Map.of(
            "idempotency_key", AttributeValue.builder().s("ik-test-4").build(),
            "request_hash", AttributeValue.builder().s("different-hash-value").build(),
            "status", AttributeValue.builder().s("COMPLETED").build()
        );

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());

        IdempotencyResult result = idempotencyService.acquire("ik-test-4", "{\"amount\":999}");

        assertThat(result.outcome()).isEqualTo(IdempotencyResult.Outcome.CONFLICT);
        assertThat(result.errorMessage()).contains("different request payload parameters");
    }
}
