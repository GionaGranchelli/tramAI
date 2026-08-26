package dev.tramai.build.quality

import org.gradle.api.GradleException
import java.io.File

/** Configuration-time facade for the authoritative module architecture manifest. */
object ModuleManifest {
    fun catalog(rootDir: File): ModuleCatalog.CatalogResult = ModuleCatalog(rootDir).parse().also { result ->
        if (result.errors.isNotEmpty()) throw GradleException(result.errors.joinToString("\n") { "[${it.code}] ${it.message}" })
    }

    fun publishableModulePaths(rootDir: File): List<String> = catalog(rootDir).modules.values
        .filter { it.publishability == ModulePublishability.PUBLISHED }.map { it.path }.sorted()

    fun bomModulePaths(rootDir: File): List<String> = catalog(rootDir).modules.values
        .filter { it.path != ":tramai-bom" && it.publishability == ModulePublishability.PUBLISHED && it.releaseInclusion == ReleaseInclusion.INCLUDED }
        .map { it.path }.sorted()

    fun matrix(rootDir: File): String {
        val rows = catalog(rootDir).modules.values.sortedBy { it.path }.joinToString("\n") { entry ->
            "| ${entry.path.removePrefix(":")} | ${entry.layer.yaml} | ${entry.maturity.yaml} | ${entry.apiStability.yaml} | ${if (entry.publishability == ModulePublishability.PUBLISHED) "Yes" else "No"} | ${entry.owner} | ${entry.releaseInclusion.yaml} |"
        }
        return """# TramAI Module Matrix

<!-- generated from config/quality/module-catalog.yml — do not edit manually -->

| Module | Layer | Maturity | API | Published | Owner | Release |
|--------|-------|----------|-----|-----------|-------|---------|
$rows
"""
    }
}
