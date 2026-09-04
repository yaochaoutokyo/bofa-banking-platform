package com.bofa.authservice.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Enterprise password policy (FFIEC / NIST 800-63B aligned).
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private static final Set<String> BREACHED = Set.of(
            "password1234", "welcome12345", "bankofamerica", "qwerty123456", "letmein12345");

    public List<String> violations(String password, String username) {
        List<String> problems = new ArrayList<>();
        if (password == null) {
            problems.add("Password is required");
            return problems;
        }
        if (password.length() < MIN_LENGTH) {
            problems.add("Must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            problems.add("Must be at most " + MAX_LENGTH + " characters");
        }
        if (!password.chars().anyMatch(Character::isUpperCase)) {
            problems.add("Must contain an uppercase letter");
        }
        if (!password.chars().anyMatch(Character::isLowerCase)) {
            problems.add("Must contain a lowercase letter");
        }
        if (!password.chars().anyMatch(Character::isDigit)) {
            problems.add("Must contain a digit");
        }
        if (password.chars().allMatch(Character::isLetterOrDigit)) {
            problems.add("Must contain a symbol");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && !username.isBlank() && lower.contains(username.toLowerCase(Locale.ROOT))) {
            problems.add("Must not contain the username");
        }
        if (BREACHED.contains(lower)) {
            problems.add("Password appears in a known breach corpus");
        }
        if (hasSequentialRun(password, 4)) {
            problems.add("Must not contain 4+ sequential characters");
        }
        return problems;
    }

    public boolean isAcceptable(String password, String username) {
        return violations(password, username).isEmpty();
    }

    static boolean hasSequentialRun(String s, int runLength) {
        int run = 1;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == s.charAt(i - 1) + 1) {
                run++;
                if (run >= runLength) {
                    return true;
                }
            } else {
                run = 1;
            }
        }
        return false;
    }
}
