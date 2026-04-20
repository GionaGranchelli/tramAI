# Set variables
export BASE_URL="http://localhost:8080"
export WORKFLOW_ID="wf-1044"

# Health check
#curl -s "$BASE_URL/"
#
## Raw summary (POST /invoice/summary)
#curl -s -X POST "$BASE_URL/invoice/summary" \
#  -H 'Content-Type: application/json' \
#  --data-binary @- <<'JSON'
#{
#  "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus:\n12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
#}
#JSON
#
## Typed triage - overdue example (POST /invoice/triage). Pipe to jq if you want pretty output:
#curl -s -X POST "$BASE_URL/invoice/triage" \
#  -H 'Content-Type: application/json' \
#  --data-binary @- <<'JSON' | jq
#{
#  "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus:\n12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
#}
#JSON
#
## Typed triage - disputed example
#curl -s -X POST "$BASE_URL/invoice/triage" \
#  -H 'Content-Type: application/json' \
#  --data-binary @- <<'JSON' | jq
#{
#  "invoiceText": "Supplier: Blue Harbor Logistics\nInvoice number: BH-7781\nAmount: 1299 EUR\nDue date: 2026-05-12\nThe buyer says the shipment was incomplete and payment is on hold until the vendor issues a corrected invoice."
#}
#JSON
#
## Typed triage - current example
#curl -s -X POST "$BASE_URL/invoice/triage" \
#  -H 'Content-Type: application/json' \
#  --data-binary @- <<'JSON' | jq
#{
#  "invoiceText": "Vendor: Acme Office Supplies\nInvoice: AC-2201\nAmount due: 210 USD\nDue date: 2026-05-28\nThe invoice is approved and scheduled for payment in the normal weekly run."
#}
#JSON
#
## Typed triage using a local request file (use the sample JSON file at examples/kotlin-springboot-example/sample-invoice.json)
#curl -s -X POST "$BASE_URL/invoice/triage" \
#  -H 'Content-Type: application/json' \
#  --data-binary @examples/kotlin-springboot-example/sample-invoice.json | jq
#
## Streaming summary (non-buffered). -N disables buffering so you can see streaming output in real time.
#curl -s -N -X POST "$BASE_URL/invoice/summary/stream" \
#  -H 'Content-Type: application/json' \
#  --data-binary @- <<'JSON'
#{
#  "invoiceText": "Vendor: ACME\nInvoice: INV-999\nAmount: 500 USD\nStatus: Pending approval."
#}
#JSON
#
## Tool calling - vendor enrichment (POST /invoice/enrich)
#curl -s -X POST "$BASE_URL/invoice/enrich" \
#  -H 'Content-Type: application/json' \
#  --data-binary @- <<'JSON'
#{
#  "invoiceText": "Vendor: Acme\nInvoice: INV-123\nAmount: 1200.00 USD\nPlease verify terms."
#}
#JSON

# Workflow run with persisted checkpoint (POST /invoice/workflow)
# Uses the WORKFLOW_ID environment variable; this heredoc will expand $WORKFLOW_ID.
#curl -s -X POST "$BASE_URL/invoice/workflow" \
#  -H 'Content-Type: application/json' \
#  --data-binary @- <<JSON
#{
#  "workflowId": "$WORKFLOW_ID",
#  "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
#}
#JSON | jq

# Workflow checkpoint inspection (GET /invoice/workflow/checkpoint/{workflowId})
curl -s "$BASE_URL/invoice/workflow/checkpoint/$WORKFLOW_ID" | jq

# Workflow resume (POST /invoice/workflow/resume/{workflowId})
curl -s -X POST "$BASE_URL/invoice/workflow/resume/$WORKFLOW_ID"