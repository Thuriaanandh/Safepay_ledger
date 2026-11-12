package com.safepay.ledger.service;

import com.safepay.ledger.domain.*;
import com.safepay.ledger.exception.AccountNotFoundException;
import com.safepay.ledger.exception.CurrencyMismatchException;
import com.safepay.ledger.exception.LedgerInvariantViolationException;
import com.safepay.ledger.repository.AccountRepository;
import com.safepay.ledger.repository.LedgerPostingRepository;
import com.safepay.ledger.repository.LedgerTransactionRepository;
import com.safepay.ledger.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerPostingRepository postingRepository;
    private final OutboxEventRepository outboxEventRepository;

    public LedgerService(
        AccountRepository accountRepository,
        LedgerTransactionRepository transactionRepository,
        LedgerPostingRepository postingRepository,
        OutboxEventRepository outboxEventRepository
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.postingRepository = postingRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Executes an atomic, double-entry transfer between two accounts with optimistic locking
     * and persists a Transactional Outbox event within the same ACID database transaction.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult processTransfer(TransferCommand command) {
        log.info("Processing ledger transfer: transferId={}, amountCents={}, currency={}",
            command.transferId(), command.amountCents(), command.currency());

        // 1. Ledger-level Idempotency Check: Return existing record if already processed
        Optional<LedgerTransactionEntity> existingTx = transactionRepository.findByTransferId(command.transferId());
        if (existingTx.isPresent()) {
            LedgerTransactionEntity tx = existingTx.get();
            log.info("Idempotent hit: transferId={} already processed with status={}", command.transferId(), tx.getStatus());
            return new TransferResult(
                tx.getTransferId(),
                tx.getStatus(),
                command.amountCents(),
                command.currency(),
                null,
                tx.getCreatedAt().toEpochMilli()
            );
        }

        // 2. Fetch Accounts
        AccountEntity sourceAccount = accountRepository.findByAccountNumber(command.sourceAccountNumber())
            .orElseThrow(() -> new AccountNotFoundException("Source account not found: " + command.sourceAccountNumber()));

        AccountEntity destAccount = accountRepository.findByAccountNumber(command.destinationAccountNumber())
            .orElseThrow(() -> new AccountNotFoundException("Destination account not found: " + command.destinationAccountNumber()));

        // 3. Validate Currencies
        if (!sourceAccount.getCurrency().equalsIgnoreCase(command.currency())) {
            throw new CurrencyMismatchException(
                "Source account currency (" + sourceAccount.getCurrency() + ") does not match transfer currency (" + command.currency() + ")"
            );
        }
        if (!destAccount.getCurrency().equalsIgnoreCase(command.currency())) {
            throw new CurrencyMismatchException(
                "Destination account currency (" + destAccount.getCurrency() + ") does not match transfer currency (" + command.currency() + ")"
            );
        }

        // 4. Execute Double-Entry Postings (Source Debit, Dest Credit)
        // Note: AccountEntity uses @Version for Optimistic Concurrency Control
        sourceAccount.debit(command.amountCents());
        destAccount.credit(command.amountCents());

        accountRepository.save(sourceAccount);
        accountRepository.save(destAccount);

        // 5. Create Parent Ledger Transaction
        LedgerTransactionEntity tx = new LedgerTransactionEntity(
            null,
            command.transferId(),
            command.description(),
            TransactionStatus.POSTED
        );

        // 6. Create Child Double-Entry Legs (Leg 1: Source DEBIT, Leg 2: Destination CREDIT)
        LedgerPostingEntity debitPosting = new LedgerPostingEntity(
            null,
            sourceAccount,
            PostingDirection.DEBIT,
            command.amountCents(),
            1
        );
        LedgerPostingEntity creditPosting = new LedgerPostingEntity(
            null,
            destAccount,
            PostingDirection.CREDIT,
            command.amountCents(),
            2
        );

        tx.addPosting(debitPosting);
        tx.addPosting(creditPosting);

        // 7. Verify Accounting Invariant (Sum of Debits == Sum of Credits)
        long totalDebit = tx.getPostings().stream()
            .filter(p -> p.getDirection() == PostingDirection.DEBIT)
            .mapToLong(LedgerPostingEntity::getAmountCents)
            .sum();

        long totalCredit = tx.getPostings().stream()
            .filter(p -> p.getDirection() == PostingDirection.CREDIT)
            .mapToLong(LedgerPostingEntity::getAmountCents)
            .sum();

        if (totalDebit != totalCredit) {
            throw new LedgerInvariantViolationException(
                "Double-entry invariant violated! Debits=" + totalDebit + " != Credits=" + totalCredit
            );
        }

        // Save transaction and cascaded postings
        LedgerTransactionEntity savedTx = transactionRepository.save(tx);

        // 8. Transactional Outbox: Write event in SAME ACID transaction
        // Schema payload contains necessary metadata for downstream workers
        String outboxPayload = String.format(
            "{\"transferId\":\"%s\",\"sourceAccount\":\"%s\",\"destinationAccount\":\"%s\",\"amountCents\":%d,\"currency\":\"%s\",\"status\":\"COMPLETED\",\"timestamp\":%d}",
            command.transferId(),
            command.sourceAccountNumber(),
            command.destinationAccountNumber(),
            command.amountCents(),
            command.currency(),
            savedTx.getCreatedAt().toEpochMilli()
        );

        OutboxEventEntity outboxEvent = new OutboxEventEntity(
            null,
            "TRANSFER",
            command.transferId(),
            "TRANSFER_COMPLETED",
            outboxPayload
        );
        outboxEventRepository.save(outboxEvent);

        log.info("Ledger transfer successfully committed: transferId={}, txId={}", command.transferId(), savedTx.getId());

        return new TransferResult(
            savedTx.getTransferId(),
            savedTx.getStatus(),
            command.amountCents(),
            command.currency(),
            null,
            savedTx.getCreatedAt().toEpochMilli()
        );
    }

    /**
     * Retrieves an existing transfer status.
     */
    @Transactional(readOnly = true)
    public Optional<TransferResult> getTransfer(String transferId) {
        return transactionRepository.findByTransferId(transferId)
            .map(tx -> new TransferResult(
                tx.getTransferId(),
                tx.getStatus(),
                0L, // Amount can be retrieved from postings if required
                "USD",
                null,
                tx.getCreatedAt().toEpochMilli()
            ));
    }

    /**
     * Reconciles total debits vs total credits across all historic postings.
     * Returns 0 if balanced, non-zero if an imbalance exists.
     */
    @Transactional(readOnly = true)
    public long verifyLedgerInvariant() {
        return postingRepository.calculateNetPostingImbalance();
    }
}
