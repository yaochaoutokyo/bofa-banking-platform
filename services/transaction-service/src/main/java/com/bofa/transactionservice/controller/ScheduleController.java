package com.bofa.transactionservice.controller;

import com.bofa.transactionservice.model.Transaction;
import com.bofa.transactionservice.model.TransactionException;
import com.bofa.transactionservice.model.TransferRequest;
import com.bofa.transactionservice.service.SanctionsScreeningService;
import com.bofa.transactionservice.service.ScheduledTransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ScheduleController {

    private final ScheduledTransferService scheduledTransferService;
    private final SanctionsScreeningService sanctionsScreeningService;

    public ScheduleController(ScheduledTransferService scheduledTransferService,
                              SanctionsScreeningService sanctionsScreeningService) {
        this.scheduledTransferService = scheduledTransferService;
        this.sanctionsScreeningService = sanctionsScreeningService;
    }

    public record CreateScheduleRequest(@Valid TransferRequest template,
                                        ScheduledTransferService.Frequency frequency,
                                        LocalDate firstRun,
                                        Integer maxRuns) {
    }

    public record ScreenRequest(String transactionId, String counterpartyName, String counterpartyCountry) {
    }

    @PostMapping("/schedules")
    public ResponseEntity<ScheduledTransferService.Schedule> create(@Valid @RequestBody CreateScheduleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduledTransferService.create(
                request.template(), request.frequency(), request.firstRun(), request.maxRuns()));
    }

    @GetMapping("/schedules/{id}")
    public ScheduledTransferService.Schedule get(@PathVariable String id) {
        return scheduledTransferService.get(id);
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        scheduledTransferService.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/schedules/run-due")
    public List<Transaction> runDue() {
        return scheduledTransferService.runDue();
    }

    @PostMapping("/screening")
    public SanctionsScreeningService.ScreeningResult screen(@RequestBody ScreenRequest request) {
        return sanctionsScreeningService.screen(
                request.transactionId(), request.counterpartyName(), request.counterpartyCountry());
    }

    @PostMapping("/screening/{transactionId}/resolve")
    public SanctionsScreeningService.ScreeningResult resolve(@PathVariable String transactionId,
                                                             @RequestParam boolean truePositive,
                                                             @RequestParam String analyst) {
        return sanctionsScreeningService.resolve(transactionId, truePositive, analyst);
    }

    @GetMapping("/screening/pending")
    public List<SanctionsScreeningService.ScreeningResult> pending() {
        return sanctionsScreeningService.pending();
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<Map<String, String>> handle(TransactionException ex) {
        HttpStatus status = ex.getCode().endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
