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
        api(project(":tramai-memory"))
        api(project(":tramai-structured"))
        api(project(":tramai-anthropic"))
        api(project(":tramai-azure-openai"))
        api(project(":tramai-bedrock"))
        api(project(":tramai-deepseek"))
        api(project(":tramai-platform"))
        api(project(":tramai-gemini"))
        api(project(":tramai-openai"))
        api(project(":tramai-ollama"))
        api(project(":tramai-observability"))
        api(project(":tramai-orchestration"))
        api(project(":tramai-standalone"))
        api(project(":tramai-sovereign"))
        api(project(":tramai-persistence-file"))
        api(project(":tramai-spring-boot-starter-sovereign"))
        api(project(":tramai-spring-boot-starter-sovereign-persistence-file"))
        api(project(":tramai-spring-boot-starter-sovereign-ops"))
        api(project(":tramai-spring-boot-starter-sovereign-ops-actuator"))
        api(project(":tramai-spring-boot-starter-sovereign-ops-micrometer"))
        api(project(":tramai-spring-boot-starter-sovereign-ops-observability"))
        api(project(":tramai-spring"))
        api(project(":tramai-spring-core"))
        api(project(":tramai-spring-provider-anthropic"))
        api(project(":tramai-spring-provider-ollama"))
        api(project(":tramai-spring-provider-openai"))
        api(project(":tramai-spring-secrets-file"))
        api(project(":tramai-security"))
        api(project(":tramai-testing"))
        api(project(":tramai-vectorstore-spi"))
        api(project(":tramai-vectorstore-chroma"))
        api(project(":tramai-vectorstore-pgvector"))
        api(project(":tramai-rag"))
    }
}
