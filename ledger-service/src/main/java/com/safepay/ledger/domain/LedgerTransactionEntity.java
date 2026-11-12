package com.safepay.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {

    @Id
    private UUID id;

    @Column(name = "transfer_id", unique = true, nullable = false, length = 128)
    private String transferId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerPostingEntity> postings = new ArrayList<>();

    protected LedgerTransactionEntity() {
        // Required by JPA
    }

    public LedgerTransactionEntity(UUID id, String transferId, String description, TransactionStatus status) {
        this.id = id != null ? id : UUID.randomUUID();
        this.transferId = transferId;
        this.description = description;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public void addPosting(LedgerPostingEntity posting) {
        postings.add(posting);
        posting.setTransaction(this);
    }

    public UUID getId() {
        return id;
    }

    public String getTransferId() {
        return transferId;
    }

    public String getDescription() {
        return description;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerPostingEntity> getPostings() {
        return postings;
    }
}
