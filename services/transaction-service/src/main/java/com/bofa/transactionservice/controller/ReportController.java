package com.bofa.transactionservice.controller;

import com.bofa.transactionservice.service.BatchTransferService;
import com.bofa.transactionservice.service.TransactionReportService;
import com.bofa.transactionservice.model.TransferRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private final TransactionReportService reportService;
    private final BatchTransferService batchTransferService;

    public ReportController(TransactionReportService reportService, BatchTransferService batchTransferService) {
        this.reportService = reportService;
        this.batchTransferService = batchTransferService;
    }

    @GetMapping("/reports/accounts/{accountId}/summary")
    public TransactionReportService.AccountSummary summary(@PathVariable String accountId,
                                                           @RequestParam Instant from,
                                                           @RequestParam Instant to) {
        return reportService.summarize(accountId, from, to);
    }

    @GetMapping("/reports/ctr")
    public Map<String, BigDecimal> ctr(@RequestParam LocalDate day, @RequestParam List<String> accounts) {
        return reportService.ctrCandidates(day, accounts);
    }

    @PostMapping("/transactions/batch")
    public BatchTransferService.BatchResult batch(@Valid @RequestBody List<TransferRequest> requests,
                                                  @RequestParam(defaultValue = "true") boolean atomic) {
        return batchTransferService.execute(requests, atomic);
    }
}
