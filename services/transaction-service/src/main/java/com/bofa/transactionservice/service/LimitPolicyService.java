package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.TransactionException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiered transfer limits by customer segment with temporary limit overrides
 * that require dual approval above a threshold.
 */
@Service
public class LimitPolicyService {

    static final BigDecimal DUAL_APPROVAL_THRESHOLD = new BigDecimal("50000.00");

    private final Clock clock;
    private final Map<Segment, Limits> defaults = new EnumMap<>(Segment.class);
    private final Map<String, Segment> segmentByAccount = new ConcurrentHashMap<>();
    private final Map<String, Override> overrides = new ConcurrentHashMap<>();
    private final Map<String, List<String>> pendingApprovals = new ConcurrentHashMap<>();

    public LimitPolicyService() {
        this(Clock.systemUTC());
    }

    public LimitPolicyService(Clock clock) {
        this.clock = clock;
        defaults.put(Segment.RETAIL, new Limits(new BigDecimal("5000.00"), new BigDecimal("10000.00"), 20));
        defaults.put(Segment.PREFERRED, new Limits(new BigDecimal("25000.00"), new BigDecimal("50000.00"), 50));
        defaults.put(Segment.PRIVATE_BANK, new Limits(new BigDecimal("250000.00"), new BigDecimal("1000000.00"), 200));
        defaults.put(Segment.SMALL_BUSINESS, new Limits(new BigDecimal("100000.00"), new BigDecimal("250000.00"), 500));
        segmentByAccount.put("ACC-1001", Segment.PREFERRED);
        segmentByAccount.put("ACC-1002", Segment.RETAIL);
        segmentByAccount.put("ACC-2001", Segment.PRIVATE_BANK);
        segmentByAccount.put("ACC-3001", Segment.RETAIL);
    }

    public Limits effectiveLimits(String accountId) {
        Segment segment = segmentByAccount.getOrDefault(accountId, Segment.RETAIL);
        Limits base = defaults.get(segment);
        Override override = overrides.get(accountId);
        if (override == null || !override.approved || override.expiresAt.isBefore(clock.instant())) {
            return base;
        }
        return new Limits(
                override.perTransfer != null ? override.perTransfer : base.perTransfer(),
                override.perDay != null ? override.perDay : base.perDay(),
                base.transfersPerDay());
    }

    public void check(String accountId, BigDecimal amount, BigDecimal outflowToday, int transfersToday) {
        Limits limits = effectiveLimits(accountId);
        if (amount.compareTo(limits.perTransfer()) > 0) {
            throw new TransactionException("LIMIT_EXCEEDED",
                    "Amount " + amount + " exceeds per-transfer limit " + limits.perTransfer());
        }
        if (outflowToday.add(amount).compareTo(limits.perDay()) > 0) {
            throw new TransactionException("DAILY_LIMIT_EXCEEDED",
                    "Daily outflow would exceed " + limits.perDay());
        }
        if (transfersToday + 1 > limits.transfersPerDay()) {
            throw new TransactionException("DAILY_COUNT_EXCEEDED",
                    "More than " + limits.transfersPerDay() + " transfers in one day");
        }
    }

    public Override requestOverride(String accountId, BigDecimal perTransfer, BigDecimal perDay, Instant expiresAt,
                                    String requestedBy) {
        if (perTransfer == null && perDay == null) {
            throw new TransactionException("OVERRIDE_EMPTY", "Override must raise at least one limit");
        }
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new TransactionException("OVERRIDE_INVALID_EXPIRY", "Override expiry must be in the future");
        }
        if (expiresAt.isAfter(clock.instant().plusSeconds(30L * 24 * 3600))) {
            throw new TransactionException("OVERRIDE_TOO_LONG", "Overrides may not exceed 30 days");
        }
        Limits base = defaults.get(segmentByAccount.getOrDefault(accountId, Segment.RETAIL));
        if (perTransfer != null && perTransfer.compareTo(base.perTransfer()) <= 0) {
            throw new TransactionException("OVERRIDE_NOT_HIGHER", "Override must exceed the base per-transfer limit");
        }
        BigDecimal highest = perTransfer != null ? perTransfer : perDay;
        boolean needsDual = highest.compareTo(DUAL_APPROVAL_THRESHOLD) > 0;
        Override override = new Override(accountId, perTransfer, perDay, expiresAt, requestedBy, needsDual ? 2 : 1);
        overrides.put(accountId, override);
        pendingApprovals.put(accountId, new ArrayList<>());
        return override;
    }

    public Override approveOverride(String accountId, String approver) {
        Override override = overrides.get(accountId);
        if (override == null) {
            throw new TransactionException("OVERRIDE_NOT_FOUND", "No override pending for " + accountId);
        }
        if (approver == null || approver.equals(override.requestedBy)) {
            throw new TransactionException("OVERRIDE_SELF_APPROVAL", "Requester cannot approve their own override");
        }
        List<String> approvers = pendingApprovals.get(accountId);
        if (approvers.contains(approver)) {
            throw new TransactionException("OVERRIDE_DUPLICATE_APPROVAL", "Approver already recorded");
        }
        approvers.add(approver);
        if (approvers.size() >= override.approvalsRequired) {
            override.approved = true;
        }
        return override;
    }

    public void assignSegment(String accountId, Segment segment) {
        segmentByAccount.put(accountId, segment);
    }

    public enum Segment { RETAIL, PREFERRED, PRIVATE_BANK, SMALL_BUSINESS }

    public record Limits(BigDecimal perTransfer, BigDecimal perDay, int transfersPerDay) {
    }

    public static class Override {
        private final String accountId;
        private final BigDecimal perTransfer;
        private final BigDecimal perDay;
        private final Instant expiresAt;
        private final String requestedBy;
        private final int approvalsRequired;
        private boolean approved;

        Override(String accountId, BigDecimal perTransfer, BigDecimal perDay, Instant expiresAt, String requestedBy,
                 int approvalsRequired) {
            this.accountId = accountId;
            this.perTransfer = perTransfer;
            this.perDay = perDay;
            this.expiresAt = expiresAt;
            this.requestedBy = requestedBy;
            this.approvalsRequired = approvalsRequired;
        }

        public String getAccountId() {
            return accountId;
        }

        public BigDecimal getPerTransfer() {
            return perTransfer;
        }

        public BigDecimal getPerDay() {
            return perDay;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public String getRequestedBy() {
            return requestedBy;
        }

        public int getApprovalsRequired() {
            return approvalsRequired;
        }

        public boolean isApproved() {
            return approved;
        }
    }
}
