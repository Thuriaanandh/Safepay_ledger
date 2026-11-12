package com.safepay.payment.concurrent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.payment.client.LedgerGrpcClient;
import com.safepay.payment.controller.TransferController;
import com.safepay.payment.dto.TransferRequestDto;
import com.safepay.payment.dto.TransferResponseDto;
import com.safepay.payment.idempotency.IdempotencyResult;
import com.safepay.payment.idempotency.IdempotencyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConcurrentTransferDeduplicationTest {

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private LedgerGrpcClient ledgerGrpcClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TransferController transferController;

    @Test
    @DisplayName("Acceptance Criteria 1: Sending the same transfer 50 times concurrently results in exactly ONE ledger call")
    void test50ConcurrentIdenticalTransfersDeduplicated() throws InterruptedException, ExecutionException {
        int threadCount = 50;
        String sharedIdempotencyKey = "ik-concurrent-stress-50";

        TransferRequestDto request = new TransferRequestDto(
            "ACC-ALICE-USD",
            "ACC-BOB-USD",
            100_00L,
            "USD",
            "Concurrent Deduplication Test"
        );

        // Atomic guard simulating DynamoDB's attribute_not_exists conditional write
        AtomicBoolean lockAcquired = new AtomicBoolean(false);
        AtomicInteger gRPCExecutionCount = new AtomicInteger(0);

        when(idempotencyService.acquire(eq(sharedIdempotencyKey), anyString())).thenAnswer(invocation -> {
            if (lockAcquired.compareAndSet(false, true)) {
                return IdempotencyResult.acquired();
            } else {
                return IdempotencyResult.inProgress();
            }
        });

        when(ledgerGrpcClient.processTransfer(anyString(), eq(sharedIdempotencyKey), any(TransferRequestDto.class)))
            .thenAnswer(invocation -> {
                gRPCExecutionCount.incrementAndGet();
                return new TransferResponseDto(
                    "tx-uuid-50",
                    sharedIdempotencyKey,
                    "COMPLETED",
                    100_00L,
                    "USD",
                    System.currentTimeMillis()
                );
            });

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<ResponseEntity<?>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                // Wait for all 50 threads to be ready, then release at once
                startLatch.await();
                return transferController.initiateTransfer(sharedIdempotencyKey, request);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release all 50 threads simultaneously

        List<HttpStatus> statusCodes = Collections.synchronizedList(new ArrayList<>());
        for (Future<ResponseEntity<?>> future : futures) {
            ResponseEntity<?> response = future.get();
            statusCodes.add(HttpStatus.valueOf(response.getStatusCode().value()));
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Assertions:
        // 1. Exactly ONE thread got 201 CREATED
        long createdCount = statusCodes.stream().filter(s -> s == HttpStatus.CREATED).count();
        assertThat(createdCount)
            .as("Exactly ONE thread must successfully acquire the idempotency lock and receive 201 Created")
            .isEqualTo(1);

        // 2. The remaining 49 threads were intercepted by the atomic conditional write and got 409 CONFLICT
        long conflictCount = statusCodes.stream().filter(s -> s == HttpStatus.CONFLICT).count();
        assertThat(conflictCount)
            .as("All other 49 concurrent requests must receive 409 Conflict")
            .isEqualTo(threadCount - 1);

        // 3. Ledger service gRPC stub was called EXACTLY ONCE
        assertThat(gRPCExecutionCount.get())
            .as("Ledger gRPC service must be invoked exactly ONCE across all 50 concurrent requests")
            .isEqualTo(1);

        verify(ledgerGrpcClient, times(1)).processTransfer(anyString(), eq(sharedIdempotencyKey), any(TransferRequestDto.class));
        verify(idempotencyService, times(1)).recordSuccess(eq(sharedIdempotencyKey), eq(201), anyString());
    }
}
