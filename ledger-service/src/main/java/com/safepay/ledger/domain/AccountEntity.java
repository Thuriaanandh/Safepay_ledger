package com.safepay.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    private UUID id;

    @Column(name = "account_number", unique = true, nullable = false, length = 64)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_cents", nullable = false)
    private Long balanceCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AccountStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {
        // Required by JPA
    }

    public AccountEntity(UUID id, String accountNumber, AccountType accountType, String currency, Long initialBalanceCents) {
        this.id = id != null ? id : UUID.randomUUID();
        this.accountNumber = accountNumber;
        this.accountType = accountType;
        this.currency = currency;
        this.balanceCents = initialBalanceCents != null ? initialBalanceCents : 0L;
        this.status = AccountStatus.ACTIVE;
        this.version = 0L;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Applies a debit to this account based on its accounting type.
     * In accounting:
     * - Debiting an ASSET increases it (+).
     * - Debiting a LIABILITY decreases it (-).
     */
    public void debit(long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive, was: " + amountCents);
        }
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Account " + accountNumber + " is not ACTIVE (status: " + status + ")");
        }

        if (this.accountType == AccountType.LIABILITY) {
            if (this.balanceCents < amountCents) {
                throw new com.safepay.ledger.exception.InsufficientFundsException(
                    "Insufficient funds in account " + accountNumber + ". Required: " + amountCents + ", Available: " + balanceCents
                );
            }
            this.balanceCents -= amountCents;
        } else if (this.accountType == AccountType.ASSET) {
            this.balanceCents += amountCents;
        } else {
            throw new UnsupportedOperationException("Debit not implemented for account type: " + accountType);
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Applies a credit to this account based on its accounting type.
     * In accounting:
     * - Crediting an ASSET decreases it (-).
     * - Crediting a LIABILITY increases it (+).
     */
    public void credit(long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive, was: " + amountCents);
        }
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Account " + accountNumber + " is not ACTIVE (status: " + status + ")");
        }

        if (this.accountType == AccountType.LIABILITY) {
            this.balanceCents += amountCents;
        } else if (this.accountType == AccountType.ASSET) {
            if (this.balanceCents < amountCents) {
                throw new com.safepay.ledger.exception.InsufficientFundsException(
                    "Insufficient funds in asset account " + accountNumber + ". Required: " + amountCents + ", Available: " + balanceCents
                );
            }
            this.balanceCents -= amountCents;
        } else {
            throw new UnsupportedOperationException("Credit not implemented for account type: " + accountType);
        }
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getBalanceCents() {
        return balanceCents;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
