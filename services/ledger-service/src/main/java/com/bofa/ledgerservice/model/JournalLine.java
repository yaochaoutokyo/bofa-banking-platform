package com.bofa.ledgerservice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class JournalLine {

    @NotBlank
    private String accountCode;

    @NotNull
    private EntryType type;

    @NotNull
    private BigDecimal amount;

    public JournalLine() {
    }

    public JournalLine(String accountCode, EntryType type, BigDecimal amount) {
        this.accountCode = accountCode;
        this.type = type;
        this.amount = amount;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public EntryType getType() {
        return type;
    }

    public void setType(EntryType type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
