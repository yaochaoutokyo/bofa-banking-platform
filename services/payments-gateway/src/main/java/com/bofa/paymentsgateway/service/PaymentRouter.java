package com.bofa.paymentsgateway.service;

import com.bofa.paymentsgateway.model.Payment;
import com.bofa.paymentsgateway.model.PaymentException;
import com.bofa.paymentsgateway.model.PaymentRail;
import com.bofa.paymentsgateway.model.PaymentRequest;
import com.bofa.paymentsgateway.model.PaymentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses a rail for each payment, applies fees, and submits with bounded retry.
 *
 * Rail selection:
 *  - RTP for urgent payments under $100k when the creditor bank supports it
 *  - WIRE for amounts >= $25k or any urgent payment not eligible for RTP
 *  - ACH otherwise
 *  - Wires received after the daily cutoff are queued for next business day
 */
@Service
public class PaymentRouter {

    private static final BigDecimal WIRE_THRESHOLD = new BigDecimal("25000.00");
    private static final BigDecimal RTP_LIMIT = new BigDecimal("100000.00");
    private static final BigDecimal ACH_LIMIT = new BigDecimal("1000000.00");

    private final Map<PaymentRail, RailConnector> connectors = new EnumMap<>(PaymentRail.class);
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final RoutingNumberValidator routingNumberValidator;
    private final int maxRetries;
    private final int wireCutoffHourUtc;
    private final Clock clock;

    public PaymentRouter(List<RailConnector> railConnectors,
                         RoutingNumberValidator routingNumberValidator,
                         @Value("${payments.max-retries:3}") int maxRetries,
                         @Value("${payments.wire.cutoff-hour-utc:21}") int wireCutoffHourUtc) {
        this(railConnectors, routingNumberValidator, maxRetries, wireCutoffHourUtc, Clock.systemUTC());
    }

    public PaymentRouter(List<RailConnector> railConnectors, RoutingNumberValidator routingNumberValidator,
                         int maxRetries, int wireCutoffHourUtc, Clock clock) {
        railConnectors.forEach(c -> connectors.put(c.rail(), c));
        this.routingNumberValidator = routingNumberValidator;
        this.maxRetries = maxRetries;
        this.wireCutoffHourUtc = wireCutoffHourUtc;
        this.clock = clock;
    }

    public Payment submit(PaymentRequest request) {
        validate(request);
        Payment payment = new Payment(request);
        payment.setRail(selectRail(request));
        payment.setFee(feeFor(payment.getRail(), request.getAmount()));
        payments.put(payment.getId(), payment);

        if (payment.getRail() == PaymentRail.WIRE && isAfterWireCutoff()) {
            payment.setStatus(PaymentStatus.ACCEPTED);
            return payment;
        }
        dispatch(payment);
        return payment;
    }

    public void dispatch(Payment payment) {
        RailConnector connector = connectors.get(payment.getRail());
        if (connector == null) {
            payment.setStatus(PaymentStatus.REJECTED);
            payment.setRejectionReason("No connector for rail " + payment.getRail());
            return;
        }
        while (payment.getAttempts() < maxRetries) {
            payment.incrementAttempts();
            try {
                payment.setRailReference(connector.submit(payment));
                payment.setStatus(PaymentStatus.ROUTED);
                return;
            } catch (RailConnector.RailUnavailableException e) {
                payment.setStatus(PaymentStatus.RETRYING);
                payment.setRejectionReason(e.getMessage());
            }
        }
        payment.setStatus(PaymentStatus.REJECTED);
    }

    public Optional<Payment> get(String id) {
        return Optional.ofNullable(payments.get(id));
    }

    public List<Payment> pending() {
        List<Payment> result = new ArrayList<>();
        for (Payment p : payments.values()) {
            if (p.getStatus() == PaymentStatus.ACCEPTED || p.getStatus() == PaymentStatus.RETRYING) {
                result.add(p);
            }
        }
        return result;
    }

    PaymentRail selectRail(PaymentRequest request) {
        if (request.getPreferredRail() != null) {
            return request.getPreferredRail();
        }
        BigDecimal amount = request.getAmount();
        if (request.isUrgent() && amount.compareTo(RTP_LIMIT) < 0) {
            return PaymentRail.RTP;
        }
        if (request.isUrgent() || amount.compareTo(WIRE_THRESHOLD) >= 0) {
            return PaymentRail.WIRE;
        }
        return PaymentRail.ACH;
    }

    BigDecimal feeFor(PaymentRail rail, BigDecimal amount) {
        return switch (rail) {
            case ACH -> new BigDecimal("0.25");
            case RTP -> new BigDecimal("1.00");
            case WIRE -> new BigDecimal("25.00");
            case CARD -> amount.multiply(new BigDecimal("0.029")).setScale(2, RoundingMode.HALF_UP);
        };
    }

    boolean isAfterWireCutoff() {
        return clock.instant().atZone(ZoneOffset.UTC).getHour() >= wireCutoffHourUtc;
    }

    private void validate(PaymentRequest request) {
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new PaymentException("INVALID_AMOUNT", "Amount must be positive");
        }
        if (request.getAmount().compareTo(ACH_LIMIT) > 0 && request.getPreferredRail() == PaymentRail.ACH) {
            throw new PaymentException("RAIL_LIMIT", "Amount exceeds ACH limit");
        }
        if (!"USD".equalsIgnoreCase(request.getCurrency())) {
            throw new PaymentException("UNSUPPORTED_CURRENCY", "Only USD payments are routed domestically");
        }
        if (!routingNumberValidator.isValid(request.getCreditorRoutingNumber())) {
            throw new PaymentException("INVALID_ROUTING_NUMBER", "Creditor routing number failed checksum");
        }
        if (request.getDebtorAccount().equals(request.getCreditorAccount())) {
            throw new PaymentException("SAME_ACCOUNT", "Debtor and creditor accounts must differ");
        }
        if (request.getMemo() != null && request.getMemo().length() > 140) {
            throw new PaymentException("MEMO_TOO_LONG", "Memo exceeds 140 characters");
        }
    }
}
