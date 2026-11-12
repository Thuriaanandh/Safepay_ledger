package com.safepay.ledger.outbox;

import com.safepay.ledger.domain.OutboxEventEntity;
import com.safepay.ledger.domain.OutboxStatus;
import com.safepay.ledger.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        outboxPublisher = new OutboxPublisher(
            outboxEventRepository,
            kafkaTemplate,
            "transfer-events",
            50
        );
    }

    @Test
    @DisplayName("Should publish pending outbox events to Kafka with aggregateId as key")
    void testPublishPendingEventsSuccess() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = new OutboxEventEntity(
            eventId,
            "TRANSFER",
            "tx-outbox-101",
            "TRANSFER_COMPLETED",
            "{\"transferId\":\"tx-outbox-101\"}"
        );

        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
            .thenReturn(List.of(event));
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        RecordMetadata metadata = new RecordMetadata(new TopicPartition("transfer-events", 0), 0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(null, metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq("transfer-events"), eq("tx-outbox-101"), anyString()))
            .thenReturn(future);

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq("transfer-events"), eq("tx-outbox-101"), eq("{\"transferId\":\"tx-outbox-101\"}"));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Should increment retry and mark FAILED when Kafka publish fails")
    void testPublishPendingEventsFailure() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = new OutboxEventEntity(
            eventId,
            "TRANSFER",
            "tx-outbox-fail",
            "TRANSFER_COMPLETED",
            "{\"transferId\":\"tx-outbox-fail\"}"
        );

        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
            .thenReturn(List.of(event));
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker connection timeout"));

        when(kafkaTemplate.send(eq("transfer-events"), eq("tx-outbox-fail"), anyString()))
            .thenReturn(failedFuture);

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq("transfer-events"), eq("tx-outbox-fail"), anyString());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(1);
        verify(outboxEventRepository).save(event);
    }
}
