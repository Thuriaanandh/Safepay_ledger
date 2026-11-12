package com.safepay.payment.idempotency;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public IdempotencyService(
        DynamoDbClient dynamoDbClient,
        @Value("${aws.dynamodb.table-name:safepay_idempotency_keys}") String tableName
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @PostConstruct
    public void initTable() {
        try {
            createTableIfNotExists();
        } catch (Exception e) {
            log.warn("Could not verify/create DynamoDB table '{}': {}", tableName, e.getMessage());
        }
    }

    /**
     * Attempts to acquire an atomic idempotency lock via DynamoDB conditional put.
     * Condition: attribute_not_exists(idempotency_key)
     */
    public IdempotencyResult acquire(String idempotencyKey, String payload) {
        String requestHash = computeSha256(payload);
        long nowMillis = Instant.now().toEpochMilli();
        long ttlSeconds = Instant.now().getEpochSecond() + (24 * 3600); // 24-hour expiration

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("idempotency_key", AttributeValue.builder().s(idempotencyKey).build());
        item.put("request_hash", AttributeValue.builder().s(requestHash).build());
        item.put("status", AttributeValue.builder().s(IdempotencyStatus.IN_PROGRESS.name()).build());
        item.put("created_at", AttributeValue.builder().n(String.valueOf(nowMillis)).build());
        item.put("ttl", AttributeValue.builder().n(String.valueOf(ttlSeconds)).build());

        PutItemRequest putRequest = PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
            .conditionExpression("attribute_not_exists(idempotency_key)")
            .build();

        try {
            dynamoDbClient.putItem(putRequest);
            log.info("Idempotency lock acquired for key: {}", idempotencyKey);
            return IdempotencyResult.acquired();
        } catch (ConditionalCheckFailedException e) {
            log.info("Idempotency collision detected for key: {}. Checking existing record...", idempotencyKey);
            return handleCollision(idempotencyKey, requestHash);
        }
    }

    private IdempotencyResult handleCollision(String idempotencyKey, String currentRequestHash) {
        Map<String, AttributeValue> key = Map.of(
            "idempotency_key", AttributeValue.builder().s(idempotencyKey).build()
        );

        GetItemResponse response = dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .consistentRead(true)
                .build()
        );

        if (!response.hasItem()) {
            return IdempotencyResult.inProgress();
        }

        Map<String, AttributeValue> item = response.item();
        String storedHash = item.get("request_hash").s();

        // Parameter mismatch verification: Cannot reuse key with different request payload
        if (!storedHash.equals(currentRequestHash)) {
            log.warn("Idempotency key '{}' reused with different request payload parameters", idempotencyKey);
            return IdempotencyResult.conflict("Idempotency key reused with different request payload parameters");
        }

        String statusStr = item.get("status").s();
        IdempotencyStatus status = IdempotencyStatus.valueOf(statusStr);

        if (status == IdempotencyStatus.COMPLETED || status == IdempotencyStatus.FAILED) {
            int responseCode = Integer.parseInt(item.get("response_code").n());
            String responseBody = item.get("response_body").s();
            log.info("Idempotency cache hit for key: {}. Returning cached status {}", idempotencyKey, responseCode);
            return IdempotencyResult.cached(responseCode, responseBody);
        }

        // Another concurrent request with the exact same key is currently running
        log.info("Idempotency key '{}' is currently IN_PROGRESS", idempotencyKey);
        return IdempotencyResult.inProgress();
    }

    public void recordSuccess(String idempotencyKey, int statusCode, String responseBody) {
        updateRecord(idempotencyKey, IdempotencyStatus.COMPLETED, statusCode, responseBody);
    }

    public void recordFailure(String idempotencyKey, int statusCode, String responseBody) {
        updateRecord(idempotencyKey, IdempotencyStatus.FAILED, statusCode, responseBody);
    }

    private void updateRecord(String idempotencyKey, IdempotencyStatus status, int statusCode, String responseBody) {
        Map<String, AttributeValue> key = Map.of(
            "idempotency_key", AttributeValue.builder().s(idempotencyKey).build()
        );

        Map<String, AttributeValueUpdate> updates = new HashMap<>();
        updates.put("status", AttributeValueUpdate.builder()
            .value(AttributeValue.builder().s(status.name()).build())
            .action(AttributeAction.PUT)
            .build());
        updates.put("response_code", AttributeValueUpdate.builder()
            .value(AttributeValue.builder().n(String.valueOf(statusCode)).build())
            .action(AttributeAction.PUT)
            .build());
        updates.put("response_body", AttributeValueUpdate.builder()
            .value(AttributeValue.builder().s(responseBody != null ? responseBody : "").build())
            .action(AttributeAction.PUT)
            .build());

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .attributeUpdates(updates)
            .build();

        try {
            dynamoDbClient.updateItem(updateRequest);
            log.info("Updated idempotency record for key: {} to status: {}", idempotencyKey, status);
        } catch (Exception e) {
            log.error("Failed to update idempotency record for key {}: {}", idempotencyKey, e.getMessage(), e);
        }
    }

    public void createTableIfNotExists() {
        try {
            dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
            log.info("DynamoDB table '{}' already exists", tableName);
        } catch (ResourceNotFoundException e) {
            log.info("Creating DynamoDB table '{}'...", tableName);
            CreateTableRequest createTableRequest = CreateTableRequest.builder()
                .tableName(tableName)
                .keySchema(KeySchemaElement.builder()
                    .attributeName("idempotency_key")
                    .keyType(KeyType.HASH)
                    .build())
                .attributeDefinitions(AttributeDefinition.builder()
                    .attributeName("idempotency_key")
                    .attributeType(ScalarAttributeType.S)
                    .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();

            dynamoDbClient.createTable(createTableRequest);

            try {
                dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                    .tableName(tableName)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                        .enabled(true)
                        .attributeName("ttl")
                        .build())
                    .build());
            } catch (Exception ttlEx) {
                log.warn("Could not enable TTL on DynamoDB table: {}", ttlEx.getMessage());
            }
            log.info("DynamoDB table '{}' created successfully", tableName);
        }
    }

    String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
