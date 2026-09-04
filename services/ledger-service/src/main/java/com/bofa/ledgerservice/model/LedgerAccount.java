package com.bofa.ledgerservice.model;

import java.math.BigDecimal;

public class LedgerAccount {

    public enum Kind { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE }

    private final String code;
    private final String name;
    private final Kind kind;
    private BigDecimal balance = BigDecimal.ZERO;

    public LedgerAccount(String code, String name, Kind kind) {
        this.code = code;
        this.name = name;
        this.kind = kind;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Kind getKind() {
        return kind;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    /** Debits increase asset/expense accounts and decrease the others. */
    public void apply(EntryType type, BigDecimal amount) {
        boolean debitNormal = kind == Kind.ASSET || kind == Kind.EXPENSE;
        boolean increases = (type == EntryType.DEBIT) == debitNormal;
        balance = increases ? balance.add(amount) : balance.subtract(amount);
    }
}
