package com.safepay.ledger.grpc;

import com.safepay.ledger.exception.AccountNotFoundException;
import com.safepay.ledger.exception.CurrencyMismatchException;
import com.safepay.ledger.exception.InsufficientFundsException;
import com.safepay.ledger.service.LedgerService;
import com.safepay.ledger.service.TransferCommand;
import com.safepay.ledger.service.TransferResult;
import com.safepay.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

@GrpcService
public class TransferGrpcService extends TransferServiceGrpc.TransferServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(TransferGrpcService.class);

    private final LedgerService ledgerService;

    public TransferGrpcService(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    public void processTransfer(TransferRequest request, StreamObserver<TransferResponse> responseObserver) {
        log.info("Received gRPC ProcessTransfer request: transferId={}, amount={}",
            request.getTransferId(), request.getAmountCents());

        try {
            TransferCommand command = new TransferCommand(
                request.getTransferId(),
                request.getSourceAccountId(),
                request.getDestinationAccountId(),
                request.getAmountCents(),
                request.getCurrency(),
                request.getDescription()
            );

            TransferResult result = ledgerService.processTransfer(command);

            TransferResponse response = TransferResponse.newBuilder()
                .setTransferId(result.transferId())
                .setStatus(TransferStatus.TRANSFER_STATUS_COMPLETED)
                .setAmountCents(result.amountCents())
                .setCurrency(result.currency())
                .setCreatedAt(result.timestamp())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid transfer argument: {}", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (AccountNotFoundException e) {
            log.warn("Account not found: {}", e.getMessage());
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (CurrencyMismatchException e) {
            log.warn("Currency mismatch: {}", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (InsufficientFundsException e) {
            log.warn("Insufficient funds for transfer: {}", e.getMessage());
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict during transfer: {}", e.getMessage());
            responseObserver.onError(Status.ABORTED.withDescription("Concurrent update conflict. Please retry.").asRuntimeException());
        } catch (Exception e) {
            log.error("Internal error processing transfer: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal ledger failure: " + e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getTransfer(GetTransferRequest request, StreamObserver<TransferResponse> responseObserver) {
        ledgerService.getTransfer(request.getTransferId()).ifPresentOrElse(
            result -> {
                TransferResponse response = TransferResponse.newBuilder()
                    .setTransferId(result.transferId())
                    .setStatus(TransferStatus.TRANSFER_STATUS_COMPLETED)
                    .setAmountCents(result.amountCents())
                    .setCurrency(result.currency())
                    .setCreatedAt(result.timestamp())
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            },
            () -> responseObserver.onError(Status.NOT_FOUND.withDescription("Transfer not found: " + request.getTransferId()).asRuntimeException())
        );
    }
}
