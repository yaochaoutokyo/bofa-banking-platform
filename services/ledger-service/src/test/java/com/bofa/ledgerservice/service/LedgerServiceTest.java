package com.bofa.ledgerservice.service;

import com.bofa.ledgerservice.model.EntryType;
import com.bofa.ledgerservice.model.JournalEntry;
import com.bofa.ledgerservice.model.JournalLine;
import com.bofa.ledgerservice.model.LedgerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerServiceTest {

    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(new ChartOfAccounts());
    }

    private JournalEntry entry(JournalLine... lines) {
        JournalEntry e = new JournalEntry();
        e.setDescription("test");
        e.setLines(List.of(lines));
        return e;
    }

    @Test
    void postsBalancedEntryAndUpdatesBalances() {
        ledger.post(entry(
                new JournalLine("1000", EntryType.DEBIT, new BigDecimal("500.00")),
                new JournalLine("2000", EntryType.CREDIT, new BigDecimal("500.00"))));

        assertEquals(new BigDecimal("500.00"), ledger.trialBalance().get("1000"));
        assertEquals(new BigDecimal("500.00"), ledger.trialBalance().get("2000"));
        assertTrue(ledger.isBalanced());
    }

    @Test
    void rejectsUnbalancedEntry() {
        LedgerException ex = assertThrows(LedgerException.class, () -> ledger.post(entry(
                new JournalLine("1000", EntryType.DEBIT, new BigDecimal("500.00")),
                new JournalLine("2000", EntryType.CREDIT, new BigDecimal("499.99")))));

        assertEquals("UNBALANCED", ex.getCode());
        assertEquals(BigDecimal.ZERO, ledger.trialBalance().get("1000"));
    }

    @Test
    void assignsIdAndRecordsInJournal() {
        JournalEntry posted = ledger.post(entry(
                new JournalLine("5000", EntryType.DEBIT, new BigDecimal("42.00")),
                new JournalLine("1000", EntryType.CREDIT, new BigDecimal("42.00"))));

        assertTrue(posted.isPosted());
        assertTrue(ledger.get(posted.getId()).isPresent());
        assertEquals(1, ledger.journal().size());
    }
}
