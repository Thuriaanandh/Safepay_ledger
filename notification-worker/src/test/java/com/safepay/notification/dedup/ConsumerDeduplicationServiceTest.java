package com.safepay.notification.dedup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerDeduplicationServiceTest {

    private ConsumerDeduplicationService deduplicationService;

    @BeforeEach
    void setUp() {
        deduplicationService = new ConsumerDeduplicationService();
    }

    @Test
    @DisplayName("Should detect duplicate transfers after marking processed")
    void testDeduplicationTracking() {
        String transferId = "tx-test-dup-1";

        assertThat(deduplicationService.isDuplicate(transferId)).isFalse();

        deduplicationService.markProcessed(transferId);

        assertThat(deduplicationService.isDuplicate(transferId)).isTrue();
        assertThat(deduplicationService.getProcessedCount()).isEqualTo(1);
    }
}
