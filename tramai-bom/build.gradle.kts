import dev.tramai.build.quality.ModuleManifest

plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        val bomModulePaths = ModuleManifest.bomModulePaths(rootProject.rootDir)
        rootProject.extra["tramai.bomModulePaths"] = bomModulePaths
        bomModulePaths.forEach { path -> api(project(path)) }
    }
}
