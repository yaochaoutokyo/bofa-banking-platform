package com.bofa.authservice.service;

import com.bofa.authservice.model.AuthException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Time-based one-time passwords (RFC 6238) with a small replay window and a
 * per-user attempt counter.
 */
@Service
public class MfaService {

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    static final int MAX_ATTEMPTS = 3;

    private final Clock clock;
    private final Map<String, String> enrolledSecrets = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAcceptedStep = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    public MfaService() {
        this(Clock.systemUTC());
    }

    public MfaService(Clock clock) {
        this.clock = clock;
    }

    public void enroll(String username, String sharedSecret) {
        if (sharedSecret == null || sharedSecret.length() < 16) {
            throw new AuthException("MFA_WEAK_SECRET", "Shared secret must be at least 16 characters");
        }
        enrolledSecrets.put(username, sharedSecret);
    }

    public boolean isEnrolled(String username) {
        return enrolledSecrets.containsKey(username);
    }

    public void verify(String username, String code) {
        String secret = enrolledSecrets.get(username);
        if (secret == null) {
            throw new AuthException("MFA_NOT_ENROLLED", "User has no MFA device");
        }
        if (attempts.getOrDefault(username, 0) >= MAX_ATTEMPTS) {
            throw new AuthException("MFA_LOCKED", "Too many MFA attempts");
        }
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(Character::isDigit)) {
            attempts.merge(username, 1, Integer::sum);
            throw new AuthException("MFA_INVALID", "Code must be " + DIGITS + " digits");
        }

        long step = clock.instant().getEpochSecond() / STEP_SECONDS;
        for (long candidate = step - 1; candidate <= step + 1; candidate++) {
            if (generate(secret, candidate).equals(code)) {
                Long last = lastAcceptedStep.get(username);
                if (last != null && last >= candidate) {
                    throw new AuthException("MFA_REPLAY", "Code already used");
                }
                lastAcceptedStep.put(username, candidate);
                attempts.remove(username);
                return;
            }
        }
        attempts.merge(username, 1, Integer::sum);
        throw new AuthException("MFA_INVALID", "Incorrect code");
    }

    String generate(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
