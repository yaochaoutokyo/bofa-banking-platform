package com.bofa.paymentsgateway.service;

import org.springframework.stereotype.Component;

/**
 * ABA routing number checksum (3-7-1 weighting).
 */
@Component
public class RoutingNumberValidator {

    private static final int[] WEIGHTS = {3, 7, 1, 3, 7, 1, 3, 7, 1};

    public boolean isValid(String routingNumber) {
        if (routingNumber == null || routingNumber.length() != 9) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            char c = routingNumber.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * WEIGHTS[i];
        }
        return sum % 10 == 0;
    }
}
