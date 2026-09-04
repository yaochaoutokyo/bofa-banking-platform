package com.bofa.paymentsgateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class PaymentRequest {

    @NotBlank
    private String debtorAccount;

    @NotBlank
    private String creditorAccount;

    @NotBlank
    private String creditorRoutingNumber;

    @NotNull
    private BigDecimal amount;

    @NotBlank
    private String currency;

    private PaymentRail preferredRail;
    private boolean urgent;
    private String memo;

    public String getDebtorAccount() {
        return debtorAccount;
    }

    public void setDebtorAccount(String debtorAccount) {
        this.debtorAccount = debtorAccount;
    }

    public String getCreditorAccount() {
        return creditorAccount;
    }

    public void setCreditorAccount(String creditorAccount) {
        this.creditorAccount = creditorAccount;
    }

    public String getCreditorRoutingNumber() {
        return creditorRoutingNumber;
    }

    public void setCreditorRoutingNumber(String creditorRoutingNumber) {
        this.creditorRoutingNumber = creditorRoutingNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public PaymentRail getPreferredRail() {
        return preferredRail;
    }

    public void setPreferredRail(PaymentRail preferredRail) {
        this.preferredRail = preferredRail;
    }

    public boolean isUrgent() {
        return urgent;
    }

    public void setUrgent(boolean urgent) {
        this.urgent = urgent;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
