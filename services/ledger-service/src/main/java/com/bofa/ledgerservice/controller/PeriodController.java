package com.bofa.ledgerservice.controller;

import com.bofa.ledgerservice.service.PeriodCloseService;
import com.bofa.ledgerservice.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ledger")
public class PeriodController {

    private final PeriodCloseService periodCloseService;
    private final ReconciliationService reconciliationService;

    public PeriodController(PeriodCloseService periodCloseService, ReconciliationService reconciliationService) {
        this.periodCloseService = periodCloseService;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/periods/{period}/close")
    public PeriodCloseService.CloseRecord close(@RequestParam YearMonth period, @RequestParam String approvedBy) {
        return periodCloseService.close(period, approvedBy);
    }

    @GetMapping("/periods")
    public List<PeriodCloseService.CloseRecord> history() {
        return periodCloseService.history();
    }

    @PostMapping("/reconcile")
    public ReconciliationService.ReconciliationReport reconcile(@RequestBody Map<String, BigDecimal> external) {
        return reconciliationService.reconcile(external);
    }
}
