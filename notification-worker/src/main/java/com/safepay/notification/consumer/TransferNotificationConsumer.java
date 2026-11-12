package com.safepay.notification.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.notification.dedup.ConsumerDeduplicationService;
import com.safepay.notification.webhook.WebhookDispatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransferNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferNotificationConsumer.class);

    private final ConsumerDeduplicationService deduplicationService;
    private final WebhookDispatcherService webhookDispatcherService;
    private final ObjectMapper objectMapper;

    public TransferNotificationConsumer(
        ConsumerDeduplicationService deduplicationService,
        WebhookDispatcherService webhookDispatcherService,
        ObjectMapper objectMapper
    ) {
        this.deduplicationService = deduplicationService;
        this.webhookDispatcherService = webhookDispatcherService;
        this.objectMapper = objectMapper;
    }

    /**
     * Idempotent Kafka consumer.
     * Guarantees Acceptance Criterion 3: Duplicate Kafka messages never cause duplicate notifications.
     */
    @KafkaListener(
        topics = "${safepay.kafka.topics.transfer-events:transfer-events}",
        groupId = "${spring.kafka.consumer.group-id:safepay-notification-group}"
    )
    public void onTransferEvent(String message) {
        log.info("Received Kafka transfer event: {}", message);

        String transferId;
        try {
            JsonNode root = objectMapper.readTree(message);
            transferId = root.path("transferId").asText();
            if (transferId == null || transferId.isBlank()) {
                log.warn("Ignoring malformed Kafka message without transferId: {}", message);
                return;
            }
        } catch (Exception e) {
            log.error("Failed to parse Kafka message JSON: {}", e.getMessage(), e);
            return;
        }

        // 1. Idempotent Consumer Deduplication Check
        if (deduplicationService.isDuplicate(transferId)) {
            log.info("DEDUPLICATED: Kafka message with transferId={} has already been notified. Dropping duplicate.", transferId);
            return;
        }

        // 2. Dispatch notification with Resilience4j Retry + Circuit Breaker
        try {
            webhookDispatcherService.dispatch(transferId, message);
            // 3. Mark processed only after successful dispatch
            deduplicationService.markProcessed(transferId);
        } catch (Exception e) {
            log.error("Failed to dispatch notification for transferId={}: {}", transferId, e.getMessage(), e);
        }
    }
}
