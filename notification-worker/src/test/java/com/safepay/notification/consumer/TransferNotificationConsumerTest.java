package com.safepay.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.notification.dedup.ConsumerDeduplicationService;
import com.safepay.notification.webhook.WebhookDispatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferNotificationConsumerTest {

    @Mock
    private ConsumerDeduplicationService deduplicationService;

    @Mock
    private WebhookDispatcherService webhookDispatcherService;

    private TransferNotificationConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new TransferNotificationConsumer(
            deduplicationService,
            webhookDispatcherService,
            objectMapper
        );
    }

    @Test
    @DisplayName("Should successfully process unique transfer event and mark processed")
    void testProcessUniqueEvent() {
        String transferId = "tx-unique-991";
        String message = String.format(
            "{\"transferId\":\"%s\",\"sourceAccount\":\"ACC-ALICE-USD\",\"destinationAccount\":\"ACC-BOB-USD\",\"amountCents\":10000}",
            transferId
        );

        when(deduplicationService.isDuplicate(transferId)).thenReturn(false);

        consumer.onTransferEvent(message);

        verify(webhookDispatcherService).dispatch(eq(transferId), eq(message));
        verify(deduplicationService).markProcessed(transferId);
    }

    @Test
    @DisplayName("Acceptance Criterion 3: Duplicate Kafka messages never cause duplicate notifications")
    void testDeduplicateKafkaMessage() {
        String transferId = "tx-duplicate-992";
        String message = String.format(
            "{\"transferId\":\"%s\",\"sourceAccount\":\"ACC-ALICE-USD\",\"destinationAccount\":\"ACC-BOB-USD\",\"amountCents\":10000}",
            transferId
        );

        // First delivery: Not duplicate
        when(deduplicationService.isDuplicate(transferId)).thenReturn(false);
        consumer.onTransferEvent(message);

        verify(webhookDispatcherService, times(1)).dispatch(eq(transferId), eq(message));
        verify(deduplicationService, times(1)).markProcessed(transferId);

        // Second delivery (e.g. Kafka replay or consumer restart): Duplicate detected!
        when(deduplicationService.isDuplicate(transferId)).thenReturn(true);
        consumer.onTransferEvent(message);

        // Crucial: Dispatch must still have only been called ONCE!
        verify(webhookDispatcherService, times(1)).dispatch(eq(transferId), eq(message));
        verify(deduplicationService, times(1)).markProcessed(transferId);
    }
}
