package com.bofa.authservice.service;

import com.bofa.authservice.model.AuthException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Risk-based authentication. Scores each login attempt from device
 * fingerprint, geolocation velocity, and behavioural signals, then decides
 * whether to allow, step-up (MFA), or deny.
 */
@Service
public class DeviceTrustService {

    static final int STEP_UP_THRESHOLD = 40;
    static final int DENY_THRESHOLD = 80;
    static final Duration TRUST_TTL = Duration.ofDays(90);
    static final double IMPOSSIBLE_TRAVEL_KMH = 900.0;

    private final Clock clock;
    private final Map<String, TrustedDevice> trustedDevices = new ConcurrentHashMap<>();
    private final Map<String, LoginContext> lastLoginByUser = new ConcurrentHashMap<>();
    private final Map<String, Integer> recentFailuresByIp = new ConcurrentHashMap<>();

    public DeviceTrustService() {
        this(Clock.systemUTC());
    }

    public DeviceTrustService(Clock clock) {
        this.clock = clock;
    }

    public Assessment assess(String username, LoginContext context) {
        if (context == null || context.deviceFingerprint() == null || context.deviceFingerprint().isBlank()) {
            throw new AuthException("DEVICE_FINGERPRINT_REQUIRED", "Device fingerprint is required");
        }
        List<String> signals = new ArrayList<>();
        int score = 0;

        String key = username + "|" + context.deviceFingerprint();
        TrustedDevice trusted = trustedDevices.get(key);
        boolean deviceKnown = trusted != null && trusted.trustedUntil().isAfter(clock.instant());
        if (!deviceKnown) {
            score += 35;
            signals.add("NEW_DEVICE");
        }

        LoginContext previous = lastLoginByUser.get(username);
        if (previous != null && previous.latitude() != null && context.latitude() != null) {
            double km = haversineKm(previous.latitude(), previous.longitude(), context.latitude(), context.longitude());
            double hours = Math.max(Duration.between(previous.at(), context.at()).toMinutes() / 60.0, 1.0 / 60);
            if (km / hours > IMPOSSIBLE_TRAVEL_KMH) {
                score += 50;
                signals.add("IMPOSSIBLE_TRAVEL");
            } else if (km > 500) {
                score += 15;
                signals.add("NEW_GEO");
            }
        }

        if (context.country() != null && isHighRiskCountry(context.country())) {
            score += 30;
            signals.add("HIGH_RISK_COUNTRY");
        }
        if (context.torExitNode()) {
            score += 40;
            signals.add("ANONYMIZING_NETWORK");
        }
        int ipFailures = recentFailuresByIp.getOrDefault(context.ipAddress(), 0);
        if (ipFailures >= 10) {
            score += 40;
            signals.add("IP_BRUTE_FORCE");
        } else if (ipFailures >= 3) {
            score += 15;
            signals.add("IP_REPEATED_FAILURES");
        }
        int hour = context.at().atZone(java.time.ZoneOffset.UTC).getHour();
        if (hour >= 1 && hour <= 4) {
            score += 5;
            signals.add("OFF_HOURS");
        }

        Decision decision;
        if (score >= DENY_THRESHOLD) {
            decision = Decision.DENY;
        } else if (score >= STEP_UP_THRESHOLD) {
            decision = Decision.STEP_UP;
        } else {
            decision = Decision.ALLOW;
        }
        return new Assessment(decision, Math.min(score, 100), signals);
    }

    public void recordSuccess(String username, LoginContext context, boolean rememberDevice) {
        lastLoginByUser.put(username, context);
        recentFailuresByIp.remove(context.ipAddress());
        if (rememberDevice) {
            trustedDevices.put(username + "|" + context.deviceFingerprint(),
                    new TrustedDevice(context.deviceFingerprint(), clock.instant(), clock.instant().plus(TRUST_TTL)));
        }
    }

    public void recordFailure(LoginContext context) {
        if (context != null && context.ipAddress() != null) {
            recentFailuresByIp.merge(context.ipAddress(), 1, Integer::sum);
        }
    }

    public int forgetDevices(String username) {
        int removed = 0;
        for (String key : List.copyOf(trustedDevices.keySet())) {
            if (key.startsWith(username + "|")) {
                trustedDevices.remove(key);
                removed++;
            }
        }
        return removed;
    }

    static boolean isHighRiskCountry(String iso2) {
        return switch (iso2.toUpperCase(Locale.ROOT)) {
            case "KP", "IR", "SY", "CU", "RU", "BY", "MM" -> true;
            default -> false;
        };
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public enum Decision { ALLOW, STEP_UP, DENY }

    public record LoginContext(String deviceFingerprint, String ipAddress, String country, Double latitude,
                               Double longitude, boolean torExitNode, Instant at) {
    }

    public record TrustedDevice(String fingerprint, Instant trustedAt, Instant trustedUntil) {
    }

    public record Assessment(Decision decision, int riskScore, List<String> signals) {
    }
}
