package com.safepay.reconciliation.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MeterRegistry meterRegistry;
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        reconciliationService = new ReconciliationService(jdbcTemplate, meterRegistry, false);
    }

    @Test
    @DisplayName("Audit should report balanced status (imbalance = 0) when Debits == Credits")
    void shouldReportBalancedWhenDebitsEqualCredits() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(invocation -> {
            return ReconciliationAuditResult.of(50000L, 50000L, 10, 100000L);
        });

        ReconciliationAuditResult result = reconciliationService.runAudit();

        assertThat(result.isBalanced()).isTrue();
        assertThat(result.imbalanceCents()).isEqualTo(0L);
        assertThat(result.totalDebitsCents()).isEqualTo(50000L);
        assertThat(result.totalCreditsCents()).isEqualTo(50000L);
        assertThat(reconciliationService.getImbalanceCents()).isEqualTo(0L);
        assertThat(reconciliationService.getReconciliationStatus()).isEqualTo(1L);

        // Verify Micrometer gauge
        double gaugeValue = meterRegistry.get("safepay.ledger.imbalance.cents").gauge().value();
        assertThat(gaugeValue).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Audit should report imbalance (status = 0) when Debits != Credits")
    void shouldReportImbalanceWhenDebitsNotEqualCredits() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(invocation -> {
            return ReconciliationAuditResult.of(50000L, 45000L, 10, 95000L);
        });

        ReconciliationAuditResult result = reconciliationService.runAudit();

        assertThat(result.isBalanced()).isFalse();
        assertThat(result.imbalanceCents()).isEqualTo(5000L);
        assertThat(reconciliationService.getImbalanceCents()).isEqualTo(5000L);
        assertThat(reconciliationService.getReconciliationStatus()).isEqualTo(0L);

        // Verify Micrometer gauge reflects the 5000 cents imbalance
        double gaugeValue = meterRegistry.get("safepay.ledger.imbalance.cents").gauge().value();
        assertThat(gaugeValue).isEqualTo(5000.0);
    }

    @Test
    @DisplayName("Audit should handle empty ledger with 0 debits and 0 credits as balanced")
    void shouldHandleEmptyLedger() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(invocation -> {
            return ReconciliationAuditResult.of(0L, 0L, 0, 0L);
        });

        ReconciliationAuditResult result = reconciliationService.runAudit();

        assertThat(result.isBalanced()).isTrue();
        assertThat(result.imbalanceCents()).isEqualTo(0L);
        assertThat(reconciliationService.getReconciliationStatus()).isEqualTo(1L);
    }
}
