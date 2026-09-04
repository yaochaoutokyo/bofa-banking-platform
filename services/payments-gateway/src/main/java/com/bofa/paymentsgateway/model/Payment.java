package com.bofa.paymentsgateway.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class Payment {

    private final String id;
    private final PaymentRequest request;
    private final Instant receivedAt;
    private PaymentRail rail;
    private PaymentStatus status;
    private int attempts;
    private BigDecimal fee;
    private String rejectionReason;
    private String railReference;

    public Payment(PaymentRequest request) {
        this.id = UUID.randomUUID().toString();
        this.request = request;
        this.receivedAt = Instant.now();
        this.status = PaymentStatus.ACCEPTED;
    }

    public String getId() {
        return id;
    }

    public PaymentRequest getRequest() {
        return request;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public PaymentRail getRail() {
        return rail;
    }

    public void setRail(PaymentRail rail) {
        this.rail = rail;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getRailReference() {
        return railReference;
    }

    public void setRailReference(String railReference) {
        this.railReference = railReference;
    }
}
