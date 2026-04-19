plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":tramai-core"))
        api(project(":tramai-engine"))
        api(project(":tramai-structured"))
        api(project(":tramai-anthropic"))
        api(project(":tramai-openai"))
        api(project(":tramai-ollama"))
        api(project(":tramai-observability"))
        api(project(":tramai-standalone"))
        api(project(":tramai-spring"))
        api(project(":tramai-testing"))
    }
}
