# JDBC Persistence Proof

## Database Configuration

| Setting | Value |
|---------|-------|
| Database | |
| Host | |
| Port | |
| Database name | |
| Username | |
| Driver | PostgreSQL 16 |

## Connection Verification

```bash
# Verify the application connected to PostgreSQL successfully
# Look for these log lines during boot:
# - "sovereign-lab" profile activated
# - Connected to PostgreSQL at ...
# - Flyway migrations applied
```

## Migration Log

| Migration | Applied | Version |
|-----------|---------|---------|
| V1__initial_schema | | |
| V2__approval_tables | | |
| V3__continuation_store | | |
| V4__suspended_invocations | | |
| V5__approval_audit_outbox | | |
| V6__approval_resume_credential_custody | | |
| V7__approved_continuation_resume | | |

## Persistence Verification

### Before Restart

Verify approval records exist in the database:

```bash
# Via inbox
curl http://localhost:8080/tramai/sovereign/approvals
```

**Response:**

```

```

### After Restart

Stop the app, start again, and recheck:

```bash
./gradlew :examples:spring-sovereign-starter:bootRun \
  --args='--spring.profiles.active=sovereign-lab'

curl http://localhost:8080/tramai/sovereign/approvals
```

**Response (should match before-restart state):**

```

```

## Notes

Document any migration warnings, connection issues, schema drift, or rollbacks encountered.
