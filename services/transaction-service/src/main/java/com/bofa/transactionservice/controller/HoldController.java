package com.bofa.transactionservice.controller;

import com.bofa.transactionservice.model.TransactionException;
import com.bofa.transactionservice.service.HoldService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/holds")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    public record PlaceHoldRequest(String accountId, BigDecimal amount, String merchantRef) {
    }

    public record CaptureRequest(BigDecimal amount) {
    }

    @PostMapping
    public ResponseEntity<HoldService.Hold> place(@RequestBody PlaceHoldRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(holdService.place(request.accountId(), request.amount(), request.merchantRef()));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<Void> capture(@PathVariable String id, @RequestBody CaptureRequest request) {
        holdService.capture(id, request.amount());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> release(@PathVariable String id) {
        holdService.release(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/account/{accountId}")
    public Map<String, Object> forAccount(@PathVariable String accountId) {
        List<HoldService.Hold> holds = holdService.activeHolds(accountId);
        return Map.of("holds", holds, "availableBalance", holdService.availableBalance(accountId));
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<Map<String, String>> handle(TransactionException ex) {
        HttpStatus status = ex.getCode().endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
