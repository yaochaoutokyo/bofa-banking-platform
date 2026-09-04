package com.bofa.paymentsgateway.service;

import com.bofa.paymentsgateway.model.Payment;
import com.bofa.paymentsgateway.model.PaymentRail;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local stand-ins for the external rails. The ACH connector fails every third
 * submission to exercise retry handling.
 */
@Configuration
public class InMemoryRailConnectors {

    @Bean
    public List<RailConnector> railConnectors() {
        return List.of(
                simple(PaymentRail.WIRE, "FEDWIRE"),
                simple(PaymentRail.CARD, "CARDNET"),
                simple(PaymentRail.RTP, "TCH"),
                flakyAch());
    }

    private RailConnector simple(PaymentRail rail, String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return new RailConnector() {
            @Override
            public PaymentRail rail() {
                return rail;
            }

            @Override
            public String submit(Payment payment) {
                return prefix + "-" + seq.incrementAndGet();
            }
        };
    }

    private RailConnector flakyAch() {
        AtomicInteger seq = new AtomicInteger();
        return new RailConnector() {
            @Override
            public PaymentRail rail() {
                return PaymentRail.ACH;
            }

            @Override
            public String submit(Payment payment) {
                int n = seq.incrementAndGet();
                if (n % 3 == 0) {
                    throw new RailUnavailableException("ACH operator window closed");
                }
                return "ACH-" + n;
            }
        };
    }
}
