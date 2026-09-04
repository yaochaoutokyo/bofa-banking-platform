package com.bofa.ledgerservice.service;

import com.bofa.ledgerservice.model.LedgerAccount;
import com.bofa.ledgerservice.model.LedgerException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChartOfAccounts {

    private final Map<String, LedgerAccount> accounts = new ConcurrentHashMap<>();

    public ChartOfAccounts() {
        register(new LedgerAccount("1000", "Cash", LedgerAccount.Kind.ASSET));
        register(new LedgerAccount("1200", "Customer Loans Receivable", LedgerAccount.Kind.ASSET));
        register(new LedgerAccount("2000", "Customer Deposits", LedgerAccount.Kind.LIABILITY));
        register(new LedgerAccount("2100", "Settlement Payable", LedgerAccount.Kind.LIABILITY));
        register(new LedgerAccount("3000", "Retained Earnings", LedgerAccount.Kind.EQUITY));
        register(new LedgerAccount("4000", "Fee Income", LedgerAccount.Kind.REVENUE));
        register(new LedgerAccount("5000", "Operating Expense", LedgerAccount.Kind.EXPENSE));
    }

    public void register(LedgerAccount account) {
        if (accounts.containsKey(account.getCode())) {
            throw new LedgerException("DUPLICATE_ACCOUNT", "Account code already exists: " + account.getCode());
        }
        accounts.put(account.getCode(), account);
    }

    public LedgerAccount require(String code) {
        LedgerAccount account = accounts.get(code);
        if (account == null) {
            throw new LedgerException("UNKNOWN_ACCOUNT", "Unknown ledger account " + code);
        }
        return account;
    }

    public Collection<LedgerAccount> all() {
        return accounts.values();
    }
}
