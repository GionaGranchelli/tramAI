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
        bomModulePaths.forEach { path -> api(project(path)) }
    }
}
