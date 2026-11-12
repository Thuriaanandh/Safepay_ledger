package com.safepay.payment.client;

import com.safepay.payment.dto.TransferRequestDto;
import com.safepay.payment.dto.TransferResponseDto;
import com.safepay.proto.TransferRequest;
import com.safepay.proto.TransferResponse;
import com.safepay.proto.TransferServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class LedgerGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(LedgerGrpcClient.class);

    @GrpcClient("ledger-service")
    private TransferServiceGrpc.TransferServiceBlockingStub transferStub;

    public LedgerGrpcClient() {
    }

    // For testing injection
    public LedgerGrpcClient(TransferServiceGrpc.TransferServiceBlockingStub transferStub) {
        this.transferStub = transferStub;
    }

    /**
     * Dispatches transfer to ledger-service via gRPC with an explicit 3-second deadline.
     */
    public TransferResponseDto processTransfer(String transferId, String idempotencyKey, TransferRequestDto request) {
        log.info("Dispatching gRPC transfer to ledger-service: transferId={}, amount={}", transferId, request.amountCents());

        TransferRequest protoRequest = TransferRequest.newBuilder()
            .setTransferId(transferId)
            .setIdempotencyKey(idempotencyKey)
            .setSourceAccountId(request.sourceAccountId())
            .setDestinationAccountId(request.destinationAccountId())
            .setAmountCents(request.amountCents())
            .setCurrency(request.currency())
            .setDescription(request.description() != null ? request.description() : "")
            .build();

        try {
            // Distributed systems pattern: Explicit 3-second RPC deadline
            TransferResponse protoResponse = transferStub
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .processTransfer(protoRequest);

            log.info("Received gRPC response for transferId={}: status={}", transferId, protoResponse.getStatus());

            return new TransferResponseDto(
                protoResponse.getTransferId(),
                idempotencyKey,
                protoResponse.getStatus().name().replace("TRANSFER_STATUS_", ""),
                protoResponse.getAmountCents(),
                protoResponse.getCurrency(),
                protoResponse.getCreatedAt()
            );
        } catch (StatusRuntimeException e) {
            log.warn("gRPC call failed with status: {}, description: {}", e.getStatus().getCode(), e.getStatus().getDescription());
            throw e;
        }
    }
}
