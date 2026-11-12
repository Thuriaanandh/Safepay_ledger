package com.safepay.ledger;

import com.safepay.ledger.domain.AccountEntity;
import com.safepay.ledger.domain.TransactionStatus;
import com.safepay.ledger.exception.InsufficientFundsException;
import com.safepay.ledger.repository.AccountRepository;
import com.safepay.ledger.repository.LedgerPostingRepository;
import com.safepay.ledger.repository.LedgerTransactionRepository;
import com.safepay.ledger.repository.OutboxEventRepository;
import com.safepay.ledger.service.LedgerService;
import com.safepay.ledger.service.TransferCommand;
import com.safepay.ledger.service.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class LedgerServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("safepay_ledger_test")
        .withUsername("safepay")
        .withPassword("safepay_secret")
        .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerPostingRepository postingRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("Postgres ACID: Double-entry transfer persists atomically and preserves invariant sum == 0")
    void testEndToEndDoubleEntryTransfer() {
        // Seed accounts ACC-ALICE-USD ($5,000.00 = 500,000 cents) and ACC-BOB-USD ($2,000.00 = 200,000 cents) from V2 migration
        AccountEntity aliceBefore = accountRepository.findByAccountNumber("ACC-ALICE-USD").orElseThrow();
        AccountEntity bobBefore = accountRepository.findByAccountNumber("ACC-BOB-USD").orElseThrow();

        long transferAmount = 150_000L; // $1,500.00
        String transferId = "it-tx-001";

        TransferCommand command = new TransferCommand(
            transferId,
            aliceBefore.getAccountNumber(),
            bobBefore.getAccountNumber(),
            transferAmount,
            "USD",
            "Integration Test P2P Transfer"
        );

        TransferResult result = ledgerService.processTransfer(command);

        assertThat(result.status()).isEqualTo(TransactionStatus.POSTED);

        // Verify Balances in Database
        AccountEntity aliceAfter = accountRepository.findByAccountNumber("ACC-ALICE-USD").orElseThrow();
        AccountEntity bobAfter = accountRepository.findByAccountNumber("ACC-BOB-USD").orElseThrow();

        assertThat(aliceAfter.getBalanceCents()).isEqualTo(aliceBefore.getBalanceCents() - transferAmount);
        assertThat(bobAfter.getBalanceCents()).isEqualTo(bobBefore.getBalanceCents() + transferAmount);

        // Verify Invariant: Total Debits == Total Credits (Imbalance must be 0)
        long imbalance = ledgerService.verifyLedgerInvariant();
        assertThat(imbalance).isEqualTo(0L);

        // Verify Outbox Event Persisted in Database
        assertThat(outboxEventRepository.findByAggregateId(transferId)).isPresent();
    }

    @Test
    @DisplayName("Postgres ACID: Insufficient funds rolls back transaction completely leaving 0 orphaned rows")
    void testInsufficientFundsRollback() {
        // Charlie has $0.00 in seed data
        String transferId = "it-tx-fail-002";
        TransferCommand command = new TransferCommand(
            transferId,
            "ACC-CHARLIE-USD",
            "ACC-BOB-USD",
            50_00L,
            "USD",
            "Should fail and rollback"
        );

        assertThatThrownBy(() -> ledgerService.processTransfer(command))
            .isInstanceOf(InsufficientFundsException.class);

        // Transaction table must NOT have this transfer
        assertThat(transactionRepository.findByTransferId(transferId)).isEmpty();

        // Outbox must NOT have this transfer
        assertThat(outboxEventRepository.findByAggregateId(transferId)).isEmpty();

        // Invariant must still hold
        assertThat(ledgerService.verifyLedgerInvariant()).isEqualTo(0L);
    }
}
