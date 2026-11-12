package com.safepay.ledger.outbox;

import com.safepay.ledger.domain.OutboxEventEntity;
import com.safepay.ledger.domain.OutboxStatus;
import com.safepay.ledger.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(name = "safepay.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;

    public OutboxPublisher(
        OutboxEventRepository outboxEventRepository,
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${safepay.kafka.topics.transfer-events:transfer-events}") String topic,
        @Value("${safepay.outbox.batch-size:50}") int batchSize
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    /**
     * Periodic outbox poller relay.
     * Guaranteed At-Least-Once event publishing from PostgreSQL outbox table to Kafka.
     */
    @Scheduled(fixedDelayString = "${safepay.outbox.poll-interval-ms:500}", initialDelay = 1000)
    public void publishPendingEvents() {
        List<OutboxEventEntity> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
            OutboxStatus.PENDING,
            PageRequest.of(0, batchSize)
        );

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox events to publish to Kafka", pendingEvents.size());

        for (OutboxEventEntity event : pendingEvents) {
            publishSingleEvent(event);
        }
    }

    public CompletableFuture<SendResult<String, String>> publishSingleEvent(OutboxEventEntity event) {
        log.info("Relaying outbox event to Kafka: id={}, aggregateId={}, eventType={}",
            event.getId(), event.getAggregateId(), event.getEventType());

        // Construct ProducerRecord with partition key and distributed tracing context
        ProducerRecord<String, String> record = new ProducerRecord<>(
            topic,
            event.getAggregateId(),
            event.getPayload()
        );

        String traceparent = extractTraceparent(event.getPayload());
        if (traceparent != null && !traceparent.isBlank()) {
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        }

        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Successfully published outbox event to Kafka topic {}: offset={}",
                    topic, result.getRecordMetadata().offset());
                markEventPublished(event.getId());
            } else {
                log.error("Failed to publish outbox event id={} to Kafka: {}", event.getId(), ex.getMessage(), ex);
                markEventFailed(event.getId());
            }
        });

        return future;
    }

    private String extractTraceparent(String payload) {
        if (payload == null) return null;
        int idx = payload.indexOf("\"traceparent\":\"");
        if (idx != -1) {
            int start = idx + 15;
            int end = payload.indexOf("\"", start);
            if (end != -1 && end > start) {
                return payload.substring(start, end);
            }
        }
        return null;
    }

    @Transactional
    public void markEventPublished(java.util.UUID eventId) {
        outboxEventRepository.findById(eventId).ifPresent(e -> {
            e.markPublished();
            outboxEventRepository.save(e);
        });
    }

    @Transactional
    public void markEventFailed(java.util.UUID eventId) {
        outboxEventRepository.findById(eventId).ifPresent(e -> {
            e.markFailed();
            outboxEventRepository.save(e);
        });
    }
}
