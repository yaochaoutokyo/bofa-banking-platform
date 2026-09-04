package com.bofa.authservice.controller;

import com.bofa.authservice.model.AuthException;
import com.bofa.authservice.service.MfaService;
import com.bofa.authservice.service.PasswordPolicy;
import com.bofa.authservice.service.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class CredentialController {

    private final RefreshTokenService refreshTokenService;
    private final PasswordPolicy passwordPolicy;
    private final MfaService mfaService;

    public CredentialController(RefreshTokenService refreshTokenService, PasswordPolicy passwordPolicy,
                                MfaService mfaService) {
        this.refreshTokenService = refreshTokenService;
        this.passwordPolicy = passwordPolicy;
        this.mfaService = mfaService;
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record PasswordCheckRequest(String username, String password) {
    }

    public record MfaRequest(String username, String code) {
    }

    @PostMapping("/refresh")
    public RefreshTokenService.RefreshResult refresh(@RequestBody RefreshRequest request) {
        return refreshTokenService.rotate(request.refreshToken());
    }

    @PostMapping("/password/check")
    public Map<String, Object> checkPassword(@RequestBody PasswordCheckRequest request) {
        List<String> violations = passwordPolicy.violations(request.password(), request.username());
        return Map.of("acceptable", violations.isEmpty(), "violations", violations);
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<Void> verifyMfa(@RequestBody MfaRequest request) {
        mfaService.verify(request.username(), request.code());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handle(AuthException ex) {
        HttpStatus status = ex.getCode().startsWith("REFRESH_") || ex.getCode().startsWith("MFA_")
                ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
