package com.bofa.authservice.service;

import com.bofa.authservice.model.AuthException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Third-party data-sharing consent (open banking / Section 1033). A customer
 * grants a registered aggregator access to specific data scopes for a bounded
 * period; every access is checked against the live consent record.
 */
@Service
public class ConsentService {

    static final Duration MAX_CONSENT_DURATION = Duration.ofDays(365);
    static final Set<String> SHAREABLE_SCOPES = Set.of(
            "accounts:balances", "accounts:transactions", "identity:contact", "statements:read");

    private final Clock clock;
    private final Map<String, Consent> consents = new ConcurrentHashMap<>();
    private final Map<String, Aggregator> aggregators = new ConcurrentHashMap<>();

    public ConsentService() {
        this(Clock.systemUTC());
    }

    public ConsentService(Clock clock) {
        this.clock = clock;
        aggregators.put("agg-plaidlike", new Aggregator("agg-plaidlike", "Ledgerlink Inc", true));
        aggregators.put("agg-budgetapp", new Aggregator("agg-budgetapp", "BudgetBuddy LLC", true));
        aggregators.put("agg-suspended", new Aggregator("agg-suspended", "Suspended Data Co", false));
    }

    public Consent grant(String customerId, String aggregatorId, Set<String> scopes, Duration duration) {
        Aggregator aggregator = aggregators.get(aggregatorId);
        if (aggregator == null) {
            throw new AuthException("CONSENT_UNKNOWN_AGGREGATOR", "Aggregator is not registered");
        }
        if (!aggregator.active()) {
            throw new AuthException("CONSENT_AGGREGATOR_SUSPENDED", "Aggregator is suspended");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new AuthException("CONSENT_SCOPES_REQUIRED", "At least one scope is required");
        }
        for (String scope : scopes) {
            if (!SHAREABLE_SCOPES.contains(scope)) {
                throw new AuthException("CONSENT_SCOPE_NOT_SHAREABLE", "Scope " + scope + " cannot be shared");
            }
        }
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new AuthException("CONSENT_INVALID_DURATION", "Duration must be positive");
        }
        if (duration.compareTo(MAX_CONSENT_DURATION) > 0) {
            throw new AuthException("CONSENT_TOO_LONG", "Consent may not exceed 365 days");
        }
        for (Consent existing : consents.values()) {
            if (existing.customerId().equals(customerId) && existing.aggregatorId().equals(aggregatorId)
                    && existing.isActive(clock.instant())) {
                throw new AuthException("CONSENT_EXISTS", "An active consent already exists; revoke it first");
            }
        }
        Instant now = clock.instant();
        Consent consent = new Consent(UUID.randomUUID().toString(), customerId, aggregatorId, Set.copyOf(scopes),
                now, now.plus(duration), null, 0);
        consents.put(consent.id(), consent);
        return consent;
    }

    public Consent authorizeAccess(String consentId, String aggregatorId, String scope) {
        Consent consent = consents.get(consentId);
        if (consent == null) {
            throw new AuthException("CONSENT_NOT_FOUND", "Unknown consent");
        }
        if (!consent.aggregatorId().equals(aggregatorId)) {
            throw new AuthException("CONSENT_WRONG_AGGREGATOR", "Consent was not granted to this aggregator");
        }
        Instant now = clock.instant();
        if (consent.revokedAt() != null) {
            throw new AuthException("CONSENT_REVOKED", "Consent has been revoked");
        }
        if (!consent.expiresAt().isAfter(now)) {
            throw new AuthException("CONSENT_EXPIRED", "Consent has expired");
        }
        if (!consent.scopes().contains(scope)) {
            throw new AuthException("CONSENT_SCOPE_DENIED", "Consent does not include scope " + scope);
        }
        Aggregator aggregator = aggregators.get(aggregatorId);
        if (aggregator == null || !aggregator.active()) {
            throw new AuthException("CONSENT_AGGREGATOR_SUSPENDED", "Aggregator is suspended");
        }
        Consent updated = consent.withAccessCount(consent.accessCount() + 1);
        consents.put(consentId, updated);
        return updated;
    }

    public Consent revoke(String consentId, String customerId) {
        Consent consent = consents.get(consentId);
        if (consent == null) {
            throw new AuthException("CONSENT_NOT_FOUND", "Unknown consent");
        }
        if (!consent.customerId().equals(customerId)) {
            throw new AuthException("CONSENT_NOT_OWNER", "Only the granting customer can revoke");
        }
        if (consent.revokedAt() != null) {
            return consent;
        }
        Consent revoked = consent.withRevokedAt(clock.instant());
        consents.put(consentId, revoked);
        return revoked;
    }

    public List<Consent> activeFor(String customerId) {
        List<Consent> result = new ArrayList<>();
        Instant now = clock.instant();
        for (Consent c : consents.values()) {
            if (c.customerId().equals(customerId) && c.isActive(now)) {
                result.add(c);
            }
        }
        return result;
    }

    public void suspendAggregator(String aggregatorId) {
        Aggregator a = aggregators.get(aggregatorId);
        if (a == null) {
            throw new AuthException("CONSENT_UNKNOWN_AGGREGATOR", "Aggregator is not registered");
        }
        aggregators.put(aggregatorId, new Aggregator(a.id(), a.name(), false));
    }

    public record Aggregator(String id, String name, boolean active) {
    }

    public record Consent(String id, String customerId, String aggregatorId, Set<String> scopes, Instant grantedAt,
                          Instant expiresAt, Instant revokedAt, int accessCount) {

        boolean isActive(Instant now) {
            return revokedAt == null && expiresAt.isAfter(now);
        }

        Consent withAccessCount(int count) {
            return new Consent(id, customerId, aggregatorId, scopes, grantedAt, expiresAt, revokedAt, count);
        }

        Consent withRevokedAt(Instant at) {
            return new Consent(id, customerId, aggregatorId, scopes, grantedAt, expiresAt, at, accessCount);
        }
    }
}
