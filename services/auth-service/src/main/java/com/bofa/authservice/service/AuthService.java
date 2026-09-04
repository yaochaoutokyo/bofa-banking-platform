package com.bofa.authservice.service;

import com.bofa.authservice.model.AuthException;
import com.bofa.authservice.model.TokenClaims;
import com.bofa.authservice.model.TokenResponse;
import com.bofa.authservice.model.UserRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final UserDirectory userDirectory;
    private final JwtCodec jwtCodec;
    private final String issuer;
    private final String audience;
    private final long ttlSeconds;
    private final Clock clock;
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();

    public AuthService(UserDirectory userDirectory,
                       JwtCodec jwtCodec,
                       @Value("${auth.jwt.issuer}") String issuer,
                       @Value("${auth.jwt.audience}") String audience,
                       @Value("${auth.jwt.ttl-seconds}") long ttlSeconds) {
        this(userDirectory, jwtCodec, issuer, audience, ttlSeconds, Clock.systemUTC());
    }

    public AuthService(UserDirectory userDirectory, JwtCodec jwtCodec, String issuer, String audience,
                       long ttlSeconds, Clock clock) {
        this.userDirectory = userDirectory;
        this.jwtCodec = jwtCodec;
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    public TokenResponse login(String username, String password) {
        UserRecord user = userDirectory.find(username)
                .orElseThrow(() -> new AuthException("INVALID_CREDENTIALS", "Invalid username or password"));

        if (user.isLocked() || failedAttempts.getOrDefault(username, 0) >= MAX_FAILED_ATTEMPTS) {
            throw new AuthException("ACCOUNT_LOCKED", "Account is locked");
        }
        if (!userDirectory.passwordMatches(user, password)) {
            failedAttempts.merge(username, 1, Integer::sum);
            throw new AuthException("INVALID_CREDENTIALS", "Invalid username or password");
        }
        failedAttempts.remove(username);

        long now = clock.instant().getEpochSecond();
        TokenClaims claims = new TokenClaims(username, issuer, audience, now, now + ttlSeconds, user.getScopes());
        return new TokenResponse(jwtCodec.encode(claims), ttlSeconds);
    }

    /**
     * Verifies a raw Authorization header value and returns the claims.
     */
    public TokenClaims verify(String authorizationHeader) {
        if (authorizationHeader == null) {
            throw new AuthException("TOKEN_MISSING", "Authorization header is required");
        }
        String token = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring("Bearer ".length())
                : authorizationHeader;
        return jwtCodec.decode(token.trim());
    }

    public TokenClaims requireScope(String authorizationHeader, String scope) {
        TokenClaims claims = verify(authorizationHeader);
        if (!claims.hasScope(scope) && !claims.hasScope("admin")) {
            throw new AuthException("INSUFFICIENT_SCOPE", "Token lacks required scope " + scope);
        }
        return claims;
    }
}
