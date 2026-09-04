package com.bofa.ledgerservice.service;

import com.bofa.ledgerservice.model.EntryType;
import com.bofa.ledgerservice.model.JournalEntry;
import com.bofa.ledgerservice.model.JournalLine;
import com.bofa.ledgerservice.model.LedgerAccount;
import com.bofa.ledgerservice.model.LedgerException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Double-entry posting engine. Every journal entry must balance
 * (sum of debits == sum of credits) before any account is mutated.
 */
@Service
public class LedgerService {

    private final ChartOfAccounts chartOfAccounts;
    private final Map<String, JournalEntry> journal = new LinkedHashMap<>();

    public LedgerService(ChartOfAccounts chartOfAccounts) {
        this.chartOfAccounts = chartOfAccounts;
    }

    public synchronized JournalEntry post(JournalEntry entry) {
        validate(entry);

        for (JournalLine line : entry.getLines()) {
            chartOfAccounts.require(line.getAccountCode()).apply(line.getType(), line.getAmount());
        }

        entry.setId(UUID.randomUUID().toString());
        entry.setPostedAt(Instant.now());
        entry.setPosted(true);
        journal.put(entry.getId(), entry);
        return entry;
    }

    public Optional<JournalEntry> get(String id) {
        return Optional.ofNullable(journal.get(id));
    }

    public List<JournalEntry> journal() {
        return new ArrayList<>(journal.values());
    }

    public Map<String, BigDecimal> trialBalance() {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (LedgerAccount account : chartOfAccounts.all()) {
            balances.put(account.getCode(), account.getBalance());
        }
        return balances;
    }

    public boolean isBalanced() {
        BigDecimal debitNormal = BigDecimal.ZERO;
        BigDecimal creditNormal = BigDecimal.ZERO;
        for (LedgerAccount account : chartOfAccounts.all()) {
            if (account.getKind() == LedgerAccount.Kind.ASSET || account.getKind() == LedgerAccount.Kind.EXPENSE) {
                debitNormal = debitNormal.add(account.getBalance());
            } else {
                creditNormal = creditNormal.add(account.getBalance());
            }
        }
        return debitNormal.compareTo(creditNormal) == 0;
    }

    private void validate(JournalEntry entry) {
        if (entry.getLines() == null || entry.getLines().size() < 2) {
            throw new LedgerException("TOO_FEW_LINES", "A journal entry needs at least two lines");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (JournalLine line : entry.getLines()) {
            if (line.getAmount() == null || line.getAmount().signum() <= 0) {
                throw new LedgerException("INVALID_AMOUNT", "Line amounts must be positive");
            }
            if (line.getAmount().scale() > 2) {
                throw new LedgerException("INVALID_PRECISION", "Line amounts must have at most 2 decimals");
            }
            chartOfAccounts.require(line.getAccountCode());
            if (line.getType() == EntryType.DEBIT) {
                debits = debits.add(line.getAmount());
            } else {
                credits = credits.add(line.getAmount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new LedgerException("UNBALANCED", "Debits " + debits + " do not equal credits " + credits);
        }
    }
}
