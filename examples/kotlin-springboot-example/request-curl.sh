#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WORKFLOW_ID="${WORKFLOW_ID:-wf-1044}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

print_section() {
  printf '\n== %s ==\n' "$1"
}

print_section "Health"
curl -s "$BASE_URL/" | jq

print_section "Raw Summary"
curl -s -X POST "$BASE_URL/invoice/summary" \
  -H 'Content-Type: application/json' \
  --data-binary @"$SCRIPT_DIR/sample-invoice.json" | jq

print_section "Structured Triage"
curl -s -X POST "$BASE_URL/invoice/triage" \
  -H 'Content-Type: application/json' \
  --data-binary @"$SCRIPT_DIR/sample-invoice.json" | jq

print_section "Tool Calling"
curl -s -X POST "$BASE_URL/invoice/enrich" \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON' | jq
{
  "invoiceText": "Vendor: Acme\nInvoice: INV-123\nAmount: 1200.00 USD\nPlease verify terms."
}
JSON

print_section "Workflow Run"
curl -s -X POST "$BASE_URL/invoice/workflow" \
  -H 'Content-Type: application/json' \
  --data-binary @- <<JSON | jq
{
  "workflowId": "$WORKFLOW_ID",
  "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
}
JSON

print_section "Workflow Checkpoint"
curl -s "$BASE_URL/invoice/workflow/checkpoint/$WORKFLOW_ID" | jq
