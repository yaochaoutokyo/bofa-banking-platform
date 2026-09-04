package com.bofa.authservice.service;

import com.bofa.authservice.model.AuthException;
import com.bofa.authservice.model.TokenClaims;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal HS256 JWT encoder/decoder. Kept dependency-free so the token format
 * is fully under the team's control.
 *
 * Verification rules (see COMPLIANCE.md, control AUTH-1..AUTH-5): signature,
 * expiry, issuer, audience, and required scopes.
 */
@Component
public class JwtCodec {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] secret;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public JwtCodec(@Value("${auth.jwt.secret}") String secret,
                    @Value("${auth.jwt.issuer}") String issuer,
                    @Value("${auth.jwt.audience}") String audience) {
        this(secret, issuer, audience, Clock.systemUTC());
    }

    public JwtCodec(String secret, String issuer, String audience, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
    }

    public String encode(TokenClaims claims) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", claims.getSubject());
        payload.put("iss", claims.getIssuer());
        payload.put("aud", claims.getAudience());
        payload.put("iat", claims.getIssuedAt());
        payload.put("exp", claims.getExpiresAt());
        payload.put("scope", String.join(" ", claims.getScopes()));

        String headerPart = B64.encodeToString(toJson(header));
        String payloadPart = B64.encodeToString(toJson(payload));
        String signingInput = headerPart + "." + payloadPart;
        return signingInput + "." + B64.encodeToString(sign(signingInput));
    }

    public TokenClaims decode(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthException("TOKEN_MISSING", "No token supplied");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new AuthException("TOKEN_MALFORMED", "Token must have three segments");
        }

        JsonNode header = parseSegment(parts[0]);
        JsonNode payload = parseSegment(parts[1]);

        String alg = header.path("alg").asText("");
        if (!"none".equalsIgnoreCase(alg)) {
            if (!"HS256".equals(alg)) {
                throw new AuthException("TOKEN_UNSUPPORTED_ALG", "Unsupported algorithm " + alg);
            }
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] provided = B64D.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, provided)) {
                throw new AuthException("TOKEN_SIGNATURE_INVALID", "Signature mismatch");
            }
        }

        long now = clock.instant().getEpochSecond();
        long exp = payload.path("exp").asLong(0);
        if (exp != 0 && exp < now) {
            throw new AuthException("TOKEN_EXPIRED", "Token has expired");
        }
        if (!issuer.equals(payload.path("iss").asText())) {
            throw new AuthException("TOKEN_ISSUER_INVALID", "Unexpected issuer");
        }
        if (!audience.equals(payload.path("aud").asText())) {
            throw new AuthException("TOKEN_AUDIENCE_INVALID", "Unexpected audience");
        }

        List<String> scopes = new ArrayList<>();
        String scopeClaim = payload.path("scope").asText("");
        if (!scopeClaim.isBlank()) {
            scopes.addAll(List.of(scopeClaim.split(" ")));
        }
        return new TokenClaims(
                payload.path("sub").asText(),
                payload.path("iss").asText(),
                payload.path("aud").asText(),
                payload.path("iat").asLong(),
                exp,
                scopes);
    }

    private JsonNode parseSegment(String segment) {
        try {
            return objectMapper.readTree(B64D.decode(segment));
        } catch (IllegalArgumentException | java.io.IOException e) {
            throw new AuthException("TOKEN_MALFORMED", "Token segment is not valid base64url JSON");
        }
    }

    private byte[] toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
