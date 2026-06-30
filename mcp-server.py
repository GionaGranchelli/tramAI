#!/usr/bin/env python3
"""
MCP server for the Tramai project — structured-first AI workflow library for JVM.

Exposes design docs, ADRs, specs, task boards, and codebase search
as MCP resources and tools. Helps AI agents understand Tramai's
architecture, API surface, and design decisions before writing code.

Project: ~/Development/aurora (Tramai)
"""

import functools, json, re, subprocess
from pathlib import Path
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Resource, Tool, TextContent

TRAMAI = Path.home() / "Development" / "aurora"
DOCS = TRAMAI / "docs"

server = Server("tramai")

# ── Resources ────────────────────────────────────────────────────

_DOC_FILES = {
    "agents": TRAMAI / "AGENTS.md",
    "design": TRAMAI / "DESIGN.md",
    "plan": TRAMAI / "PLAN.md",
    "readme": TRAMAI / "README.md",
}

_DOC_DIRS: dict[str, Path] = {
    "adr": DOCS / "adr",
    "spec": DOCS / "specs",
    "architecture": DOCS / "architecture",
    "guide": DOCS / "guides",
    "release": DOCS / "releases",
    "scenario": DOCS / "scenarios",
    "security": DOCS / "security",
}


def _glob_resources(prefix: str, directory: Path, pattern: str = "*.md") -> list[Resource]:
    """Build MCP resources from markdown files in a directory."""
    if not directory.exists():
        return []
    return [
        Resource(
            uri=f"tramai://{prefix}/{f.stem}",
            name=f"{prefix}/{f.stem}",
            description=f"{prefix.capitalize()}: {f.stem}",
            mimeType="text/markdown",
        )
        for f in sorted(directory.glob(pattern))
    ]

@server.list_resources()
async def list_resources() -> list[Resource]:
    resources = []
    for name, path in _DOC_FILES.items():
        if path.exists():
            resources.append(Resource(
                uri=f"tramai://{name}",
                name=name,
                description=f"Tramai {name.upper()}",
                mimeType="text/markdown",
            ))

    for prefix, directory in _DOC_DIRS.items():
        resources.extend(_glob_resources(prefix, directory))

    return resources

@server.read_resource()
async def read_resource(uri: str) -> str:
    rel = uri.removeprefix("tramai://")
    if rel in _DOC_FILES:
        return _DOC_FILES[rel].read_text()
    for prefix, directory in _DOC_DIRS.items():
        if rel.startswith(f"{prefix}/"):
            path = directory / f"{rel.removeprefix(f'{prefix}/')}.md"
            if path.exists():
                return path.read_text()
            return f"Not found: {rel}"
    raise ValueError(f"Resource not found: {rel}")

# ── Tools ────────────────────────────────────────────────────────

@functools.cache
def _get_modules() -> list[str]:
    # Parse module list from settings.gradle.kts so it stays in sync with the repo
    settings = TRAMAI / "settings.gradle.kts"
    if not settings.exists():
        return []
    text = settings.read_text()
    # Extract only the include(...) block to skip rootProject.name
    m = re.search(r'include\s*\((.*?)\)', text, re.DOTALL)
    if not m:
        return []
    modules = re.findall(r'"([^"]+)"', m.group(1))
    return sorted(modules)

@server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="search_codebase",
            description="Search the Tramai codebase for patterns: @AiService, @Operation, @AiTool, ApprovalGateway, Sovereign, etc. Returns matches with 3 lines of surrounding context.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pattern": {
                        "type": "string",
                        "description": "Pattern to grep for (e.g., '@AiService', 'class.*Provider', 'fun create')",
                    },
                    "module": {
                        "type": "string",
                        "description": f"Optional module name: {', '.join(_get_modules())}",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max matches to return (default 30, max 100)",
                        "default": 30,
                    },
                },
                "required": ["pattern"],
            },
        ),
        Tool(
            name="list_files",
            description="List source files in a Tramai module. Shows main sources and optionally test sources.",
            inputSchema={
                "type": "object",
                "properties": {
                    "module": {
                        "type": "string",
                        "description": f"Module name from: {', '.join(_get_modules()[:8])}...",
                    },
                    "includeTests": {
                        "type": "boolean",
                        "description": "Also list test source files (default: false)",
                        "default": False,
                    },
                    "extension": {
                        "type": "string",
                        "description": "File extension filter (default: .kt)",
                        "default": ".kt",
                    },
                },
                "required": ["module"],
            },
        ),
        Tool(
            name="list_modules",
            description="List all Tramai modules with descriptions.",
            inputSchema={"type": "object", "properties": {}},
        ),
        Tool(
            name="read_source",
            description="Read a source file from a Tramai module.",
            inputSchema={
                "type": "object",
                "properties": {
                    "module": {"type": "string", "description": "Module name"},
                    "path": {"type": "string", "description": "Path relative to module src/ (e.g., 'Tramai.kt')"},
                },
                "required": ["module", "path"],
            },
        ),
        Tool(
            name="search_docs",
            description="Full-text search across ADRs, specs, and design docs.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Search term"},
                },
                "required": ["query"],
            },
        ),
        Tool(
            name="get_architecture",
            description="Return a summary of Tramai's module architecture and key design decisions.",
            inputSchema={"type": "object", "properties": {}},
        ),
    ]

@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    if name == "search_codebase":
        return await _search_codebase(
            arguments["pattern"],
            arguments.get("module"),
            arguments.get("limit", 30),
        )
    elif name == "list_modules":
        return await _list_modules()
    elif name == "list_files":
        return await _list_files(
            arguments["module"],
            arguments.get("includeTests", False),
            arguments.get("extension", ".kt"),
        )
    elif name == "read_source":
        return await _read_source(arguments["module"], arguments["path"])
    elif name == "search_docs":
        return await _search_docs(arguments["query"])
    elif name == "get_architecture":
        return await _get_architecture()
    raise ValueError(f"Unknown tool: {name}")

# ── Implementations ──────────────────────────────────────────────

def _module_path(module: str) -> Path:
    """Resolve a module name to its filesystem path, handling examples:* → examples/foo."""
    return TRAMAI / module.replace(":", "/")


async def _search_codebase(pattern: str, module: str | None, limit: int = 30) -> list[TextContent]:
    search_root = _module_path(module) if module else TRAMAI
    if not search_root.exists():
        return [TextContent(type="text", text=f"Module not found: {module}")]

    try:
        result = subprocess.run(
            ["grep", "-rn", "-C", "3", "--include=*.kt", "--include=*.java", pattern, str(search_root)],
            capture_output=True, text=True, timeout=15,
        )
        raw = result.stdout.strip()
        if not raw:
            return [TextContent(type="text", text=f"No matches for '{pattern}' in {module or 'all modules'}")]

        # Split into match groups (separated by -- lines from grep -C)
        groups = raw.split("\n--\n")
        groups = [g.strip() for g in groups if g.strip()]

        limited = groups[:min(limit, 100)]
        summary = f"Found {len(groups)} match{'es' if len(groups) != 1 else ''}"
        if len(groups) > len(limited):
            summary += f", showing {len(limited)}"

        md = [f"# {summary}\n"]
        for g in limited:
            md.append(g)
            md.append("")

        return [
            TextContent(type="text", text=json.dumps({
                "total": len(groups),
                "returned": len(limited),
                "pattern": pattern,
                "module": module,
            }, indent=2)),
            TextContent(type="text", text="\n".join(md)),
        ]
    except subprocess.TimeoutExpired:
        return [TextContent(type="text", text=f"Search timed out for '{pattern}'")]


async def _list_files(module: str, include_tests: bool = False, extension: str = ".kt") -> list[TextContent]:
    search_root = _module_path(module)
    if not search_root.exists():
        return [TextContent(type="text", text=f"Module not found: {module}")]

    roots = [search_root / "src" / "main"]
    if include_tests:
        roots.append(search_root / "src" / "test")

    files = []
    for root in roots:
        if root.exists():
            files.extend(sorted(root.rglob(f"*{extension}")))

    rels = [str(f.relative_to(search_root)) for f in files]

    summary = f"{len(rels)} {extension} files in {module}"
    if include_tests:
        summary += " (including tests)"

    md = [f"# {summary}\n"]
    for r in rels:
        md.append(f"- {r}")

    return [
        TextContent(type="text", text=json.dumps({
            "module": module,
            "total": len(rels),
            "includeTests": include_tests,
            "extension": extension,
            "files": rels,
        }, indent=2)),
        TextContent(type="text", text="\n".join(md)),
    ]

async def _list_modules() -> list[TextContent]:
    modules = []
    for mod in _get_modules():
        path = _module_path(mod)
        build_file = path / "build.gradle.kts"
        desc = ""
        if build_file.exists():
            content = build_file.read_text()
            m = re.search(r'description\s*=\s*"([^"]+)"', content)
            desc = m.group(1) if m else ""
        exists = path.exists()
        modules.append({
            "name": mod,
            "description": desc,
            "exists": exists,
        })

    md_lines = ["# Tramai Modules\n"]
    for m in modules:
        icon = "✅" if m["exists"] else "❌"
        d = f" — {m['description']}" if m["description"] else ""
        md_lines.append(f"- {icon} **{m['name']}**{d}")

    return [
        TextContent(type="text", text=json.dumps({
            "modules": modules,
            "total": len(modules),
        }, indent=2)),
        TextContent(type="text", text="\n".join(md_lines)),
    ]

async def _read_source(module: str, path: str) -> list[TextContent]:
    search_root = _module_path(module)

    for root in (
        search_root / "src" / "main" / "kotlin",
        search_root / "src" / "main" / "java",
        search_root / "src" / "test" / "kotlin",
        search_root / "src" / "test" / "java",
    ):
        file_path = root / path
        if file_path.exists():
            return [TextContent(type="text", text=file_path.read_text())]

    # Fallback: glob search under src/
    found = list((search_root / "src").rglob(path))
    if found:
        return [TextContent(type="text", text=found[0].read_text())]

    return [TextContent(type="text", text=f"File not found: {module}/{path}")]

async def _search_docs(query: str) -> list[TextContent]:
    try:
        result = subprocess.run(
            ["grep", "-rni", "-C", "2", query, str(DOCS)],
            capture_output=True, text=True, timeout=10,
        )
        raw = result.stdout.strip()
        if not raw:
            return [TextContent(type="text", text=f"No matches for '{query}' in docs")]

        groups = raw.split("\n--\n")
        groups = [g.strip() for g in groups if g.strip()]
        limited = groups[:15]
        md = [f"# Found {len(groups)} matches in docs"]
        md.append("")
        for g in limited:
            md.append(g)
            md.append("")
        return [TextContent(type="text", text="\n".join(md))]
    except subprocess.TimeoutExpired:
        return [TextContent(type="text", text="Search timed out")]

async def _get_architecture() -> list[TextContent]:
    modules = []
    for mod in _get_modules():
        path = _module_path(mod)
        if not path.exists():
            continue

        build_file = path / "build.gradle.kts"
        desc = ""
        if build_file.exists():
            m = re.search(r'description\s*=\s*"([^"]+)"', build_file.read_text())
            desc = m.group(1) if m else ""

        # Detect module category
        category = _categorize_module(mod, path)
        modules.append({
            "name": mod,
            "description": desc,
            "category": category,
        })

    # Group by category
    layers = [
        ("Core", ["tramai-core"]),
        ("Structured Output", ["tramai-structured"]),
        ("Engine", ["tramai-engine"]),
        ("Providers", [m["name"] for m in modules if m["category"] == "provider"]),
        ("Orchestration", ["tramai-orchestration"]),
        ("Observability", ["tramai-observability"]),
        ("Persistence", [m["name"] for m in modules if m["category"] == "persistence"]),
        ("Sovereign Runtime", [m["name"] for m in modules if m["category"] == "sovereign"]),
        ("Security", ["tramai-security"]),
        ("Scheduler", ["tramai-scheduler"]),
        ("Server", ["tramai-server"]),
        ("MCP", ["tramai-mcp"]),
        ("Platform", ["tramai-platform"]),
        ("Memory", [m["name"] for m in modules if m["category"] == "memory"]),
        ("Embedding", ["tramai-embedding"]),
        ("RAG", ["tramai-rag"]),
        ("Vector Stores", [m["name"] for m in modules if m["category"] == "vectorstore"]),
        ("Dashboard", ["tramai-dashboard"]),
        ("Spring Boot Adapters", [m["name"] for m in modules if m["category"] == "spring-boot"]),
        ("Sovereign Spring Boot Starters", [m["name"] for m in modules if m["category"] == "sovereign-spring-boot"]),
        ("Standalone", ["tramai-standalone"]),
        ("Testing", ["tramai-testing"]),
        ("BOM", ["tramai-bom"]),
        ("Examples", [m["name"] for m in modules if m["category"] == "example"]),
    ]

    md_parts = [f"# Tramai Architecture — {len(modules)} modules\n"]
    for i, (layer_name, layer_mods) in enumerate(layers, 1):
        if not layer_mods:
            continue
        names = "**" + "**, **".join(layer_mods) + "**"
        descs = []
        for n in layer_mods:
            for m in modules:
                if m["name"] == n and m["description"]:
                    descs.append(m["description"])
        suffix = f" — {'; '.join(descs)}" if descs else ""
        md_parts.append(f"{i}. {names}{suffix}")

    # ── Sovereign Runtime section ──
    md_parts.extend([
        "",
        "## Sovereign Runtime",
        "",
        "The Sovereign Runtime extends Tramai for governed, approval-based workflow execution with offline-capable evidence:",
        "",
        "- `ApprovalGateway` / `DefaultApprovalGateway` — Request human approval without wiring low-level stores",
        "- `ApprovalRequestResult.toWorkflowResult { ... }` — Ergonomic mapper from gateway result to workflow result (Preview API)",
        "- `ApprovalDecisionControlPlane`, `ApprovalResumeControlPlane` — REST and programmatic approval decision / resume",
        "- `SovereignWorkflowResult` — Sealed result type: Completed, SuspendedForApproval, Rejected, Expired",
        "- `SovereignEvidencePackV1` — Signed, offline-verifiable artifact provenance packs",
        "- `SovereignProfileConfiguration` — Deployment mode (offline, air-gapped, connected)",
        "- JDBC-backed stores: `SovereignOpsTransactionalApprovalGateway` commits all three records atomically",
        "- Spring Boot starters enable the full stack via minimal `tramai.sovereign.*` properties",
    ])

    # ── Key Design Decisions ──
    md_parts.extend([
        "",
        "## Key Design Decisions (from ADRs)",
        "",
        "- **Typed contracts** over raw prompt plumbing — `@AiService` interfaces define typed inputs and outputs",
        "- **Framework-agnostic core**, thin adapters — core has zero Spring dependencies",
        "- **Structured output** is a first-class capability, not an add-on (`tramai-structured`)",
        "- **Observability is optional** and opt-in at the dependency level (`tramai-observability`)",
        "- **Provider resolution** is registry-based, not fragile prefix-heuristic",
        "- **Sovereign Runtime is Preview**, not RC+ Stable — API stability boundary enforced by build guards",
        "- **Approval gateway factories** are application-supplied, not auto-created",
        "- **Fail loudly** with context when correctness cannot be guaranteed",
        "- **Prefer explicitness** over magical behavior at module boundaries",
    ])

    # ── API Surface ──
    md_parts.extend([
        "",
        "## API Surface",
        "",
        "| Annotation / Function | Module | Purpose |",
        "|---|---|---",
        "| `@AiService` | tramai-orchestration | Marks an interface as an AI service proxy |",
        "| `@Operation` | tramai-orchestration | Defines a single AI operation (prompt, model, timeout, tools) |",
        "| `@AiTool` | tramai-spring | Registers a Spring bean as a callable tool |",
        "| `@AiDescription` | tramai-structured | Annotates fields for structured output schema generation |",
        "| `Tramai.create()` | tramai-standalone | Framework-free entry point |",
        "| `TramaiAutoConfiguration` | tramai-spring | Spring Boot auto-config for core Tramai |",
        "| `ApprovalGateway.requestApproval(...)` | tramai-core | Request human approval from a governed workflow |",
        "| `ApprovalRequestResult.toWorkflowResult { ... }` | tramai-core | Map gateway result to workflow result (Preview) |",
        "| `SovereignWorkflowResult` | tramai-core | Workflow result sealed type |",
        "| `SovereignEvidencePackV1` | tramai-sovereign | Offline-verifiable artifact provenance |",
    ])

    return [
        TextContent(type="text", text=json.dumps({
            "layers": [
                {"name": ln, "modules": lm}
                for ln, lm in layers if lm
            ],
            "totalModules": len(modules),
        }, indent=2)),
        TextContent(type="text", text="\n".join(md_parts)),
    ]


def _categorize_module(name: str, path: Path) -> str:
    """Categorize a module by its name and contents."""
    if name.startswith("examples:"):
        return "example"
    if name == "tramai-spring":
        return "spring-boot"
    if name.startswith("tramai-spring-boot-starter-sovereign"):
        return "sovereign-spring-boot"
    if name.startswith("tramai-spring-boot-starter"):
        return "spring-boot"
    if name in ("tramai-openai", "tramai-anthropic", "tramai-ollama",
                 "tramai-gemini", "tramai-azure-openai", "tramai-bedrock",
                 "tramai-deepseek"):
        return "provider"
    if name.startswith("tramai-vectorstore"):
        return "vectorstore"
    if name.startswith("tramai-persistence"):
        return "persistence"
    if name in ("tramai-memory", "tramai-memory-store"):
        return "memory"
    if name == "tramai-sovereign":
        return "sovereign"
    return "other"

# ── Entry point ──────────────────────────────────────────────────

async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())

if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
