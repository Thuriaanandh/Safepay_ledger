package com.safepay.notification.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MockWebhookClient implements WebhookClient {

    private static final Logger log = LoggerFactory.getLogger(MockWebhookClient.class);

    private final RestClient restClient;
    private final String webhookUrl;
    private final boolean mockMode;

    public MockWebhookClient(
        @Value("${safepay.webhook.url:http://localhost:8089/webhook}") String webhookUrl,
        @Value("${safepay.webhook.mock-mode:true}") boolean mockMode
    ) {
        this.webhookUrl = webhookUrl;
        this.mockMode = mockMode;
        this.restClient = RestClient.builder().baseUrl(webhookUrl).build();
    }

    @Override
    public void sendNotification(String transferId, String payload) {
        log.info("Sending webhook notification: transferId={}, endpoint={}", transferId, webhookUrl);

        if (mockMode) {
            // Simulated merchant webhook receiver
            log.info("MockWebhookClient: Successfully delivered notification for transferId={}", transferId);
            return;
        }

        try {
            restClient.post()
                .uri("")
                .header("Content-Type", "application/json")
                .body(payload)
                .retrieve()
                .toBodilessEntity();
            log.info("Live webhook successfully received by merchant: transferId={}", transferId);
        } catch (Exception e) {
            log.warn("Webhook dispatch failed for transferId={}: {}", transferId, e.getMessage());
            throw new RuntimeException("Webhook endpoint unavailable: " + e.getMessage(), e);
        }
    }
}
