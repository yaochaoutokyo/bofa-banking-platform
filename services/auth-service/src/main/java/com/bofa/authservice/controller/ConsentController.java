package com.bofa.authservice.controller;

import com.bofa.authservice.model.AuthException;
import com.bofa.authservice.service.ConsentService;
import com.bofa.authservice.service.DeviceTrustService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
public class ConsentController {

    private final ConsentService consentService;
    private final DeviceTrustService deviceTrustService;

    public ConsentController(ConsentService consentService, DeviceTrustService deviceTrustService) {
        this.consentService = consentService;
        this.deviceTrustService = deviceTrustService;
    }

    public record GrantRequest(String customerId, String aggregatorId, Set<String> scopes, Duration duration) {
    }

    public record AssessRequest(String username, DeviceTrustService.LoginContext context) {
    }

    @PostMapping("/consents")
    public ResponseEntity<ConsentService.Consent> grant(@RequestBody GrantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(consentService.grant(
                request.customerId(), request.aggregatorId(), request.scopes(), request.duration()));
    }

    @PostMapping("/consents/{id}/access")
    public ConsentService.Consent access(@PathVariable String id, @RequestParam String aggregatorId,
                                         @RequestParam String scope) {
        return consentService.authorizeAccess(id, aggregatorId, scope);
    }

    @DeleteMapping("/consents/{id}")
    public ConsentService.Consent revoke(@PathVariable String id, @RequestParam String customerId) {
        return consentService.revoke(id, customerId);
    }

    @GetMapping("/consents")
    public List<ConsentService.Consent> active(@RequestParam String customerId) {
        return consentService.activeFor(customerId);
    }

    @PostMapping("/risk/assess")
    public DeviceTrustService.Assessment assess(@RequestBody AssessRequest request) {
        return deviceTrustService.assess(request.username(), request.context());
    }

    @DeleteMapping("/risk/devices")
    public Map<String, Integer> forgetDevices(@RequestParam String username) {
        return Map.of("removed", deviceTrustService.forgetDevices(username));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handle(AuthException ex) {
        HttpStatus status = ex.getCode().endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
