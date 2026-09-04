package com.bofa.authservice.service;

import com.bofa.authservice.model.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final String ISSUER = "https://auth.bofa.internal";
    private static final String AUDIENCE = "bofa-platform";

    private AuthService authService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2025-01-15T12:00:00Z"), ZoneOffset.UTC);
        JwtCodec codec = new JwtCodec(SECRET, ISSUER, AUDIENCE, clock);
        authService = new AuthService(new UserDirectory(), codec, ISSUER, AUDIENCE, 900, clock);
    }

    @Test
    void loginIssuesBearerToken() {
        TokenResponse response = authService.login("jdoe", "Correct-Horse-7");

        assertEquals("Bearer", response.getTokenType());
        assertEquals(900, response.getExpiresIn());
        assertEquals(3, response.getAccessToken().split("\\.").length);
    }

}
