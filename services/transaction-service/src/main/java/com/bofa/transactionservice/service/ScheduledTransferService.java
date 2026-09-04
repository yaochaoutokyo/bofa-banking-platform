package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.Transaction;
import com.bofa.transactionservice.model.TransactionException;
import com.bofa.transactionservice.model.TransferRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recurring and future-dated transfers. Executions that fall on a weekend or
 * holiday roll forward to the next business day; three consecutive failures
 * suspend the schedule.
 */
@Service
public class ScheduledTransferService {

    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final TransactionService transactionService;
    private final Clock clock;
    private final Map<String, Schedule> schedules = new ConcurrentHashMap<>();
    private final List<LocalDate> holidays = new ArrayList<>();

    public ScheduledTransferService(TransactionService transactionService) {
        this(transactionService, Clock.systemUTC());
    }

    public ScheduledTransferService(TransactionService transactionService, Clock clock) {
        this.transactionService = transactionService;
        this.clock = clock;
        holidays.add(LocalDate.of(2025, 1, 1));
        holidays.add(LocalDate.of(2025, 7, 4));
        holidays.add(LocalDate.of(2025, 12, 25));
    }

    public Schedule create(TransferRequest template, Frequency frequency, LocalDate firstRun, Integer maxRuns) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (firstRun == null || firstRun.isBefore(today)) {
            throw new TransactionException("INVALID_START_DATE", "First run must be today or later");
        }
        if (template.getAmount() == null || template.getAmount().signum() <= 0) {
            throw new TransactionException("INVALID_AMOUNT", "Scheduled amount must be positive");
        }
        if (maxRuns != null && maxRuns < 1) {
            throw new TransactionException("INVALID_RUN_COUNT", "maxRuns must be at least 1");
        }
        if (frequency == Frequency.ONCE && maxRuns != null && maxRuns != 1) {
            throw new TransactionException("INVALID_RUN_COUNT", "One-time schedules run exactly once");
        }
        Schedule schedule = new Schedule(UUID.randomUUID().toString(), template, frequency,
                nextBusinessDay(firstRun), maxRuns);
        schedules.put(schedule.getId(), schedule);
        return schedule;
    }

    public void cancel(String scheduleId) {
        Schedule schedule = require(scheduleId);
        schedule.status = Status.CANCELLED;
    }

    /** Runs every schedule due on or before today. Returns the transactions produced. */
    public List<Transaction> runDue() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Transaction> produced = new ArrayList<>();
        for (Schedule schedule : schedules.values()) {
            if (schedule.status != Status.ACTIVE || schedule.nextRun.isAfter(today)) {
                continue;
            }
            TransferRequest request = copyWithIdempotencyKey(schedule.template,
                    schedule.getId() + ":" + schedule.nextRun);
            try {
                produced.add(transactionService.transfer(request));
                schedule.consecutiveFailures = 0;
                schedule.runs++;
            } catch (TransactionException e) {
                schedule.consecutiveFailures++;
                schedule.lastError = e.getCode();
                if (schedule.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    schedule.status = Status.SUSPENDED;
                    continue;
                }
            }
            advance(schedule);
        }
        return produced;
    }

    public Schedule get(String id) {
        return require(id);
    }

    private void advance(Schedule schedule) {
        if (schedule.maxRuns != null && schedule.runs >= schedule.maxRuns) {
            schedule.status = Status.COMPLETED;
            return;
        }
        LocalDate next = switch (schedule.frequency) {
            case ONCE -> null;
            case WEEKLY -> schedule.nextRun.plusWeeks(1);
            case BIWEEKLY -> schedule.nextRun.plusWeeks(2);
            case MONTHLY -> schedule.nextRun.plusMonths(1);
        };
        if (next == null) {
            schedule.status = Status.COMPLETED;
        } else {
            schedule.nextRun = nextBusinessDay(next);
        }
    }

    LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date;
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    private Schedule require(String id) {
        Schedule schedule = schedules.get(id);
        if (schedule == null) {
            throw new TransactionException("SCHEDULE_NOT_FOUND", "Unknown schedule " + id);
        }
        return schedule;
    }

    private static TransferRequest copyWithIdempotencyKey(TransferRequest template, String key) {
        TransferRequest r = new TransferRequest();
        r.setSourceAccountId(template.getSourceAccountId());
        r.setDestinationAccountId(template.getDestinationAccountId());
        r.setAmount(template.getAmount());
        r.setCurrency(template.getCurrency());
        r.setIdempotencyKey(key);
        return r;
    }

    public enum Frequency { ONCE, WEEKLY, BIWEEKLY, MONTHLY }

    public enum Status { ACTIVE, SUSPENDED, COMPLETED, CANCELLED }

    public static class Schedule {
        private final String id;
        private final TransferRequest template;
        private final Frequency frequency;
        private final Integer maxRuns;
        private LocalDate nextRun;
        private Status status = Status.ACTIVE;
        private int runs;
        private int consecutiveFailures;
        private String lastError;

        Schedule(String id, TransferRequest template, Frequency frequency, LocalDate nextRun, Integer maxRuns) {
            this.id = id;
            this.template = template;
            this.frequency = frequency;
            this.nextRun = nextRun;
            this.maxRuns = maxRuns;
        }

        public String getId() {
            return id;
        }

        public TransferRequest getTemplate() {
            return template;
        }

        public Frequency getFrequency() {
            return frequency;
        }

        public Integer getMaxRuns() {
            return maxRuns;
        }

        public LocalDate getNextRun() {
            return nextRun;
        }

        public Status getStatus() {
            return status;
        }

        public int getRuns() {
            return runs;
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        public String getLastError() {
            return lastError;
        }

        public BigDecimal getAmount() {
            return template.getAmount();
        }
    }
}
