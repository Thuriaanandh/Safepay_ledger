package com.safepay.ledger.repository;

import com.safepay.ledger.domain.LedgerTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {
    Optional<LedgerTransactionEntity> findByTransferId(String transferId);
    boolean existsByTransferId(String transferId);
}
