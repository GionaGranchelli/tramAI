# Restart Durability Proof

## Before Restart

### Approval Inbox

```bash
curl http://localhost:8080/tramai/sovereign/approvals
```

**Response:**

```
Paste inbox here (should show the existing approval record).
```

## Restart

1. Stop the app (`Ctrl+C`)
2. Start again:

```bash
./gradlew :examples:spring-sovereign-starter:bootRun \
  --args='--spring.profiles.active=sovereign-lab'
```

## After Restart

### Approval Inbox

```bash
curl http://localhost:8080/tramai/sovereign/approvals
```

**Response:**

```
Paste inbox here (should show the same approval record — JDBC persistence active).
```
