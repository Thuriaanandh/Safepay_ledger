package com.safepay.reconciliation.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
@EnableScheduling
public class ReconciliationService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final boolean cronMode;

    private final AtomicLong imbalanceGauge = new AtomicLong(0);
    private final AtomicLong totalDebitsGauge = new AtomicLong(0);
    private final AtomicLong totalCreditsGauge = new AtomicLong(0);
    private final AtomicLong postingsCountGauge = new AtomicLong(0);
    private final AtomicLong reconciliationStatusGauge = new AtomicLong(1); // 1 = Balanced, 0 = Imbalance
    private final Counter auditRunsCounter;

    public ReconciliationService(
        JdbcTemplate jdbcTemplate,
        MeterRegistry meterRegistry,
        @Value("${safepay.reconciliation.cron-mode:false}") boolean cronMode
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.cronMode = cronMode;

        // Register Micrometer Gauges
        meterRegistry.gauge("safepay.ledger.imbalance.cents", imbalanceGauge);
        meterRegistry.gauge("safepay.ledger.total.debits.cents", totalDebitsGauge);
        meterRegistry.gauge("safepay.ledger.total.credits.cents", totalCreditsGauge);
        meterRegistry.gauge("safepay.ledger.postings.count", postingsCountGauge);
        meterRegistry.gauge("safepay.ledger.reconciliation.status", reconciliationStatusGauge);

        this.auditRunsCounter = Counter.builder("safepay.ledger.reconciliation.runs.total")
            .description("Total number of ledger reconciliation audit runs completed")
            .register(meterRegistry);
    }

    /**
     * Executes the ledger accounting invariant verification query.
     * Computes sum(Debits) and sum(Credits) across all posted ledger legs.
     */
    public ReconciliationAuditResult runAudit() {
        auditRunsCounter.increment();

        String postingsQuery = """
            SELECT 
                COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_cents ELSE 0 END), 0) AS total_debits,
                COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_cents ELSE 0 END), 0) AS total_credits,
                COUNT(*) AS total_postings
            FROM ledger_postings
            """;

        ReconciliationAuditResult result = jdbcTemplate.query(postingsQuery, rs -> {
            if (rs.next()) {
                long debits = rs.getLong("total_debits");
                long credits = rs.getLong("total_credits");
                long postings = rs.getLong("total_postings");

                Long accountBalanceSum = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(balance_cents), 0) FROM accounts",
                    Long.class
                );

                return ReconciliationAuditResult.of(
                    debits,
                    credits,
                    postings,
                    accountBalanceSum != null ? accountBalanceSum : 0L
                );
            }
            return ReconciliationAuditResult.of(0, 0, 0, 0);
        });

        // Update Prometheus gauges
        imbalanceGauge.set(result.imbalanceCents());
        totalDebitsGauge.set(result.totalDebitsCents());
        totalCreditsGauge.set(result.totalCreditsCents());
        postingsCountGauge.set(result.totalPostingsCount());
        reconciliationStatusGauge.set(result.isBalanced() ? 1 : 0);

        if (result.isBalanced()) {
            log.info("Ledger Reconciliation PASSED: debits_cents={}, credits_cents={}, imbalance_cents=0, postings_count={}",
                result.totalDebitsCents(), result.totalCreditsCents(), result.totalPostingsCount());
        } else {
            log.error("CRITICAL: Ledger Invariant VIOLATION detected! Debits={}, Credits={}, Imbalance={}",
                result.totalDebitsCents(), result.totalCreditsCents(), result.imbalanceCents());
        }

        return result;
    }

    @Scheduled(fixedDelayString = "${safepay.reconciliation.schedule-ms:30000}", initialDelay = 2000)
    public void scheduledAudit() {
        if (!cronMode) {
            runAudit();
        }
    }

    @Override
    public void run(String... args) {
        if (cronMode) {
            log.info("Running in CronJob one-shot mode...");
            ReconciliationAuditResult result = runAudit();
            if (!result.isBalanced()) {
                log.error("Exiting with non-zero code due to ledger imbalance!");
                System.exit(1);
            } else {
                log.info("Reconciliation CronJob completed successfully. Exiting.");
                System.exit(0);
            }
        }
    }

    public long getImbalanceCents() {
        return imbalanceGauge.get();
    }

    public long getTotalDebitsCents() {
        return totalDebitsGauge.get();
    }

    public long getTotalCreditsCents() {
        return totalCreditsGauge.get();
    }

    public long getReconciliationStatus() {
        return reconciliationStatusGauge.get();
    }
}
