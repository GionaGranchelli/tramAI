plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":aurora-core"))
        api(project(":aurora-engine"))
        api(project(":aurora-structured"))
        api(project(":aurora-anthropic"))
        api(project(":aurora-openai"))
        api(project(":aurora-ollama"))
        api(project(":aurora-observability"))
        api(project(":aurora-standalone"))
        api(project(":aurora-spring"))
        api(project(":aurora-testing"))
    }
}
