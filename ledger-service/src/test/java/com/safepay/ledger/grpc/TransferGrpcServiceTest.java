package com.safepay.ledger.grpc;

import com.safepay.ledger.domain.TransactionStatus;
import com.safepay.ledger.exception.AccountNotFoundException;
import com.safepay.ledger.exception.InsufficientFundsException;
import com.safepay.ledger.service.LedgerService;
import com.safepay.ledger.service.TransferCommand;
import com.safepay.ledger.service.TransferResult;
import com.safepay.proto.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferGrpcServiceTest {

    @Mock
    private LedgerService ledgerService;

    @Mock
    private StreamObserver<TransferResponse> responseObserver;

    @InjectMocks
    private TransferGrpcService transferGrpcService;

    private TransferRequest request;

    @BeforeEach
    void setUp() {
        request = TransferRequest.newBuilder()
            .setTransferId("tx-grpc-101")
            .setSourceAccountId("ACC-ALICE-USD")
            .setDestinationAccountId("ACC-BOB-USD")
            .setAmountCents(250_00L)
            .setCurrency("USD")
            .setDescription("gRPC transfer test")
            .build();
    }

    @Test
    @DisplayName("gRPC: Should process transfer and complete stream observer")
    void testProcessTransferSuccess() {
        when(ledgerService.processTransfer(any(TransferCommand.class))).thenReturn(
            new TransferResult("tx-grpc-101", TransactionStatus.POSTED, 250_00L, "USD", null, 1700000000000L)
        );

        transferGrpcService.processTransfer(request, responseObserver);

        ArgumentCaptor<TransferResponse> captor = ArgumentCaptor.forClass(TransferResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        TransferResponse response = captor.getValue();
        assertThat(response.getTransferId()).isEqualTo("tx-grpc-101");
        assertThat(response.getStatus()).isEqualTo(TransferStatus.TRANSFER_STATUS_COMPLETED);
        assertThat(response.getAmountCents()).isEqualTo(250_00L);
    }

    @Test
    @DisplayName("gRPC: Should map InsufficientFundsException to FAILED_PRECONDITION")
    void testProcessTransferInsufficientFunds() {
        when(ledgerService.processTransfer(any(TransferCommand.class)))
            .thenThrow(new InsufficientFundsException("Insufficient balance"));

        transferGrpcService.processTransfer(request, responseObserver);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        verify(responseObserver, never()).onNext(any());

        assertThat(errorCaptor.getValue()).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException sre = (StatusRuntimeException) errorCaptor.getValue();
        assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(sre.getMessage()).contains("Insufficient balance");
    }

    @Test
    @DisplayName("gRPC: Should map AccountNotFoundException to NOT_FOUND")
    void testProcessTransferAccountNotFound() {
        when(ledgerService.processTransfer(any(TransferCommand.class)))
            .thenThrow(new AccountNotFoundException("Account does not exist"));

        transferGrpcService.processTransfer(request, responseObserver);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());

        StatusRuntimeException sre = (StatusRuntimeException) errorCaptor.getValue();
        assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    @DisplayName("gRPC: Should map OptimisticLockingFailureException to ABORTED")
    void testProcessTransferOptimisticLockConflict() {
        when(ledgerService.processTransfer(any(TransferCommand.class)))
            .thenThrow(new OptimisticLockingFailureException("Concurrent conflict"));

        transferGrpcService.processTransfer(request, responseObserver);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());

        StatusRuntimeException sre = (StatusRuntimeException) errorCaptor.getValue();
        assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
    }
}
