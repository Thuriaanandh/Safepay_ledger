package com.safepay.ledger.outbox;

import com.safepay.ledger.domain.OutboxStatus;
import com.safepay.ledger.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxMetrics(OutboxEventRepository outboxEventRepository, MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;

        // Metric for Acceptance Criterion 5: Outbox lag
        Gauge.builder("safepay.outbox.pending.events",
                () -> outboxEventRepository.countByStatus(OutboxStatus.PENDING))
            .description("Number of pending events waiting to be published by the Transactional Outbox relay (lag)")
            .register(meterRegistry);

        Gauge.builder("safepay.outbox.failed.events",
                () -> outboxEventRepository.countByStatus(OutboxStatus.FAILED))
            .description("Number of permanently failed events in the Transactional Outbox relay")
            .register(meterRegistry);
    }
}
