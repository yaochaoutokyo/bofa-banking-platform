# auth-service

Issues and verifies HS256 JWT access tokens for the customer portal and
internal service-to-service calls. Includes account lockout after repeated
failures and scope-based authorization.

**Compliance tier:** critical (FFIEC Authentication Guidance, SOX 404 ITGC
logical access, PCI-DSS Req. 8).

## Run

```bash
mvn spring-boot:run          # http://localhost:8082
mvn test                     # JUnit 5 + JaCoCo -> target/site/jacoco/index.html
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/auth/login` | Exchange credentials for a bearer token |
| GET | `/api/v1/auth/introspect` | Verify a token and return its claims |
| GET | `/api/v1/auth/authorize?scope=` | Verify a token and require a scope |

## Test status

`AuthServiceTest` covers login and a successful round-trip only. Token
rejection paths in `JwtCodec.decode` (expiry, malformed segments, unsupported
or missing algorithm, wrong issuer/audience), lockout, and scope enforcement
have no tests.
