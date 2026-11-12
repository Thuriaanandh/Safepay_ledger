package com.safepay.ledger.outbox;

import com.safepay.ledger.domain.OutboxEventEntity;
import com.safepay.ledger.domain.OutboxStatus;
import com.safepay.ledger.repository.OutboxEventRepository;
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

        // Use aggregateId (transferId) as Kafka partition key for strict per-entity ordering
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
            topic,
            event.getAggregateId(),
            event.getPayload()
        );

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
