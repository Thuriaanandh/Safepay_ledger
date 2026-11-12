package com.safepay.ledger.outbox;

import com.safepay.ledger.domain.OutboxEventEntity;
import com.safepay.ledger.domain.OutboxStatus;
import com.safepay.ledger.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
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

import java.nio.charset.StandardCharsets;
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

        when(kafkaTemplate.send(any(ProducerRecord.class)))
            .thenReturn(future);

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, String> record) ->
            record.topic().equals("transfer-events") &&
            record.key().equals("tx-outbox-101") &&
            record.value().equals("{\"transferId\":\"tx-outbox-101\"}")
        ));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Should propagate traceparent header when present in outbox payload")
    void testPublishPropagatesTraceparentHeader() {
        UUID eventId = UUID.randomUUID();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String payload = String.format("{\"transferId\":\"tx-trace-1\",\"traceparent\":\"%s\"}", traceparent);
        OutboxEventEntity event = new OutboxEventEntity(
            eventId,
            "TRANSFER",
            "tx-trace-1",
            "TRANSFER_COMPLETED",
            payload
        );

        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), any(Pageable.class)))
            .thenReturn(List.of(event));
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        RecordMetadata metadata = new RecordMetadata(new TopicPartition("transfer-events", 0), 0, 0, 0, 0, 0);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(new SendResult<>(null, metadata));

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, String> record) -> {
            boolean hasHeader = record.headers().lastHeader("traceparent") != null &&
                new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8).equals(traceparent);
            return record.key().equals("tx-trace-1") && hasHeader;
        }));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
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

        when(kafkaTemplate.send(any(ProducerRecord.class)))
            .thenReturn(failedFuture);

        outboxPublisher.publishPendingEvents();

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, String> record) ->
            record.topic().equals("transfer-events") &&
            record.key().equals("tx-outbox-fail")
        ));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(1);
        verify(outboxEventRepository).save(event);
    }
}
