package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.Transaction;
import com.bofa.transactionservice.model.TransactionStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Produces per-account activity summaries and the daily Currency Transaction
 * Report (CTR) candidate list required under the Bank Secrecy Act.
 */
@Service
public class TransactionReportService {

    public static final BigDecimal CTR_THRESHOLD = new BigDecimal("10000.00");

    private final TransactionService transactionService;

    public TransactionReportService(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public AccountSummary summarize(String accountId, Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal outflow = BigDecimal.ZERO;
        Map<TransactionStatus, Integer> byStatus = new EnumMap<>(TransactionStatus.class);
        int count = 0;

        for (Transaction txn : transactionService.listForAccount(accountId)) {
            if (txn.getCreatedAt().isBefore(from) || txn.getCreatedAt().isAfter(to)) {
                continue;
            }
            count++;
            byStatus.merge(txn.getStatus(), 1, Integer::sum);
            if (txn.getStatus() != TransactionStatus.COMPLETED) {
                continue;
            }
            if (txn.getSourceAccountId().equals(accountId)) {
                outflow = outflow.add(txn.getAmount());
            } else {
                inflow = inflow.add(txn.getAmount());
            }
        }
        return new AccountSummary(accountId, count, inflow, outflow, inflow.subtract(outflow), byStatus);
    }

    /**
     * Accounts whose aggregate completed outflow on a given day meets the CTR threshold.
     */
    public Map<String, BigDecimal> ctrCandidates(LocalDate day, List<String> accountIds) {
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Map<String, BigDecimal> result = new TreeMap<>();
        for (String accountId : accountIds) {
            AccountSummary summary = summarize(accountId, start, end);
            if (summary.outflow().compareTo(CTR_THRESHOLD) >= 0) {
                result.put(accountId, summary.outflow());
            }
        }
        return result;
    }

    public record AccountSummary(String accountId, int transactionCount, BigDecimal inflow, BigDecimal outflow,
                                 BigDecimal net, Map<TransactionStatus, Integer> byStatus) {
    }
}
