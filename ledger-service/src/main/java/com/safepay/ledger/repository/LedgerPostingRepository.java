package com.safepay.ledger.repository;

import com.safepay.ledger.domain.LedgerPostingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerPostingRepository extends JpaRepository<LedgerPostingEntity, UUID> {
    List<LedgerPostingEntity> findByTransactionId(UUID transactionId);

    /**
     * Ledger Invariant Audit:
     * Calculates the sum of all DEBITs minus all CREDITs.
     * In a balanced double-entry ledger, this must always be EXACTLY 0.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amountCents ELSE -p.amountCents END), 0) FROM LedgerPostingEntity p")
    Long calculateNetPostingImbalance();

    @Query("SELECT COUNT(p) FROM LedgerPostingEntity p")
    long countAllPostings();
}
