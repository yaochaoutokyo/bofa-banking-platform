# Demo script — "Exam-ready in an afternoon"

**Audience:** banking technology and risk executives.
**Runtime:** ~25 minutes live (plus a 10-minute variant noted inline).
**Story:** an OCC examination is eight weeks out; the controls the examiners
will probe live in the *least*-tested services. Devin turns coverage debt on
compliance-critical code into reviewed, CI-gated, auditable pull requests.

Before the session: `git checkout main`, open the README in the browser, have
the Devin session view and the GitHub PR list side by side. Make sure
`python3 scripts/coverage-summary.py --json` runs (no network needed).

---

## 0:00 — Set the scene (3 min)

Open [`README.md`](README.md). Point at three things, in this order:

1. **Badge row.** Three grey *tests not configured* badges — `payments-gateway`,
   `pii-vault-service`, `kyc-service`. Then the red ones: `transaction-service`
   16%, `auth-service` 19%, `audit-logging-service` 26%.
2. **Current Test Coverage table.** Average 33%, line-weighted 23%. Only 5 of
   12 services have a CI test job. Ask: *"Which column would the examiner read
   first?"* — it's "CI test job", because it is the evidence of a *control*, not
   a number.
3. **Compliance Readiness table.** Four control areas, four red rows. Each is
   mapped to the regulation in [`COMPLIANCE.md`](COMPLIANCE.md). Say the
   sentence: *"Today, the code that moves money, authenticates users, protects
   PII and produces the audit trail has the least automated assurance in the
   estate."*

Talking point: this is not a code-quality problem, it's a **findings** problem.
Every red row is a potential MRA (Matter Requiring Attention).

## 0:03 — Bootstrap a service with no tests at all (7 min)

Pick `pii-vault-service` (GLBA / PCI) or `kyc-service` (BSA/AML). Show the
service directory: real business logic, no `tests/`, no test runner in
`pyproject.toml` / `package.json`. Show the README section that says so.

**Devin prompt (pii-vault-service):**

> In `services/pii-vault-service` there is no test infrastructure. Add
> pytest + pytest-cov configured like `services/notification-service`
> (pyproject `[tool.pytest.ini_options]`, `[tool.coverage.run]`, a `test`
> extra, `coverage.xml` output). Write tests for `app/services/validators.py`,
> `app/services/masking.py` and `app/services/vault.py` covering: null/empty
> and oversized inputs, invalid SSN and card numbers (Luhn), masking
> correctness for every PiiType (only the permitted characters survive),
> detokenization access-policy enforcement for every seeded service policy,
> token owner scoping, and detokenization quotas. Add a
> `pii-vault-service` job to `.github/workflows/ci.yml` mirroring
> `notification-service`, and run `python3 scripts/coverage-summary.py --write`.
> Open a PR; do not change production code — if a test reveals a defect, keep
> the failing test, mark it `xfail(strict=True)` with the reason, and describe
> the defect in the PR.

**Devin prompt (kyc-service alternative):**

> `services/kyc-service` has no test framework. Add Jest + ts-jest following
> the conventions in `services/account-service` (jest.config.js with
> coverage thresholds, a `tests/helpers` harness with a frozen clock,
> `it.each` tables). Cover `validation/identity.ts` (minor / future /
> leap-day DOBs, SSN vs ITIN, PO Box rejection, expiry boundary day),
> `services/sanctions.ts` (fuzzy matching, DOB tolerance) and
> `services/cipService.ts` decisioning (PROHIBITED / HIGH / MEDIUM / LOW,
> beneficial-owner 25% threshold, ownership > 100%). Add a CI job and refresh
> the README scoreboard. Open a PR.

While Devin works, narrate what it is doing in the session view: reading the
golden reference, copying the harness pattern, running the suite locally,
iterating on failures. Emphasise: *it followed our conventions because we
pointed it at them.*

**Expected outcome:** one of the grey badges becomes green; the "PII handling"
row moves from 0% to well above target.

**What the tests will find.** Both services carry defects that only these
tests reach. Don't pre-announce them — let the PR description surface them.
For `pii-vault-service`, look at the masking tests (which characters survive
masking for each type?) and the detokenization-policy tests (is the policy
check applied uniformly for every calling service?). For `kyc-service`, look
at the entity-screening path.

## 0:10 — Raise transaction-service to 80% and surface the seeded defect (8 min)

Show [`services/transaction-service/README.md`](services/transaction-service/README.md):
16% coverage, happy-path-only JUnit tests, a documented list of unverified
edge cases (negative / zero / overflow amounts, currency mismatch,
idempotency-key reuse, rollback on partial failure).

**Devin prompt:**

> Raise `services/transaction-service` line coverage to at least 80% with
> JUnit 5 tests, prioritising error handling and validation in
> `TransactionService`, `CurrencyConverter`, `BatchTransferService`,
> `DisputeService` and `LimitPolicyService`. Specifically test: negative, zero and
> `BigDecimal` overflow amounts; currency mismatch between accounts; minor-unit
> rounding on FX conversion (compare against `BigDecimal` with
> `RoundingMode.HALF_EVEN`); idempotency-key reuse with a *different* payload;
> and rollback when the destination credit fails after the source debit.
> Do not modify production code. Where a test exposes a defect, keep the test,
> annotate it with `@Disabled("DEFECT: <one line>")`, and list every defect in
> the PR description with the regulation it affects (see COMPLIANCE.md). Add a
> `transaction-service` CI job, then run `python3 scripts/coverage-summary.py
> --write`.

**What to expect in the PR description** (do not read this aloud; let the
audience read it):

* A transfer with a **negative** or **zero** amount is accepted — the
  validation never checks the sign. A negative transfer is a reverse
  transfer with no authorisation. *(Reg E / SOX 404 transaction integrity)*
* FX conversion uses `double` arithmetic and **truncates** minor units instead
  of banker's rounding — off-by-one-cent errors that compound across a batch.
  *(SOX 404 completeness & accuracy)*
* Re-using an idempotency key with a **different amount** returns the original
  result instead of rejecting — a replay with a changed payload is silently
  accepted. *(Payments integrity)*
* When the destination credit fails, the source debit is **not rolled back**
  and the exception in the rollback path is swallowed. *(OCC operational
  risk)*

Talking point: **none of these are visible in the diff of the code that was
written.** They are visible only in the diff of the *tests*. That's what the
coverage number was hiding.

*10-minute variant:* skip section 0:03 and run only this section.

## 0:18 — Show the PR, CI going green, and the number moving (4 min)

Open the PR. Walk the checklist from
[`.github/pull_request_template.md`](.github/pull_request_template.md): before /
after coverage table filled in, edge-case checklist ticked, defects listed
with regulation mapping, "reviewed by risk controls" box.

Show the CI run: the new `transaction-service` job appears in the matrix; the
`CI gate` job is the required status check; the job summary contains the
per-service coverage table written from `coverage-summary.py`.

Show the README diff in the PR: badge from red 16% → green 80%+, table row
status from 🔴 to 🟢, Compliance Readiness "Transaction processing" row
changes.

Then **request changes** on something small (a test name, a missing edge
case). Devin responds, pushes a commit, CI reruns. This is the human-in-the-loop
beat; don't skip it.

Merge.

## 0:22 — Close on the updated scoreboard (3 min)

Pull `main`, rerun `python3 scripts/coverage-summary.py --write`, reload the
README. Compare with the opening screenshot:

| | Before | After (typical) |
|---|---|---|
| Services with tests configured | 9 / 12 | 10–11 / 12 |
| Services with a CI test job | 5 / 12 | 7 / 12 |
| Average coverage | 33% | 45–50% |
| Compliance-critical paths at target | 0 / 4 | 1–2 / 4 |
| Defects found in compliance-critical code | 0 | 4–6, each with a failing test as evidence |

Close with the compliance table: every row that moved is an audit finding
that no longer needs to be explained to an examiner — and the PRs are the
evidence package.

---

## Enterprise trust points to hit

* **Human-in-the-loop.** Devin opens PRs; humans review and merge. Branch
  protection requires the `CI gate` check and a CODEOWNERS review from the
  risk-controls team for compliance-critical paths.
* **CI gating.** Nothing merges with failing tests. Coverage thresholds in
  `.codecov.yml` make regressions visible on every PR.
* **Auditability.** Every change is a PR with a linked Devin session, a
  description of what was tested and why, and the defects found. That is the
  artefact an examiner asks for.
* **No production code changed without a human decision.** The prompts above
  instruct Devin to *report* defects, not fix them. Fixes are a separate,
  reviewed PR — with the failing test already in place as the acceptance
  criterion.
* **Conventions, not improvisation.** Devin is pointed at
  `services/account-service` and `services/notification-service` as the house
  style, so the tests it writes look like the tests the team writes.

## Suggested follow-up prompts (for Q&A)

* "Add tests to `services/auth-service` for expired tokens, malformed
  headers, `alg: none`, wrong issuer/audience and missing scopes. Report
  defects; do not fix."
* "Add CI jobs for every service that now has a test runner and update the
  README coverage table."
* "Fix the defects listed in PR #N in `transaction-service`; the disabled
  tests must pass and remain enabled."
* "Bootstrap tests for `services/payments-gateway` following
  `services/ledger-service`, covering ACH cutoff, wire limits, retry policy,
  and PAN handling."
