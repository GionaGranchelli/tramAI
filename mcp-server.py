#!/usr/bin/env python3
"""
MCP server for the Tramai project — structured-first AI workflow library for JVM.

Exposes design docs, ADRs, specs, task boards, and codebase search
as MCP resources and tools. Helps AI agents understand Tramai's
architecture, API surface, and design decisions before writing code.

Project: ~/Development/aurora (Tramai)
"""

import json, re, subprocess
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

_MODULES = [
    "tramai-core", "tramai-engine", "tramai-structured",
    "tramai-anthropic", "tramai-openai", "tramai-ollama",
    "tramai-observability", "tramai-orchestration",
    "tramai-standalone", "tramai-spring", "tramai-testing",
    "tramai-bom",
]

@server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="search_codebase",
            description="Search the Tramai codebase for patterns: @AiService, @Operation, @AiTool, etc.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pattern": {
                        "type": "string",
                        "description": "Pattern to grep for (e.g., '@AiService', 'class.*Provider', 'fun create')",
                    },
                    "module": {
                        "type": "string",
                        "description": f"Optional module name: {', '.join(_MODULES)}",
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

async def _search_codebase(pattern: str, module: str | None) -> list[TextContent]:
    search_root = TRAMAI / module if module else TRAMAI
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
    lines = ["# Tramai Modules\n"]
    for mod in _MODULES:
        path = TRAMAI / mod
        build_file = path / "build.gradle.kts"
        desc = ""
        if build_file.exists():
            content = build_file.read_text()
            m = re.search(r'description\s*=\s*"([^"]+)"', content)
            desc = f" — {m.group(1)}" if m else ""
        exists = "✅" if path.exists() else "❌"
        lines.append(f"- {exists} **{mod}**{desc}")
    return [TextContent(type="text", text="\n".join(lines))]

async def _read_source(module: str, path: str) -> list[TextContent]:
    file_path = TRAMAI / module / "src" / "main" / "kotlin" / path
    if not file_path.exists():
        file_path = TRAMAI / module / "src" / "main" / "java" / path
    if not file_path.exists():
        # Try searching
        found = list((TRAMAI / module / "src").rglob(path))
        if found:
            file_path = found[0]
    if not file_path.exists():
        return [TextContent(type="text", text=f"File not found: {module}/{path}")]
    return [TextContent(type="text", text=file_path.read_text())]

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
    return [TextContent(type="text", text="""# Tramai Architecture

## Module Layering (bottom → top)

1. **tramai-core** — SPI definitions: ModelProvider, StructuredOutputSchema, SecretValueResolver
2. **tramai-structured** — Schema generation, extraction, deserialization, failure analysis
3. **tramai-engine** — Orchestration, retry policy, model routing, provider resolution
4. Provider modules (tramai-openai, tramai-anthropic, tramai-ollama) — ModelProvider implementations
5. **tramai-orchestration** — Workflow DSL with @AiService, @Operation, @AiTool
6. Framework adapters (tramai-spring) — Spring Boot auto-configuration
7. **tramai-standalone** — Minimal entry point for framework-free usage

## Key Design Decisions (from ADRs)

- Typed contracts over raw prompt plumbing
- Framework-agnostic core, thin adapters
- Structured output as first-class capability
- Observability is optional and opt-in
- Provider resolution is registry-based, not prefix-heuristic

## API Surface

- `@AiService` — Marks an interface as an AI service proxy
- `@Operation` — Defines a single AI operation (prompt, model, tools, timeout)
- `@AiTool` — Registers a Spring bean as a callable tool
- `@AiDescription` — Annotates fields for structured output schema generation
- `Tramai.create()` — Framework-free entry point (standalone module)
- `TramaiAutoConfiguration` — Spring Boot auto-config (spring module)
""")]

# ── Entry point ──────────────────────────────────────────────────

async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())

if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
