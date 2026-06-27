package dev.tramai.spring.sovereign.ops.rest

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("\${tramai.sovereign.ops.rest.base-path:/tramai/sovereign}")
class ApprovalReviewerUiController {

    @GetMapping("/reviewer")
    fun reviewerPage(): String = PAGE_HTML

    companion object {
        val PAGE_HTML: String = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<title>Reviewer UI</title>\n" +
                "<style>\n" +
                "body { font-family: sans-serif; padding: 20px; }\n" +
                ".warning { background: #fff3cd; border: 1px solid #ffc107; padding: 12px; border-radius: 6px; margin-bottom: 20px; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"warning\">Preview UI — not designed for production. Disabled by default. Deploy behind authentication.</div>\n" +
                "<h1>TramAI Sovereign Approval Reviewer — Preview</h1>\n" +
                "<div id=\"app\">Loading...</div>\n" +
                "<script>\n" +
                "const BASE = '/tramai/sovereign';\n" +
                "async function loadInbox() {\n" +
                "  const r = await fetch(BASE + '/approvals?status=PENDING&limit=50');\n" +
                "  const data = await r.json();\n" +
                "  let html = '<table><tr><th>ID</th><th>Role</th><th>Status</th></tr>';\n" +
                "  for (const item of (data.items || [])) {\n" +
                "    html += '<tr><td>' + item.approvalId + '</td><td>' + (item.requiredRole || '') + '</td><td>' + (item.status || '') + '</td></tr>';\n" +
                "  }\n" +
                "  html += '</table>';\n" +
                "  document.getElementById('app').innerHTML = html;\n" +
                "}\n" +
                "async function doApprove(id) {\n" +
                "  await fetch(BASE + '/approvals/' + id + '/approve', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({actor:'reviewer:local',reason:'Approved from preview reviewer UI',expectedVersion:0}) });\n" +
                "  loadInbox();\n" +
                "}\n" +
                "async function doDeny(id) {\n" +
                "  await fetch(BASE + '/approvals/' + id + '/deny', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({actor:'reviewer:local',reason:'Denied from preview reviewer UI',expectedVersion:0}) });\n" +
                "  loadInbox();\n" +
                "}\n" +
                "async function doResume(id) {\n" +
                "  await fetch(BASE + '/approvals/' + id + '/resume', { method: 'POST' });\n" +
                "  loadInbox();\n" +
                "}\n" +
                "loadInbox();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>"
    }
}
