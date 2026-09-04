package com.bofa.transactionservice.controller;

import com.bofa.transactionservice.model.TransactionException;
import com.bofa.transactionservice.service.DisputeService;
import com.bofa.transactionservice.service.LimitPolicyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DisputeController {

    private final DisputeService disputeService;
    private final LimitPolicyService limitPolicyService;

    public DisputeController(DisputeService disputeService, LimitPolicyService limitPolicyService) {
        this.disputeService = disputeService;
        this.limitPolicyService = limitPolicyService;
    }

    public record OpenDisputeRequest(String transactionId, String customerAccountId, DisputeService.Reason reason,
                                     String narrative) {
    }

    public record OverrideRequest(BigDecimal perTransfer, BigDecimal perDay, Instant expiresAt, String requestedBy) {
    }

    @PostMapping("/disputes")
    public ResponseEntity<DisputeService.Dispute> open(@RequestBody OpenDisputeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(disputeService.open(
                request.transactionId(), request.customerAccountId(), request.reason(), request.narrative()));
    }

    @PostMapping("/disputes/{id}/provisional-credit")
    public DisputeService.Dispute provisionalCredit(@PathVariable String id) {
        return disputeService.provisionalCredit(id);
    }

    @PostMapping("/disputes/{id}/resolve")
    public DisputeService.Dispute resolve(@PathVariable String id, @RequestParam boolean inCustomerFavor,
                                          @RequestParam String analyst) {
        return disputeService.resolve(id, inCustomerFavor, analyst);
    }

    @GetMapping("/disputes/overdue")
    public List<DisputeService.Dispute> overdue() {
        return disputeService.overdue();
    }

    @GetMapping("/limits/{accountId}")
    public LimitPolicyService.Limits limits(@PathVariable String accountId) {
        return limitPolicyService.effectiveLimits(accountId);
    }

    @PostMapping("/limits/{accountId}/override")
    public LimitPolicyService.Override requestOverride(@PathVariable String accountId,
                                                       @RequestBody OverrideRequest request) {
        return limitPolicyService.requestOverride(accountId, request.perTransfer(), request.perDay(),
                request.expiresAt(), request.requestedBy());
    }

    @PostMapping("/limits/{accountId}/override/approve")
    public LimitPolicyService.Override approve(@PathVariable String accountId, @RequestParam String approver) {
        return limitPolicyService.approveOverride(accountId, approver);
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<Map<String, String>> handle(TransactionException ex) {
        HttpStatus status = ex.getCode().endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
