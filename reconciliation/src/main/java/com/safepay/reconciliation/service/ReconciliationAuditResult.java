package com.safepay.reconciliation.service;

import java.time.Instant;

/**
 * Audit result capturing the fundamental double-entry ledger invariant:
 * Total Debits == Total Credits (sum of all postings across the entire ledger = 0).
 */
public record ReconciliationAuditResult(
    long totalDebitsCents,
    long totalCreditsCents,
    long imbalanceCents,
    long totalPostingsCount,
    long totalAccountBalanceCents,
    boolean isBalanced,
    Instant timestamp
) {
    public static ReconciliationAuditResult of(
        long debits,
        long credits,
        long postingsCount,
        long accountBalance
    ) {
        long imbalance = Math.abs(debits - credits);
        boolean balanced = (imbalance == 0);
        return new ReconciliationAuditResult(
            debits,
            credits,
            imbalance,
            postingsCount,
            accountBalance,
            balanced,
            Instant.now()
        );
    }
}
