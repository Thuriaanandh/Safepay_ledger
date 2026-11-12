package com.safepay.notification.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherServiceTest {

    @Mock
    private WebhookClient webhookClient;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private WebhookDispatcherService dispatcherService;

    @BeforeEach
    void setUp() {
        dispatcherService = new WebhookDispatcherService(
            webhookClient,
            kafkaTemplate,
            "transfer-events-dlq"
        );
    }

    @Test
    @DisplayName("Should successfully dispatch notification to webhook client")
    void testDispatchSuccess() {
        String transferId = "tx-dispatch-1";
        String payload = "{\"transferId\":\"tx-dispatch-1\"}";

        dispatcherService.dispatch(transferId, payload);

        verify(webhookClient).sendNotification(transferId, payload);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Should route poison message to Dead-Letter Topic when fallback is triggered")
    void testFallbackRoutesToDlq() {
        String transferId = "tx-poison-2";
        String payload = "{\"transferId\":\"tx-poison-2\"}";
        Throwable error = new RuntimeException("Connection refused from merchant server");

        dispatcherService.handleWebhookFallback(transferId, payload, error);

        verify(kafkaTemplate).send(eq("transfer-events-dlq"), eq(transferId), contains("Connection refused"));
    }
}
