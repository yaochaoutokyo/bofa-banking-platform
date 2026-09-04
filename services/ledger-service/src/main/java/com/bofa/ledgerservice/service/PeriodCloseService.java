package com.bofa.ledgerservice.service;

import com.bofa.ledgerservice.model.EntryType;
import com.bofa.ledgerservice.model.JournalEntry;
import com.bofa.ledgerservice.model.JournalLine;
import com.bofa.ledgerservice.model.LedgerAccount;
import com.bofa.ledgerservice.model.LedgerException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Month-end close: sweeps revenue and expense balances into retained earnings
 * and locks the period so no further entries can be posted into it.
 */
@Service
public class PeriodCloseService {

    private static final String RETAINED_EARNINGS = "3000";

    private final LedgerService ledgerService;
    private final ChartOfAccounts chartOfAccounts;
    private final Map<YearMonth, CloseRecord> closedPeriods = new LinkedHashMap<>();

    public PeriodCloseService(LedgerService ledgerService, ChartOfAccounts chartOfAccounts) {
        this.ledgerService = ledgerService;
        this.chartOfAccounts = chartOfAccounts;
    }

    public CloseRecord close(YearMonth period, String approvedBy) {
        if (closedPeriods.containsKey(period)) {
            throw new LedgerException("PERIOD_ALREADY_CLOSED", period + " is already closed");
        }
        if (period.isAfter(YearMonth.from(LocalDate.now()))) {
            throw new LedgerException("PERIOD_IN_FUTURE", "Cannot close a future period");
        }
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new LedgerException("APPROVAL_REQUIRED", "Period close requires an approver");
        }
        if (!ledgerService.isBalanced()) {
            throw new LedgerException("UNBALANCED", "Ledger is out of balance; close aborted");
        }

        List<JournalLine> lines = new ArrayList<>();
        BigDecimal netIncome = BigDecimal.ZERO;
        for (LedgerAccount account : chartOfAccounts.all()) {
            BigDecimal balance = account.getBalance();
            if (balance.signum() == 0) {
                continue;
            }
            if (account.getKind() == LedgerAccount.Kind.REVENUE) {
                lines.add(new JournalLine(account.getCode(), EntryType.DEBIT, balance));
                netIncome = netIncome.add(balance);
            } else if (account.getKind() == LedgerAccount.Kind.EXPENSE) {
                lines.add(new JournalLine(account.getCode(), EntryType.CREDIT, balance));
                netIncome = netIncome.subtract(balance);
            }
        }

        JournalEntry closingEntry = null;
        if (!lines.isEmpty()) {
            if (netIncome.signum() > 0) {
                lines.add(new JournalLine(RETAINED_EARNINGS, EntryType.CREDIT, netIncome));
            } else if (netIncome.signum() < 0) {
                lines.add(new JournalLine(RETAINED_EARNINGS, EntryType.DEBIT, netIncome.negate()));
            }
            closingEntry = new JournalEntry();
            closingEntry.setDescription("Period close " + period);
            closingEntry.setLines(lines);
            closingEntry = ledgerService.post(closingEntry);
        }

        CloseRecord record = new CloseRecord(period, approvedBy, netIncome,
                closingEntry == null ? null : closingEntry.getId());
        closedPeriods.put(period, record);
        return record;
    }

    public boolean isClosed(YearMonth period) {
        return closedPeriods.containsKey(period);
    }

    public List<CloseRecord> history() {
        return new ArrayList<>(closedPeriods.values());
    }

    public record CloseRecord(YearMonth period, String approvedBy, BigDecimal netIncome, String closingEntryId) {
    }
}
