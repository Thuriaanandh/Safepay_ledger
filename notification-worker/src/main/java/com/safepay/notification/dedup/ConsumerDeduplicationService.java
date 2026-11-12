package com.safepay.notification.dedup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotent Consumer Deduplication Store.
 * Guarantees that at-least-once message deliveries from Kafka do not trigger duplicate webhook notifications.
 */
@Service
public class ConsumerDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(ConsumerDeduplicationService.class);

    // In-memory thread-safe cache mapping transferId -> processed timestamp
    private final Map<String, Long> processedTransfers = new ConcurrentHashMap<>();

    public boolean isDuplicate(String transferId) {
        boolean exists = processedTransfers.containsKey(transferId);
        if (exists) {
            log.info("Duplicate Kafka message detected for transferId={}. Deduplication active.", transferId);
        }
        return exists;
    }

    public void markProcessed(String transferId) {
        processedTransfers.put(transferId, Instant.now().toEpochMilli());
        log.info("Marked transferId={} as processed in consumer deduplication store", transferId);
    }

    public int getProcessedCount() {
        return processedTransfers.size();
    }

    public void clear() {
        processedTransfers.clear();
    }
}
