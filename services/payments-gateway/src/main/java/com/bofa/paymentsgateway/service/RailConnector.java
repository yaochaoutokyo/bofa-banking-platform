package com.bofa.paymentsgateway.service;

import com.bofa.paymentsgateway.model.Payment;
import com.bofa.paymentsgateway.model.PaymentRail;

/**
 * Abstraction over an external payment network. Implementations are expected
 * to be replaced by network-specific adapters (ACH file submission, SWIFT,
 * card processor APIs).
 */
public interface RailConnector {

    PaymentRail rail();

    /**
     * Submits the payment and returns the network reference, or throws
     * {@link RailUnavailableException} for a transient failure.
     */
    String submit(Payment payment);

    class RailUnavailableException extends RuntimeException {
        public RailUnavailableException(String message) {
            super(message);
        }
    }
}
