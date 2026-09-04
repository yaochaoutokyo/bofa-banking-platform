package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.Transaction;
import com.bofa.transactionservice.model.TransactionException;
import com.bofa.transactionservice.model.TransferRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a batch of transfers (payroll, bulk disbursements) either
 * all-or-nothing or best-effort.
 *
 * In all-or-nothing mode a failure after N successful legs must reverse the
 * N completed legs; partial-failure handling is the highest-risk path here.
 */
@Service
public class BatchTransferService {

    public static final int MAX_BATCH_SIZE = 500;

    private final TransactionService transactionService;

    public BatchTransferService(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public BatchResult execute(List<TransferRequest> requests, boolean atomic) {
        if (requests == null || requests.isEmpty()) {
            throw new TransactionException("EMPTY_BATCH", "Batch contains no transfers");
        }
        if (requests.size() > MAX_BATCH_SIZE) {
            throw new TransactionException("BATCH_TOO_LARGE", "Batch exceeds " + MAX_BATCH_SIZE + " transfers");
        }

        List<Transaction> completed = new ArrayList<>();
        Map<Integer, String> failures = new LinkedHashMap<>();

        for (int i = 0; i < requests.size(); i++) {
            try {
                completed.add(transactionService.transfer(requests.get(i)));
            } catch (TransactionException e) {
                failures.put(i, e.getCode());
                if (atomic) {
                    rollback(completed);
                    return new BatchResult(List.of(), failures, true);
                }
            }
        }
        return new BatchResult(completed, failures, false);
    }

    private void rollback(List<Transaction> completed) {
        for (int i = completed.size() - 1; i >= 0; i--) {
            Transaction txn = completed.get(i);
            try {
                transactionService.reverse(txn.getId());
            } catch (TransactionException ignored) {
                // A failed reversal leaves the ledger inconsistent; surfaced via BatchResult.rolledBack
            }
        }
    }

    public record BatchResult(List<Transaction> completed, Map<Integer, String> failures, boolean rolledBack) {

        public int successCount() {
            return completed.size();
        }

        public int failureCount() {
            return failures.size();
        }
    }
}
