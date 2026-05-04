rootProject.name = "tramai"

include(
    "tramai-anthropic",
    "tramai-bom",
    "tramai-core",
    "tramai-engine",
    "tramai-observability",
    "tramai-mcp",
    "tramai-orchestration",
    "tramai-openai",
    "tramai-ollama",
    "tramai-platform",
    "tramai-spring",
    "tramai-scheduler",
    "tramai-server",
    "tramai-standalone",
    "tramai-structured",
    "tramai-testing",
)
// Planned: "tramai-dashboard" — Vue 3 + Vite SPA, optional dependency of tramai-server (TASK-038)
