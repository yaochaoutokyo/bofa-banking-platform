package com.bofa.authservice.service;

import com.bofa.authservice.model.AuthException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service-to-service API keys. Keys are shown once at creation, stored only
 * as a SHA-256 hash, carry a fixed scope set, and can be rotated with an
 * overlap window so callers can cut over without downtime.
 */
@Service
public class ApiKeyService {

    static final String PREFIX = "bofa_sk_";
    static final Set<String> ALLOWED_SCOPES = Set.of(
            "accounts:read", "accounts:write", "transactions:read", "transactions:write",
            "audit:read", "pii:detokenize", "kyc:read");

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, ApiKeyRecord> byHash = new ConcurrentHashMap<>();

    public ApiKeyService() {
        this(Clock.systemUTC());
    }

    public ApiKeyService(Clock clock) {
        this.clock = clock;
    }

    public IssuedKey issue(String serviceName, Set<String> scopes, Instant expiresAt) {
        if (serviceName == null || !serviceName.matches("[a-z][a-z0-9-]{2,40}")) {
            throw new AuthException("APIKEY_INVALID_SERVICE", "Service name must be kebab-case, 3-41 chars");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new AuthException("APIKEY_SCOPES_REQUIRED", "At least one scope is required");
        }
        for (String scope : scopes) {
            if (!ALLOWED_SCOPES.contains(scope)) {
                throw new AuthException("APIKEY_UNKNOWN_SCOPE", "Unknown scope " + scope);
            }
        }
        if (scopes.contains("pii:detokenize") && scopes.size() > 1) {
            throw new AuthException("APIKEY_SCOPE_CONFLICT", "pii:detokenize keys must be single-purpose");
        }
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new AuthException("APIKEY_INVALID_EXPIRY", "Expiry must be in the future");
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String keyId = "key_" + HexFormat.of().formatHex(sha256(secret)).substring(0, 12);
        ApiKeyRecord record = new ApiKeyRecord(keyId, serviceName, Set.copyOf(scopes), clock.instant(),
                expiresAt, null);
        byHash.put(hashHex(secret), record);
        return new IssuedKey(keyId, secret, record);
    }

    public ApiKeyRecord authenticate(String presentedKey) {
        if (presentedKey == null || !presentedKey.startsWith(PREFIX)) {
            throw new AuthException("APIKEY_MALFORMED", "API key has unexpected format");
        }
        ApiKeyRecord record = byHash.get(hashHex(presentedKey));
        if (record == null) {
            throw new AuthException("APIKEY_INVALID", "Unknown API key");
        }
        Instant now = clock.instant();
        if (record.revokedAt() != null && !record.revokedAt().isAfter(now)) {
            throw new AuthException("APIKEY_REVOKED", "API key has been revoked");
        }
        if (record.expiresAt() != null && !record.expiresAt().isAfter(now)) {
            throw new AuthException("APIKEY_EXPIRED", "API key has expired");
        }
        return record;
    }

    public void requireScope(ApiKeyRecord record, String scope) {
        if (!record.scopes().contains(scope)) {
            throw new AuthException("INSUFFICIENT_SCOPE", "API key lacks scope " + scope);
        }
    }

    /** Issues a replacement key and schedules the old one for revocation after the overlap window. */
    public IssuedKey rotate(String presentedKey, java.time.Duration overlap) {
        ApiKeyRecord current = authenticate(presentedKey);
        Instant revokeAt = clock.instant().plus(overlap);
        byHash.put(hashHex(presentedKey), current.withRevokedAt(revokeAt));
        return issue(current.serviceName(), current.scopes(), current.expiresAt());
    }

    public int revokeAll(String serviceName) {
        int count = 0;
        Instant now = clock.instant();
        for (Map.Entry<String, ApiKeyRecord> e : byHash.entrySet()) {
            if (e.getValue().serviceName().equals(serviceName) && e.getValue().revokedAt() == null) {
                e.setValue(e.getValue().withRevokedAt(now));
                count++;
            }
        }
        return count;
    }

    public List<ApiKeyRecord> list(String serviceName) {
        List<ApiKeyRecord> result = new ArrayList<>();
        for (ApiKeyRecord r : byHash.values()) {
            if (r.serviceName().equals(serviceName)) {
                result.add(r);
            }
        }
        return result;
    }

    private static String hashHex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record ApiKeyRecord(String keyId, String serviceName, Set<String> scopes, Instant createdAt,
                               Instant expiresAt, Instant revokedAt) {
        ApiKeyRecord withRevokedAt(Instant at) {
            return new ApiKeyRecord(keyId, serviceName, scopes, createdAt, expiresAt, at);
        }
    }

    public record IssuedKey(String keyId, String secret, ApiKeyRecord record) {
    }
}
