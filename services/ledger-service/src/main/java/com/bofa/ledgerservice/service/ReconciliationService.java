package com.bofa.ledgerservice.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compares ledger balances against an external statement (e.g. Fed master
 * account, custodian) and reports breaks above a tolerance.
 */
@Service
public class ReconciliationService {

    private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.01");

    private final LedgerService ledgerService;

    public ReconciliationService(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    public ReconciliationReport reconcile(Map<String, BigDecimal> externalBalances) {
        return reconcile(externalBalances, DEFAULT_TOLERANCE);
    }

    public ReconciliationReport reconcile(Map<String, BigDecimal> externalBalances, BigDecimal tolerance) {
        if (tolerance == null || tolerance.signum() < 0) {
            throw new IllegalArgumentException("Tolerance must be non-negative");
        }
        Map<String, BigDecimal> ledger = ledgerService.trialBalance();
        List<Break> breaks = new ArrayList<>();
        int matched = 0;

        for (Map.Entry<String, BigDecimal> e : externalBalances.entrySet()) {
            BigDecimal internal = ledger.get(e.getKey());
            if (internal == null) {
                breaks.add(new Break(e.getKey(), null, e.getValue(), "MISSING_IN_LEDGER"));
                continue;
            }
            BigDecimal diff = internal.subtract(e.getValue()).abs();
            if (diff.compareTo(tolerance) > 0) {
                breaks.add(new Break(e.getKey(), internal, e.getValue(), "AMOUNT_MISMATCH"));
            } else {
                matched++;
            }
        }
        for (String code : ledger.keySet()) {
            if (!externalBalances.containsKey(code) && ledger.get(code).signum() != 0) {
                breaks.add(new Break(code, ledger.get(code), null, "MISSING_IN_STATEMENT"));
            }
        }
        return new ReconciliationReport(matched, breaks);
    }

    public record Break(String accountCode, BigDecimal ledgerBalance, BigDecimal externalBalance, String reason) {
    }

    public record ReconciliationReport(int matched, List<Break> breaks) {
        public boolean isClean() {
            return breaks.isEmpty();
        }
    }
}
