package com.bofa.paymentsgateway.controller;

import com.bofa.paymentsgateway.model.Payment;
import com.bofa.paymentsgateway.model.PaymentException;
import com.bofa.paymentsgateway.model.PaymentRequest;
import com.bofa.paymentsgateway.service.PaymentRouter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentsController {

    private final PaymentRouter paymentRouter;

    public PaymentsController(PaymentRouter paymentRouter) {
        this.paymentRouter = paymentRouter;
    }

    @PostMapping
    public ResponseEntity<Payment> submit(@Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(paymentRouter.submit(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> get(@PathVariable String id) {
        return paymentRouter.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/pending")
    public List<Payment> pending() {
        return paymentRouter.pending();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Payment> retry(@PathVariable String id) {
        return paymentRouter.get(id).map(p -> {
            paymentRouter.dispatch(p);
            return ResponseEntity.ok(p);
        }).orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<Map<String, String>> handle(PaymentException ex) {
        return ResponseEntity.badRequest().body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
}
