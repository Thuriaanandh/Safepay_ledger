package com.safepay.notification.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.notification.dedup.ConsumerDeduplicationService;
import com.safepay.notification.webhook.WebhookDispatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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
     * Convenience method for unit tests or invocations without explicit Kafka headers.
     */
    public void onTransferEvent(String message) {
        onTransferEvent(message, null);
    }

    /**
     * Idempotent Kafka consumer with distributed trace context propagation.
     * Guarantees Acceptance Criterion 3: Duplicate Kafka messages never cause duplicate notifications.
     */
    @KafkaListener(
        topics = "${safepay.kafka.topics.transfer-events:transfer-events}",
        groupId = "${spring.kafka.consumer.group-id:safepay-notification-group}"
    )
    public void onTransferEvent(
        @Payload String message,
        @Header(name = "traceparent", required = false) byte[] traceparentBytes
    ) {
        String traceparent = null;
        if (traceparentBytes != null) {
            traceparent = new String(traceparentBytes, StandardCharsets.UTF_8);
        }

        try {
            JsonNode root = objectMapper.readTree(message);
            String transferId = root.path("transferId").asText();

            if (traceparent == null || traceparent.isBlank()) {
                traceparent = root.path("traceparent").asText(null);
            }

            if (traceparent != null && traceparent.startsWith("00-")) {
                String[] parts = traceparent.split("-");
                if (parts.length >= 3) {
                    MDC.put("trace_id", parts[1]);
                    MDC.put("span_id", parts[2]);
                }
            }

            log.info("Received Kafka transfer event: {}", message);

            if (transferId == null || transferId.isBlank()) {
                log.warn("Ignoring malformed Kafka message without transferId: {}", message);
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
        } catch (Exception e) {
            log.error("Failed to parse Kafka message JSON: {}", e.getMessage(), e);
        } finally {
            MDC.remove("trace_id");
            MDC.remove("span_id");
        }
    }
}
