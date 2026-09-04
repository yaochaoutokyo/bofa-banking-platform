package com.bofa.transactionservice.model;

import java.math.BigDecimal;

public class Account {

    private final String id;
    private final String currency;
    private BigDecimal balance;
    private boolean frozen;

    public Account(String id, String currency, BigDecimal balance) {
        this.id = id;
        this.currency = currency;
        this.balance = balance;
    }

    public String getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void freeze() {
        this.frozen = true;
    }

    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }
}
