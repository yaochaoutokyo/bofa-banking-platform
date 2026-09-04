package com.bofa.ledgerservice.model;

public class LedgerException extends RuntimeException {

    private final String code;

    public LedgerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
