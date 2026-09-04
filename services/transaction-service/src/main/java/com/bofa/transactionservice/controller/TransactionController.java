package com.bofa.transactionservice.controller;

import com.bofa.transactionservice.model.Transaction;
import com.bofa.transactionservice.model.TransactionException;
import com.bofa.transactionservice.model.TransferRequest;
import com.bofa.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/transfer")
    public ResponseEntity<Transaction> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.transfer(request));
    }

    @PostMapping("/{id}/reverse")
    public Transaction reverse(@PathVariable String id) {
        return transactionService.reverse(id);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Transaction> get(@PathVariable String id) {
        return transactionService.get(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/account/{accountId}")
    public List<Transaction> listForAccount(@PathVariable String accountId) {
        return transactionService.listForAccount(accountId);
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<Map<String, String>> handleTransactionException(TransactionException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "ACCOUNT_NOT_FOUND", "TRANSACTION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INSUFFICIENT_FUNDS", "LIMIT_EXCEEDED", "DAILY_LIMIT_EXCEEDED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "SOURCE_FROZEN", "DESTINATION_FROZEN" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
