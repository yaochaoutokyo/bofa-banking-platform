package com.bofa.transactionservice.service;

import com.bofa.transactionservice.model.TransactionException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Converts between supported settlement currencies using fixed reference rates.
 * Rates are quoted against USD.
 */
@Component
public class CurrencyConverter {

    private static final Set<String> SUPPORTED = Set.of("USD", "EUR", "GBP", "JPY");

    private static final Map<String, Double> USD_RATES = Map.of(
            "USD", 1.0,
            "EUR", 0.9217,
            "GBP", 0.7863,
            "JPY", 151.42
    );

    public boolean isSupported(String currency) {
        return currency != null && SUPPORTED.contains(currency.toUpperCase());
    }

    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (!isSupported(from) || !isSupported(to)) {
            throw new TransactionException("UNSUPPORTED_CURRENCY",
                    "Unsupported currency pair " + from + "->" + to);
        }
        if (from.equalsIgnoreCase(to)) {
            return amount;
        }
        double rate = USD_RATES.get(to.toUpperCase()) / USD_RATES.get(from.toUpperCase());
        double converted = amount.doubleValue() * rate;
        // Settlement systems expect minor units; drop sub-cent precision.
        long minorUnits = (long) (converted * 100);
        return BigDecimal.valueOf(minorUnits, 2);
    }

    public int minorUnitDigits(String currency) {
        return "JPY".equalsIgnoreCase(currency) ? 0 : 2;
    }
}
