package com.bofa.ledgerservice.controller;

import com.bofa.ledgerservice.model.JournalEntry;
import com.bofa.ledgerservice.model.LedgerException;
import com.bofa.ledgerservice.service.LedgerService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/entries")
    public ResponseEntity<JournalEntry> post(@Valid @RequestBody JournalEntry entry) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledgerService.post(entry));
    }

    @GetMapping("/entries")
    public List<JournalEntry> journal() {
        return ledgerService.journal();
    }

    @GetMapping("/entries/{id}")
    public ResponseEntity<JournalEntry> get(@PathVariable String id) {
        return ledgerService.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/trial-balance")
    public Map<String, Object> trialBalance() {
        Map<String, BigDecimal> balances = ledgerService.trialBalance();
        return Map.of("balances", balances, "balanced", ledgerService.isBalanced());
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<Map<String, String>> handle(LedgerException ex) {
        HttpStatus status = "UNKNOWN_ACCOUNT".equals(ex.getCode()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
