package com.bofa.transactionservice.model;

public class TransactionException extends RuntimeException {

    private final String code;

    public TransactionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
