package com.bofa.transactionservice.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class Transaction {

    private final String id;
    private final String sourceAccountId;
    private final String destinationAccountId;
    private final BigDecimal amount;
    private final String currency;
    private final String idempotencyKey;
    private final Instant createdAt;
    private TransactionStatus status;
    private String failureReason;

    public Transaction(String sourceAccountId, String destinationAccountId, BigDecimal amount,
                       String currency, String idempotencyKey) {
        this.id = UUID.randomUUID().toString();
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
        this.status = TransactionStatus.PENDING;
    }

    public String getId() {
        return id;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getDestinationAccountId() {
        return destinationAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void markCompleted() {
        this.status = TransactionStatus.COMPLETED;
    }

    public void markFailed(String reason) {
        this.status = TransactionStatus.FAILED;
        this.failureReason = reason;
    }

    public void markReversed() {
        this.status = TransactionStatus.REVERSED;
    }
}
