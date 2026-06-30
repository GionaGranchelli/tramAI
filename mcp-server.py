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

    # ADRs
    adr_dir = DOCS / "adr"
    if adr_dir.exists():
        for f in sorted(adr_dir.glob("adr-*.md")):
            resources.append(Resource(
                uri=f"tramai://adr/{f.stem}",
                name=f"adr/{f.stem}",
                description=f"Architecture Decision Record: {f.stem}",
                mimeType="text/markdown",
            ))

    # Specs
    spec_dir = DOCS / "specs"
    if spec_dir.exists():
        for f in sorted(spec_dir.glob("spec-*.md")):
            resources.append(Resource(
                uri=f"tramai://spec/{f.stem}",
                name=f"spec/{f.stem}",
                description=f"Specification: {f.stem}",
                mimeType="text/markdown",
            ))

    return resources

@server.read_resource()
async def read_resource(uri: str) -> str:
    rel = uri.removeprefix("tramai://")
    if rel in _DOC_FILES:
        return _DOC_FILES[rel].read_text()
    if rel.startswith("adr/"):
        path = DOCS / "adr" / f"{rel[4:]}.md"
        return path.read_text() if path.exists() else f"Not found: {rel}"
    if rel.startswith("spec/"):
        path = DOCS / "specs" / f"{rel[5:]}.md"
        return path.read_text() if path.exists() else f"Not found: {rel}"
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
            description="Search the Tramai codebase for patterns: @AiService, @Operation, @AiTool, ApprovalGateway, Sovereign, etc.",
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
                },
                "required": ["pattern"],
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
        return await _search_codebase(arguments["pattern"], arguments.get("module"))
    elif name == "list_modules":
        return await _list_modules()
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


async def _search_codebase(pattern: str, module: str | None) -> list[TextContent]:
    search_root = _module_path(module) if module else TRAMAI
    if not search_root.exists():
        return [TextContent(type="text", text=f"Module not found: {module}")]

    try:
        result = subprocess.run(
            ["grep", "-rn", "--include=*.kt", "--include=*.java", pattern, str(search_root)],
            capture_output=True, text=True, timeout=10,
        )
        lines = result.stdout.strip().split("\n")[:40]
        if not lines:
            return [TextContent(type="text", text=f"No matches for '{pattern}'")]
        return [TextContent(type="text", text="\n".join(lines))]
    except subprocess.TimeoutExpired:
        return [TextContent(type="text", text="Search timed out")]

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
            ["grep", "-rni", query, str(DOCS)],
            capture_output=True, text=True, timeout=10,
        )
        lines = result.stdout.strip().split("\n")[:30]
        if not lines:
            return [TextContent(type="text", text=f"No matches for '{query}' in docs")]
        return [TextContent(type="text", text="\n".join(lines))]
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

    md_parts = ["# Tramai Architecture\n"]
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

    return [
        TextContent(type="text", text=json.dumps({
            "layers": [
                {"name": ln, "modules": lm}
                for ln, lm in layers if lm
            ],
            "totalModules": len(modules),
        }, indent=2)),
        TextContent(type="text", text="\n\n".join(md_parts)),
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
