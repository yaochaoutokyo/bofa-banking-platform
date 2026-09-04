package com.bofa.authservice.controller;

import com.bofa.authservice.model.AuthException;
import com.bofa.authservice.model.LoginRequest;
import com.bofa.authservice.model.TokenClaims;
import com.bofa.authservice.model.TokenResponse;
import com.bofa.authservice.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.getUsername(), request.getPassword());
    }

    @GetMapping("/introspect")
    public TokenClaims introspect(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return authService.verify(authorization);
    }

    @GetMapping("/authorize")
    public Map<String, Object> authorize(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam String scope) {
        TokenClaims claims = authService.requireScope(authorization, scope);
        return Map.of("subject", claims.getSubject(), "scope", scope, "granted", true);
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handleAuthException(AuthException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "INVALID_CREDENTIALS", "TOKEN_MISSING", "TOKEN_EXPIRED", "TOKEN_SIGNATURE_INVALID",
                 "TOKEN_ISSUER_INVALID", "TOKEN_AUDIENCE_INVALID" -> HttpStatus.UNAUTHORIZED;
            case "ACCOUNT_LOCKED", "INSUFFICIENT_SCOPE" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
