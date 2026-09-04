package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.Account;
import com.bofa.transactionservice.model.TransactionException;
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
 * Places and releases temporary holds (pre-authorizations) against account
 * balances. Held funds reduce available balance but not ledger balance.
 */
@Service
public class HoldService {

    public static final Duration DEFAULT_HOLD_TTL = Duration.ofDays(7);

    private final AccountStore accountStore;
    private final Clock clock;
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();

    public HoldService(AccountStore accountStore) {
        this(accountStore, Clock.systemUTC());
    }

    public HoldService(AccountStore accountStore, Clock clock) {
        this.accountStore = accountStore;
        this.clock = clock;
    }

    public Hold place(String accountId, BigDecimal amount, String merchantRef) {
        Account account = accountStore.require(accountId);
        if (amount == null || amount.signum() <= 0) {
            throw new TransactionException("INVALID_AMOUNT", "Hold amount must be positive");
        }
        if (account.isFrozen()) {
            throw new TransactionException("SOURCE_FROZEN", "Cannot place hold on frozen account");
        }
        if (availableBalance(accountId).compareTo(amount) < 0) {
            throw new TransactionException("INSUFFICIENT_FUNDS", "Available balance below hold amount");
        }
        Hold hold = new Hold(UUID.randomUUID().toString(), accountId, amount, merchantRef,
                clock.instant(), clock.instant().plus(DEFAULT_HOLD_TTL));
        holds.put(hold.id(), hold);
        return hold;
    }

    public void release(String holdId) {
        if (holds.remove(holdId) == null) {
            throw new TransactionException("HOLD_NOT_FOUND", "Unknown hold " + holdId);
        }
    }

    /** Converts a hold into a real debit. Capturing more than the hold is a hard error. */
    public void capture(String holdId, BigDecimal amount) {
        Hold hold = holds.get(holdId);
        if (hold == null) {
            throw new TransactionException("HOLD_NOT_FOUND", "Unknown hold " + holdId);
        }
        if (hold.expiresAt().isBefore(clock.instant())) {
            holds.remove(holdId);
            throw new TransactionException("HOLD_EXPIRED", "Hold has expired");
        }
        if (amount.compareTo(hold.amount()) > 0) {
            throw new TransactionException("CAPTURE_EXCEEDS_HOLD", "Capture exceeds held amount");
        }
        accountStore.require(hold.accountId()).debit(amount);
        holds.remove(holdId);
    }

    public BigDecimal availableBalance(String accountId) {
        BigDecimal held = BigDecimal.ZERO;
        Instant now = clock.instant();
        for (Hold hold : holds.values()) {
            if (hold.accountId().equals(accountId) && hold.expiresAt().isAfter(now)) {
                held = held.add(hold.amount());
            }
        }
        return accountStore.require(accountId).getBalance().subtract(held);
    }

    public List<Hold> activeHolds(String accountId) {
        List<Hold> result = new ArrayList<>();
        for (Hold hold : holds.values()) {
            if (hold.accountId().equals(accountId)) {
                result.add(hold);
            }
        }
        return result;
    }

    public int expireStale() {
        Instant now = clock.instant();
        int removed = 0;
        for (Hold hold : List.copyOf(holds.values())) {
            if (!hold.expiresAt().isAfter(now)) {
                holds.remove(hold.id());
                removed++;
            }
        }
        return removed;
    }

    public record Hold(String id, String accountId, BigDecimal amount, String merchantRef,
                       Instant placedAt, Instant expiresAt) {
    }
}
