package com.bofa.authservice.model;

import java.util.List;

public class UserRecord {

    private final String username;
    private final String passwordHash;
    private final List<String> scopes;
    private final boolean locked;

    public UserRecord(String username, String passwordHash, List<String> scopes, boolean locked) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.scopes = scopes;
        this.locked = locked;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public boolean isLocked() {
        return locked;
    }
}
