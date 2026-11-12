package com.safepay.ledger.repository;

import com.safepay.ledger.domain.OutboxEventEntity;
import com.safepay.ledger.domain.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);
    Optional<OutboxEventEntity> findByAggregateId(String aggregateId);
}
