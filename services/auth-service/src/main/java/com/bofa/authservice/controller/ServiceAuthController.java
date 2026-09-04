package com.bofa.authservice.controller;

import com.bofa.authservice.model.AuthException;
import com.bofa.authservice.service.ApiKeyService;
import com.bofa.authservice.service.SessionRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
public class ServiceAuthController {

    private final ApiKeyService apiKeyService;
    private final SessionRegistry sessionRegistry;

    public ServiceAuthController(ApiKeyService apiKeyService, SessionRegistry sessionRegistry) {
        this.apiKeyService = apiKeyService;
        this.sessionRegistry = sessionRegistry;
    }

    public record IssueKeyRequest(String serviceName, Set<String> scopes, Instant expiresAt) {
    }

    public record OpenSessionRequest(String username, String ipAddress, String userAgent) {
    }

    @PostMapping("/api-keys")
    public ResponseEntity<ApiKeyService.IssuedKey> issue(@RequestBody IssueKeyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(apiKeyService.issue(request.serviceName(), request.scopes(), request.expiresAt()));
    }

    @PostMapping("/api-keys/rotate")
    public ApiKeyService.IssuedKey rotate(@RequestHeader("X-Api-Key") String apiKey,
                                          @RequestParam(defaultValue = "PT24H") Duration overlap) {
        return apiKeyService.rotate(apiKey, overlap);
    }

    @GetMapping("/api-keys/introspect")
    public ApiKeyService.ApiKeyRecord introspect(@RequestHeader("X-Api-Key") String apiKey,
                                                 @RequestParam(required = false) String scope) {
        ApiKeyService.ApiKeyRecord record = apiKeyService.authenticate(apiKey);
        if (scope != null) {
            apiKeyService.requireScope(record, scope);
        }
        return record;
    }

    @DeleteMapping("/api-keys/{serviceName}")
    public Map<String, Integer> revokeAll(@PathVariable String serviceName) {
        return Map.of("revoked", apiKeyService.revokeAll(serviceName));
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionRegistry.Session> openSession(@RequestBody OpenSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionRegistry.open(request.username(), request.ipAddress(), request.userAgent()));
    }

    @PostMapping("/sessions/{id}/touch")
    public SessionRegistry.Session touch(@PathVariable String id,
                                         @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        return sessionRegistry.touch(id, ip);
    }

    @GetMapping("/sessions")
    public List<SessionRegistry.Session> sessions(@RequestParam String username) {
        return sessionRegistry.activeFor(username);
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> close(@PathVariable String id) {
        sessionRegistry.close(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handle(AuthException ex) {
        HttpStatus status = ex.getCode().startsWith("SESSION_") || ex.getCode().startsWith("APIKEY_")
                || "INSUFFICIENT_SCOPE".equals(ex.getCode()) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
