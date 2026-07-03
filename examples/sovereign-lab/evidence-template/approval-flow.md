# Approval Flow Proof

## Claim Submission

```bash
curl -X POST http://localhost:8080/tramai/examples/regulated-claim-triage \
  -H 'Content-Type: application/json' \
  -d '{"claimId":"evidence-claim-1","riskLevel":"HIGH","amount":5000}'
```

**Response:**

```
Paste response here (should show SuspendedForApproval).
```

## Approval Inbox

```bash
curl http://localhost:8080/tramai/sovereign/approvals
```

**Response:**

```
Paste inbox response here (should show one pending approval).
```

## Approval Decision

```bash
# Approve:
curl -X POST http://localhost:8080/tramai/sovereign/approvals/{id}/approve \
  -H 'Content-Type: application/json' \
  -d '{"decisionBy":"lab-reviewer","reason":"approved in evidence capture"}'

# Or deny:
curl -X POST http://localhost:8080/tramai/sovereign/approvals/{id}/deny \
  -H 'Content-Type: application/json' \
  -d '{"decisionBy":"lab-reviewer","reason":"denied in evidence capture"}'
```

**Response:**

```
Paste decision response here (should show 200 OK).
```
