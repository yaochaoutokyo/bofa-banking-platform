## Summary

<!-- What changed and why. Link the Jira ticket / audit finding if applicable. -->

## Services touched

- [ ] transaction-service
- [ ] auth-service
- [ ] payments-gateway
- [ ] ledger-service
- [ ] pii-vault-service
- [ ] audit-logging-service
- [ ] fraud-detection-service
- [ ] notification-service
- [ ] customer-portal-api
- [ ] account-service
- [ ] statement-service
- [ ] kyc-service

## Test coverage checklist

- [ ] Every touched service has a configured test runner (`mvn test` / `pytest` / `npm test`)
- [ ] Happy-path tests exist for every endpoint or public method changed
- [ ] **Error-handling and validation branches are tested** (negative/zero/overflow inputs, malformed payloads, missing fields, auth failures)
- [ ] Coverage for each touched service did not decrease (paste before/after below)
- [ ] Tests are deterministic (no network, no real clock, no shared state)
- [ ] New tests follow the conventions in `services/account-service` (see CONTRIBUTING.md)

| Service | Coverage before | Coverage after |
|---|---|---|
|         |                 |                |

## Compliance impact

<!-- If this PR touches a compliance-critical service, name the control(s) from COMPLIANCE.md it supports. -->

- [ ] Not compliance-critical
- [ ] Compliance-critical — controls affected: <!-- e.g. SOX 404 ITGC, PCI-DSS 6.3, GLBA 501(b) -->
- [ ] Reviewed by `@bofa-platform/risk-controls`

## Defects surfaced

<!-- If new tests exposed an existing bug, describe it and link the fix or follow-up. -->
