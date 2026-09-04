package com.bofa.authservice.model;

import java.util.List;

public class TokenClaims {

    private final String subject;
    private final String issuer;
    private final String audience;
    private final long issuedAt;
    private final long expiresAt;
    private final List<String> scopes;

    public TokenClaims(String subject, String issuer, String audience, long issuedAt, long expiresAt,
                       List<String> scopes) {
        this.subject = subject;
        this.issuer = issuer;
        this.audience = audience;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.scopes = scopes;
    }

    public String getSubject() {
        return subject;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getAudience() {
        return audience;
    }

    public long getIssuedAt() {
        return issuedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }
}
