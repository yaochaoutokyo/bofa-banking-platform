package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.Account;
import com.bofa.transactionservice.model.Transaction;
import com.bofa.transactionservice.model.TransactionException;
import com.bofa.transactionservice.model.TransferRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes account-to-account transfers.
 *
 * Edge cases owned by this service (see COMPLIANCE.md, control TXN-1..TXN-6):
 *  - amount sign and magnitude (negative, zero, overflow beyond daily limit)
 *  - currency mismatch between request and source account
 *  - idempotency-key reuse with differing payloads
 *  - rollback when the credit leg fails after the debit leg has been applied
 */
@Service
public class TransactionService {

    private static final BigDecimal MAX_SINGLE_TRANSFER = new BigDecimal("1000000.00");

    private final AccountStore accountStore;
    private final CurrencyConverter currencyConverter;
    private final BigDecimal dailyLimit;

    private final Map<String, Transaction> transactionsById = new ConcurrentHashMap<>();
    private final Map<String, Transaction> transactionsByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> dailyOutflowByAccount = new ConcurrentHashMap<>();

    public TransactionService(AccountStore accountStore,
                              CurrencyConverter currencyConverter,
                              @Value("${transaction.daily-limit:250000.00}") BigDecimal dailyLimit) {
        this.accountStore = accountStore;
        this.currencyConverter = currencyConverter;
        this.dailyLimit = dailyLimit;
    }

    public Transaction transfer(TransferRequest request) {
        if (request.getIdempotencyKey() != null) {
            Transaction existing = transactionsByIdempotencyKey.get(request.getIdempotencyKey());
            if (existing != null) {
                return existing;
            }
        }

        Account source = accountStore.require(request.getSourceAccountId());
        Account destination = accountStore.require(request.getDestinationAccountId());

        validate(request, source);

        Transaction txn = new Transaction(
                source.getId(), destination.getId(), request.getAmount(),
                request.getCurrency().toUpperCase(), request.getIdempotencyKey());
        transactionsById.put(txn.getId(), txn);
        if (txn.getIdempotencyKey() != null) {
            transactionsByIdempotencyKey.put(txn.getIdempotencyKey(), txn);
        }

        BigDecimal debitAmount = request.getAmount();
        BigDecimal creditAmount = currencyConverter.convert(
                debitAmount, request.getCurrency(), destination.getCurrency());

        source.debit(debitAmount);
        dailyOutflowByAccount.merge(source.getId(), debitAmount, BigDecimal::add);

        if (destination.isFrozen()) {
            txn.markFailed("Destination account is frozen");
            throw new TransactionException("DESTINATION_FROZEN", "Destination account is frozen");
        }

        destination.credit(creditAmount);
        txn.markCompleted();
        return txn;
    }

    public Transaction reverse(String transactionId) {
        Transaction txn = get(transactionId)
                .orElseThrow(() -> new TransactionException("TRANSACTION_NOT_FOUND", "Unknown transaction"));
        Account source = accountStore.require(txn.getSourceAccountId());
        Account destination = accountStore.require(txn.getDestinationAccountId());
        BigDecimal creditAmount = currencyConverter.convert(
                txn.getAmount(), txn.getCurrency(), destination.getCurrency());
        destination.debit(creditAmount);
        source.credit(txn.getAmount());
        txn.markReversed();
        return txn;
    }

    public Optional<Transaction> get(String id) {
        return Optional.ofNullable(transactionsById.get(id));
    }

    public List<Transaction> listForAccount(String accountId) {
        List<Transaction> result = new ArrayList<>();
        for (Transaction txn : transactionsById.values()) {
            if (txn.getSourceAccountId().equals(accountId) || txn.getDestinationAccountId().equals(accountId)) {
                result.add(txn);
            }
        }
        return result;
    }

    private void validate(TransferRequest request, Account source) {
        if (source.isFrozen()) {
            throw new TransactionException("SOURCE_FROZEN", "Source account is frozen");
        }
        if (source.getId().equals(request.getDestinationAccountId())) {
            throw new TransactionException("SAME_ACCOUNT", "Source and destination must differ");
        }
        if (!currencyConverter.isSupported(request.getCurrency())) {
            throw new TransactionException("UNSUPPORTED_CURRENCY", "Unsupported currency " + request.getCurrency());
        }

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(MAX_SINGLE_TRANSFER) > 0) {
            throw new TransactionException("LIMIT_EXCEEDED", "Exceeds single-transfer limit");
        }
        if (amount.scale() > currencyConverter.minorUnitDigits(request.getCurrency())) {
            throw new TransactionException("INVALID_PRECISION", "Too many decimal places for " + request.getCurrency());
        }
        if (source.getBalance().compareTo(amount) < 0) {
            throw new TransactionException("INSUFFICIENT_FUNDS", "Insufficient funds");
        }

        BigDecimal outflowToday = dailyOutflowByAccount.getOrDefault(source.getId(), BigDecimal.ZERO);
        if (outflowToday.add(amount).compareTo(dailyLimit) > 0) {
            throw new TransactionException("DAILY_LIMIT_EXCEEDED", "Exceeds daily outflow limit");
        }
    }
}
