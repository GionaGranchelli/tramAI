plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":tramai-core"))
        api(project(":tramai-embedding"))
        api(project(":tramai-engine"))
        api(project(":tramai-structured"))
        api(project(":tramai-anthropic"))
        api(project(":tramai-openai"))
        api(project(":tramai-ollama"))
        api(project(":tramai-observability"))
        api(project(":tramai-orchestration"))
        api(project(":tramai-standalone"))
        api(project(":tramai-spring"))
        api(project(":tramai-testing"))
        api(project(":tramai-vectorstore-spi"))
        api(project(":tramai-vectorstore-chroma"))
        api(project(":tramai-vectorstore-pgvector"))
        api(project(":tramai-rag"))
    }
}
