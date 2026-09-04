# payments-gateway

Routes outbound payments to the appropriate rail (ACH, Wire, RTP, Card),
validates ABA routing numbers, applies rail fees, handles the wire cutoff
window, and retries transient rail failures.

**Compliance tier:** critical (PCI-DSS, NACHA operating rules, OCC payments
risk guidance, Reg CC).

## Run

```bash
mvn spring-boot:run          # http://localhost:8083
```

## Test status

**No test infrastructure.** This service has no test dependencies, no
`src/test` directory, no JaCoCo configuration, and no CI job. Coverage is
unmeasured (effectively 0%).

Areas that will need coverage when tests are bootstrapped:

- `PaymentRouter.selectRail` threshold boundaries ($25k wire, $100k RTP)
- `PaymentRouter.dispatch` retry exhaustion and `RETRYING` -> `REJECTED`
- wire cutoff behaviour (payments accepted after 21:00 UTC are not dispatched)
- `RoutingNumberValidator` checksum, length, and non-digit handling
- `PaymentRouter.validate` rejection codes and the controller's error mapping
