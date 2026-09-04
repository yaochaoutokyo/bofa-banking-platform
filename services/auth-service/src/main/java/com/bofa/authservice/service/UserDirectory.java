package com.bofa.authservice.service;

import com.bofa.authservice.model.UserRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory user directory. Stands in for the enterprise LDAP / IAM provider.
 */
@Component
public class UserDirectory {

    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();

    public UserDirectory() {
        add("jdoe", "Correct-Horse-7", List.of("accounts:read", "transactions:write"), false);
        add("ops.admin", "Battery-Staple-9", List.of("accounts:read", "accounts:write", "audit:read", "admin"), false);
        add("auditor", "Read-Only-2024", List.of("audit:read"), false);
        add("locked.user", "Locked-Out-1", List.of("accounts:read"), true);
    }

    public void add(String username, String password, List<String> scopes, boolean locked) {
        users.put(username, new UserRecord(username, hash(password), scopes, locked));
    }

    public Optional<UserRecord> find(String username) {
        return Optional.ofNullable(users.get(username));
    }

    public boolean passwordMatches(UserRecord user, String password) {
        return MessageDigest.isEqual(
                user.getPasswordHash().getBytes(StandardCharsets.UTF_8),
                hash(password).getBytes(StandardCharsets.UTF_8));
    }

    static String hash(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
