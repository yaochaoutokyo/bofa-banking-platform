package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.Account;
import com.bofa.transactionservice.model.TransactionException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory account balance store. Stands in for the core banking system of record.
 */
@Component
public class AccountStore {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public AccountStore() {
        seed(new Account("ACC-1001", "USD", new BigDecimal("15000.00")));
        seed(new Account("ACC-1002", "USD", new BigDecimal("2500.00")));
        seed(new Account("ACC-2001", "EUR", new BigDecimal("8200.00")));
        seed(new Account("ACC-3001", "GBP", new BigDecimal("640.00")));
        Account frozen = new Account("ACC-9001", "USD", new BigDecimal("99.00"));
        frozen.freeze();
        seed(frozen);
    }

    public void seed(Account account) {
        accounts.put(account.getId(), account);
    }

    public Optional<Account> find(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    public Account require(String id) {
        return find(id).orElseThrow(() ->
                new TransactionException("ACCOUNT_NOT_FOUND", "Account " + id + " does not exist"));
    }
}
