package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.Account;
import com.bofa.transactionservice.model.Transaction;
import com.bofa.transactionservice.model.TransactionException;
import com.bofa.transactionservice.model.TransactionStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regulation E error-resolution workflow. Customers have 60 days from the
 * statement to dispute; the bank must provisionally credit within 10 business
 * days and resolve within 45 (90 for new accounts / foreign transactions).
 */
@Service
public class DisputeService {

    static final Duration DISPUTE_WINDOW = Duration.ofDays(60);
    static final Duration PROVISIONAL_CREDIT_DEADLINE = Duration.ofDays(10);
    static final Duration RESOLUTION_DEADLINE = Duration.ofDays(45);
    static final Duration EXTENDED_RESOLUTION_DEADLINE = Duration.ofDays(90);

    private final TransactionService transactionService;
    private final AccountStore accountStore;
    private final Clock clock;
    private final Map<String, Dispute> disputes = new ConcurrentHashMap<>();

    public DisputeService(TransactionService transactionService, AccountStore accountStore) {
        this(transactionService, accountStore, Clock.systemUTC());
    }

    public DisputeService(TransactionService transactionService, AccountStore accountStore, Clock clock) {
        this.transactionService = transactionService;
        this.accountStore = accountStore;
        this.clock = clock;
    }

    public Dispute open(String transactionId, String customerAccountId, Reason reason, String narrative) {
        Transaction txn = transactionService.get(transactionId)
                .orElseThrow(() -> new TransactionException("TRANSACTION_NOT_FOUND", "Unknown transaction"));
        if (txn.getStatus() != TransactionStatus.COMPLETED) {
            throw new TransactionException("DISPUTE_NOT_ELIGIBLE", "Only completed transactions can be disputed");
        }
        if (!txn.getSourceAccountId().equals(customerAccountId)) {
            throw new TransactionException("DISPUTE_NOT_OWNER", "Only the debited account may dispute");
        }
        Instant now = clock.instant();
        if (Duration.between(txn.getCreatedAt(), now).compareTo(DISPUTE_WINDOW) > 0) {
            throw new TransactionException("DISPUTE_WINDOW_CLOSED", "Dispute window of 60 days has elapsed");
        }
        for (Dispute existing : disputes.values()) {
            if (existing.transactionId.equals(transactionId) && existing.status != Status.CLOSED) {
                throw new TransactionException("DISPUTE_EXISTS", "An open dispute already exists");
            }
        }
        if (narrative == null || narrative.trim().length() < 20) {
            throw new TransactionException("DISPUTE_NARRATIVE_TOO_SHORT", "Provide at least 20 characters");
        }

        boolean extended = !txn.getCurrency().equals("USD");
        Dispute dispute = new Dispute(UUID.randomUUID().toString(), transactionId, customerAccountId,
                txn.getAmount(), reason, narrative.trim(), now,
                now.plus(PROVISIONAL_CREDIT_DEADLINE),
                now.plus(extended ? EXTENDED_RESOLUTION_DEADLINE : RESOLUTION_DEADLINE));
        disputes.put(dispute.id, dispute);
        return dispute;
    }

    public Dispute provisionalCredit(String disputeId) {
        Dispute d = require(disputeId);
        if (d.status != Status.OPEN) {
            throw new TransactionException("DISPUTE_INVALID_STATE", "Provisional credit only from OPEN");
        }
        Account account = accountStore.require(d.customerAccountId);
        account.credit(d.amount);
        d.status = Status.PROVISIONALLY_CREDITED;
        d.provisionalCreditAt = clock.instant();
        return d;
    }

    public Dispute resolve(String disputeId, boolean inCustomerFavor, String analyst) {
        Dispute d = require(disputeId);
        if (d.status == Status.CLOSED) {
            throw new TransactionException("DISPUTE_INVALID_STATE", "Dispute already closed");
        }
        if (analyst == null || analyst.isBlank()) {
            throw new TransactionException("ANALYST_REQUIRED", "Resolution requires an analyst identity");
        }
        if (inCustomerFavor) {
            if (d.status == Status.OPEN) {
                accountStore.require(d.customerAccountId).credit(d.amount);
            }
            d.outcome = Outcome.CUSTOMER_FAVOR;
        } else {
            if (d.status == Status.PROVISIONALLY_CREDITED) {
                accountStore.require(d.customerAccountId).debit(d.amount);
            }
            d.outcome = Outcome.BANK_FAVOR;
        }
        d.status = Status.CLOSED;
        d.resolvedAt = clock.instant();
        d.resolvedBy = analyst;
        return d;
    }

    public List<Dispute> overdue() {
        Instant now = clock.instant();
        List<Dispute> result = new ArrayList<>();
        for (Dispute d : disputes.values()) {
            boolean creditOverdue = d.status == Status.OPEN && now.isAfter(d.provisionalCreditDueBy);
            boolean resolutionOverdue = d.status != Status.CLOSED && now.isAfter(d.resolutionDueBy);
            if (creditOverdue || resolutionOverdue) {
                result.add(d);
            }
        }
        return result;
    }

    private Dispute require(String id) {
        Dispute d = disputes.get(id);
        if (d == null) {
            throw new TransactionException("DISPUTE_NOT_FOUND", "Unknown dispute " + id);
        }
        return d;
    }

    public enum Reason { UNAUTHORIZED, DUPLICATE, INCORRECT_AMOUNT, NOT_RECEIVED, OTHER }

    public enum Status { OPEN, PROVISIONALLY_CREDITED, CLOSED }

    public enum Outcome { PENDING, CUSTOMER_FAVOR, BANK_FAVOR }

    public static class Dispute {
        private final String id;
        private final String transactionId;
        private final String customerAccountId;
        private final BigDecimal amount;
        private final Reason reason;
        private final String narrative;
        private final Instant openedAt;
        private final Instant provisionalCreditDueBy;
        private final Instant resolutionDueBy;
        private Status status = Status.OPEN;
        private Outcome outcome = Outcome.PENDING;
        private Instant provisionalCreditAt;
        private Instant resolvedAt;
        private String resolvedBy;

        Dispute(String id, String transactionId, String customerAccountId, BigDecimal amount, Reason reason,
                String narrative, Instant openedAt, Instant provisionalCreditDueBy, Instant resolutionDueBy) {
            this.id = id;
            this.transactionId = transactionId;
            this.customerAccountId = customerAccountId;
            this.amount = amount;
            this.reason = reason;
            this.narrative = narrative;
            this.openedAt = openedAt;
            this.provisionalCreditDueBy = provisionalCreditDueBy;
            this.resolutionDueBy = resolutionDueBy;
        }

        public String getId() {
            return id;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public String getCustomerAccountId() {
            return customerAccountId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public Reason getReason() {
            return reason;
        }

        public String getNarrative() {
            return narrative;
        }

        public Instant getOpenedAt() {
            return openedAt;
        }

        public Instant getProvisionalCreditDueBy() {
            return provisionalCreditDueBy;
        }

        public Instant getResolutionDueBy() {
            return resolutionDueBy;
        }

        public Status getStatus() {
            return status;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public Instant getProvisionalCreditAt() {
            return provisionalCreditAt;
        }

        public Instant getResolvedAt() {
            return resolvedAt;
        }

        public String getResolvedBy() {
            return resolvedBy;
        }
    }
}
