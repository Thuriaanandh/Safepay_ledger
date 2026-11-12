package com.safepay.notification.webhook;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class WebhookDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcherService.class);

    private final WebhookClient webhookClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String dltTopic;

    public WebhookDispatcherService(
        WebhookClient webhookClient,
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${safepay.kafka.topics.dlq:transfer-events-dlq}") String dltTopic
    ) {
        this.webhookClient = webhookClient;
        this.kafkaTemplate = kafkaTemplate;
        this.dltTopic = dltTopic;
    }

    /**
     * Dispatches notification with Resilience4j Retry (exponential backoff + jitter)
     * and Circuit Breaker protection.
     */
    @Retry(name = "webhookService")
    @CircuitBreaker(name = "webhookService", fallbackMethod = "handleWebhookFallback")
    public void dispatch(String transferId, String payload) {
        log.info("Attempting webhook dispatch for transferId={}", transferId);
        webhookClient.sendNotification(transferId, payload);
        log.info("Webhook dispatch confirmed for transferId={}", transferId);
    }

    /**
     * Fallback invoked when retries are exhausted or when the circuit breaker is OPEN.
     * Routes poison messages to the Dead-Letter Topic (DLT) for manual inspection/runbook triage.
     */
    public void handleWebhookFallback(String transferId, String payload, Throwable t) {
        log.error("CRITICAL: Webhook delivery failed after retries for transferId={}. Reason: {}. Routing to DLQ topic '{}'",
            transferId, t.getMessage(), dltTopic);

        String dlqPayload = String.format(
            "{\"transferId\":\"%s\",\"error\":\"%s\",\"failedAt\":%d,\"originalPayload\":%s}",
            transferId,
            t.getMessage().replace("\"", "'"),
            Instant.now().toEpochMilli(),
            payload
        );

        kafkaTemplate.send(dltTopic, transferId, dlqPayload);
        log.info("Poison message routed to Dead-Letter Topic '{}' for transferId={}", dltTopic, transferId);
    }
}
