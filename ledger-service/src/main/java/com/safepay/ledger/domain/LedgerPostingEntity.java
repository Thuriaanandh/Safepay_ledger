package com.safepay.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_postings")
public class LedgerPostingEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransactionEntity transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountEntity account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private PostingDirection direction;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "sequence_num", nullable = false)
    private Integer sequenceNum;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerPostingEntity() {
        // Required by JPA
    }

    public LedgerPostingEntity(UUID id, AccountEntity account, PostingDirection direction, Long amountCents, Integer sequenceNum) {
        this.id = id != null ? id : UUID.randomUUID();
        this.account = account;
        this.direction = direction;
        this.amountCents = amountCents;
        this.sequenceNum = sequenceNum != null ? sequenceNum : 0;
        this.createdAt = Instant.now();
    }

    public void setTransaction(LedgerTransactionEntity transaction) {
        this.transaction = transaction;
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransactionEntity getTransaction() {
        return transaction;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public Integer getSequenceNum() {
        return sequenceNum;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
