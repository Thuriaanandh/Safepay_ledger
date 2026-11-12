package com.safepay.ledger.service;

import com.safepay.ledger.domain.*;
import com.safepay.ledger.exception.AccountNotFoundException;
import com.safepay.ledger.exception.CurrencyMismatchException;
import com.safepay.ledger.exception.InsufficientFundsException;
import com.safepay.ledger.exception.LedgerInvariantViolationException;
import com.safepay.ledger.repository.AccountRepository;
import com.safepay.ledger.repository.LedgerPostingRepository;
import com.safepay.ledger.repository.LedgerTransactionRepository;
import com.safepay.ledger.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceUnitTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerPostingRepository postingRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @InjectMocks
    private LedgerService ledgerService;

    private AccountEntity aliceAccount;
    private AccountEntity bobAccount;

    @BeforeEach
    void setUp() {
        aliceAccount = new AccountEntity(UUID.randomUUID(), "ACC-ALICE-USD", AccountType.LIABILITY, "USD", 500_00L); // $500.00
        bobAccount = new AccountEntity(UUID.randomUUID(), "ACC-BOB-USD", AccountType.LIABILITY, "USD", 200_00L);   // $200.00
    }

    @Test
    @DisplayName("Should successfully transfer funds between accounts and satisfy double-entry invariants")
    void testSuccessfulTransfer() {
        TransferCommand command = new TransferCommand(
            "tx-1001",
            "ACC-ALICE-USD",
            "ACC-BOB-USD",
            100_00L, // $100.00
            "USD",
            "P2P payment from Alice to Bob"
        );

        when(transactionRepository.findByTransferId("tx-1001")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumber("ACC-ALICE-USD")).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByAccountNumber("ACC-BOB-USD")).thenReturn(Optional.of(bobAccount));
        when(transactionRepository.save(any(LedgerTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TransferResult result = ledgerService.processTransfer(command);

        assertThat(result.transferId()).isEqualTo("tx-1001");
        assertThat(result.status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(result.amountCents()).isEqualTo(100_00L);

        // Verify Account Balances
        assertThat(aliceAccount.getBalanceCents()).isEqualTo(400_00L); // $500 - $100
        assertThat(bobAccount.getBalanceCents()).isEqualTo(300_00L);   // $200 + $100

        // Verify Transaction & Postings Saved
        ArgumentCaptor<LedgerTransactionEntity> txCaptor = ArgumentCaptor.forClass(LedgerTransactionEntity.class);
        verify(transactionRepository).save(txCaptor.capture());
        LedgerTransactionEntity savedTx = txCaptor.getValue();

        assertThat(savedTx.getPostings()).hasSize(2);
        long debitSum = savedTx.getPostings().stream()
            .filter(p -> p.getDirection() == PostingDirection.DEBIT)
            .mapToLong(LedgerPostingEntity::getAmountCents).sum();
        long creditSum = savedTx.getPostings().stream()
            .filter(p -> p.getDirection() == PostingDirection.CREDIT)
            .mapToLong(LedgerPostingEntity::getAmountCents).sum();

        assertThat(debitSum).isEqualTo(100_00L);
        assertThat(creditSum).isEqualTo(100_00L);
        assertThat(debitSum).isEqualTo(creditSum); // Invariant holds: debits == credits

        // Verify Transactional Outbox Event Created in same transaction
        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEventEntity outboxEvent = outboxCaptor.getValue();
        assertThat(outboxEvent.getAggregateId()).isEqualTo("tx-1001");
        assertThat(outboxEvent.getEventType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("Should reject transfer when source account has insufficient funds")
    void testInsufficientFunds() {
        TransferCommand command = new TransferCommand(
            "tx-1002",
            "ACC-ALICE-USD",
            "ACC-BOB-USD",
            1000_00L, // $1000.00 (Alice only has $500.00)
            "USD",
            "Attempt overdrawn transfer"
        );

        when(transactionRepository.findByTransferId("tx-1002")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumber("ACC-ALICE-USD")).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByAccountNumber("ACC-BOB-USD")).thenReturn(Optional.of(bobAccount));

        assertThatThrownBy(() -> ledgerService.processTransfer(command))
            .isInstanceOf(InsufficientFundsException.class)
            .hasMessageContaining("Insufficient funds");

        // Balances must remain unchanged
        assertThat(aliceAccount.getBalanceCents()).isEqualTo(500_00L);
        assertThat(bobAccount.getBalanceCents()).isEqualTo(200_00L);

        // No transaction or outbox event should be persisted
        verify(transactionRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return existing transaction on duplicate transferId (idempotent)")
    void testLedgerLevelIdempotency() {
        LedgerTransactionEntity existingTx = new LedgerTransactionEntity(
            UUID.randomUUID(),
            "tx-duplicate-1",
            "Already posted",
            TransactionStatus.POSTED
        );

        when(transactionRepository.findByTransferId("tx-duplicate-1")).thenReturn(Optional.of(existingTx));

        TransferCommand command = new TransferCommand(
            "tx-duplicate-1",
            "ACC-ALICE-USD",
            "ACC-BOB-USD",
            50_00L,
            "USD",
            "Duplicate attempt"
        );

        TransferResult result = ledgerService.processTransfer(command);

        assertThat(result.transferId()).isEqualTo("tx-duplicate-1");
        assertThat(result.status()).isEqualTo(TransactionStatus.POSTED);

        // Crucial: No state mutation should happen on idempotent hit
        verify(accountRepository, never()).findByAccountNumber(any());
        verify(transactionRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject transfer when currencies do not match")
    void testCurrencyMismatch() {
        AccountEntity euroAccount = new AccountEntity(UUID.randomUUID(), "ACC-EUR-1", AccountType.LIABILITY, "EUR", 500_00L);

        TransferCommand command = new TransferCommand(
            "tx-1003",
            "ACC-ALICE-USD",
            "ACC-EUR-1",
            50_00L,
            "USD",
            "Cross-currency without conversion"
        );

        when(transactionRepository.findByTransferId("tx-1003")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumber("ACC-ALICE-USD")).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByAccountNumber("ACC-EUR-1")).thenReturn(Optional.of(euroAccount));

        assertThatThrownBy(() -> ledgerService.processTransfer(command))
            .isInstanceOf(CurrencyMismatchException.class)
            .hasMessageContaining("does not match transfer currency");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject transfer if source account is missing")
    void testMissingAccount() {
        TransferCommand command = new TransferCommand(
            "tx-1004",
            "ACC-UNKNOWN",
            "ACC-BOB-USD",
            50_00L,
            "USD",
            "Unknown account"
        );

        when(transactionRepository.findByTransferId("tx-1004")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumber("ACC-UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.processTransfer(command))
            .isInstanceOf(AccountNotFoundException.class)
            .hasMessageContaining("Source account not found");
    }
}
