package com.bofa.authservice.service;

import com.bofa.authservice.model.AuthException;
import com.bofa.authservice.model.TokenClaims;
import com.bofa.authservice.model.TokenResponse;
import com.bofa.authservice.model.UserRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque refresh tokens with single-use rotation. Reusing a rotated token
 * indicates theft and revokes the whole family.
 */
@Service
public class RefreshTokenService {

    public static final Duration REFRESH_TTL = Duration.ofDays(30);

    private final UserDirectory userDirectory;
    private final JwtCodec jwtCodec;
    private final String issuer;
    private final String audience;
    private final long accessTtlSeconds;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, RefreshRecord> tokens = new ConcurrentHashMap<>();
    private final Map<String, Boolean> revokedFamilies = new ConcurrentHashMap<>();

    public RefreshTokenService(UserDirectory userDirectory,
                               JwtCodec jwtCodec,
                               @Value("${auth.jwt.issuer}") String issuer,
                               @Value("${auth.jwt.audience}") String audience,
                               @Value("${auth.jwt.ttl-seconds}") long accessTtlSeconds) {
        this(userDirectory, jwtCodec, issuer, audience, accessTtlSeconds, Clock.systemUTC());
    }

    public RefreshTokenService(UserDirectory userDirectory, JwtCodec jwtCodec, String issuer, String audience,
                               long accessTtlSeconds, Clock clock) {
        this.userDirectory = userDirectory;
        this.jwtCodec = jwtCodec;
        this.issuer = issuer;
        this.audience = audience;
        this.accessTtlSeconds = accessTtlSeconds;
        this.clock = clock;
    }

    public String issue(String username) {
        String family = randomToken();
        return store(username, family);
    }

    public RefreshResult rotate(String refreshToken) {
        RefreshRecord record = tokens.get(refreshToken);
        if (record == null) {
            throw new AuthException("REFRESH_INVALID", "Unknown refresh token");
        }
        if (revokedFamilies.containsKey(record.family())) {
            throw new AuthException("REFRESH_REVOKED", "Token family has been revoked");
        }
        if (record.used()) {
            revokedFamilies.put(record.family(), Boolean.TRUE);
            throw new AuthException("REFRESH_REUSED", "Refresh token reuse detected; family revoked");
        }
        if (record.expiresAt().isBefore(clock.instant())) {
            tokens.remove(refreshToken);
            throw new AuthException("REFRESH_EXPIRED", "Refresh token expired");
        }

        UserRecord user = userDirectory.find(record.username())
                .orElseThrow(() -> new AuthException("REFRESH_INVALID", "User no longer exists"));
        if (user.isLocked()) {
            revokedFamilies.put(record.family(), Boolean.TRUE);
            throw new AuthException("ACCOUNT_LOCKED", "Account is locked");
        }

        tokens.put(refreshToken, record.markUsed());
        String next = store(record.username(), record.family());

        long now = clock.instant().getEpochSecond();
        TokenClaims claims = new TokenClaims(user.getUsername(), issuer, audience, now,
                now + accessTtlSeconds, user.getScopes());
        return new RefreshResult(new TokenResponse(jwtCodec.encode(claims), accessTtlSeconds), next);
    }

    public void revokeAll(String username) {
        for (RefreshRecord record : tokens.values()) {
            if (record.username().equals(username)) {
                revokedFamilies.put(record.family(), Boolean.TRUE);
            }
        }
    }

    private String store(String username, String family) {
        String token = randomToken();
        tokens.put(token, new RefreshRecord(username, family, clock.instant().plus(REFRESH_TTL), false));
        return token;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record RefreshRecord(String username, String family, Instant expiresAt, boolean used) {
        RefreshRecord markUsed() {
            return new RefreshRecord(username, family, expiresAt, true);
        }
    }

    public record RefreshResult(TokenResponse access, String refreshToken) {
    }
}
