package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.Transaction;
import com.bofa.transactionservice.model.TransactionStatus;
import com.bofa.transactionservice.model.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionServiceTest {

    private TransactionService service;
    private AccountStore accountStore;

    @BeforeEach
    void setUp() {
        accountStore = new AccountStore();
        service = new TransactionService(accountStore, new CurrencyConverter(), new BigDecimal("250000.00"));
    }

    @Test
    void transfersBetweenUsdAccounts() {
        TransferRequest request = new TransferRequest();
        request.setSourceAccountId("ACC-1001");
        request.setDestinationAccountId("ACC-1002");
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("USD");

        Transaction txn = service.transfer(request);

        assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
        assertEquals(new BigDecimal("14900.00"), accountStore.require("ACC-1001").getBalance());
        assertEquals(new BigDecimal("2600.00"), accountStore.require("ACC-1002").getBalance());
    }

}
