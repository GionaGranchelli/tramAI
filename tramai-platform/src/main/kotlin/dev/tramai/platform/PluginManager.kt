package dev.tramai.platform

import dev.tramai.orchestration.ExternalStepExecutorFactory
import dev.tramai.orchestration.ExternalStepExecutorRegistry
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ServiceLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString

class PluginNotFoundException(
    pluginId: String,
) : RuntimeException("Plugin '$pluginId' was not found")

class WebhookAdapterNotRegisteredException(
    sourceId: String,
) : RuntimeException("No webhook adapter is registered for source '$sourceId'")

class WebhookAdapterRegistry {
    private val factories = linkedMapOf<String, WebhookAdapterFactory>()

    @Synchronized
    fun replaceAll(factories: Collection<WebhookAdapterFactory>) {
        this.factories.clear()
        factories.forEach { factory ->
            require(factory.sourceId.isNotBlank()) { "Webhook adapter sourceId must not be blank" }
            this.factories[factory.sourceId] = factory
        }
    }

    @Synchronized
    fun create(sourceId: String): WebhookAdapter =
        factories[sourceId]?.create() ?: throw WebhookAdapterNotRegisteredException(sourceId)
}

class PluginManager(
    private val pluginDirectory: Path,
    private val pluginStateRepository: PluginStateRepository,
    private val stepExecutorRegistry: ExternalStepExecutorRegistry,
    private val webhookAdapterRegistry: WebhookAdapterRegistry,
) {
    private val logger = LoggerFactory.getLogger(PluginManager::class.java)
    private val views = linkedMapOf<String, PluginView>()

    init {
        pluginDirectory.createDirectories()
    }

    @Synchronized
    fun list(): List<PluginView> = views.values.sortedBy(PluginView::id)

    @Synchronized
    fun install(jarPath: String): List<PluginView> {
        val source = Path.of(jarPath)
        require(Files.isRegularFile(source)) { "Plugin JAR '$jarPath' does not exist" }
        require(source.extension == "jar") { "Plugin path '$jarPath' must reference a .jar file" }
        val target = pluginDirectory.resolve(source.name)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        refresh()
        return list().filter { it.jarPath == target.pathString }
    }

    @Synchronized
    fun enable(id: String): PluginView {
        pluginStateRepository.find(id) ?: throw PluginNotFoundException(id)
        pluginStateRepository.setEnabled(id, enabled = true)
        refresh()
        return views[id] ?: throw PluginNotFoundException(id)
    }

    @Synchronized
    fun disable(id: String): PluginView {
        pluginStateRepository.find(id) ?: throw PluginNotFoundException(id)
        pluginStateRepository.setEnabled(id, enabled = false)
        refresh()
        return views[id] ?: throw PluginNotFoundException(id)
    }

    @Synchronized
    fun refresh() {
        val stepExecutorFactories = mutableListOf<ExternalStepExecutorFactory>()
        val webhookFactories = mutableListOf<WebhookAdapterFactory>()
        val refreshedViews = linkedMapOf<String, PluginView>()
        if (!pluginDirectory.isDirectory()) {
            pluginDirectory.createDirectories()
        } else {
            Files.list(pluginDirectory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.extension == "jar" }
                    .sorted()
                    .forEach { jar ->
                        loadJar(jar).forEach { discovered ->
                            val persisted = pluginStateRepository.find(discovered.plugin.id)
                            val enabled = persisted?.enabled ?: true
                            val status = if (enabled) PluginStatus.ENABLED else PluginStatus.DISABLED
                            pluginStateRepository.upsert(
                                PluginStateRecord(
                                    id = discovered.plugin.id,
                                    version = discovered.plugin.version,
                                    jarPath = jar.pathString,
                                    enabled = enabled,
                                    status = status,
                                    error = null,
                                ),
                            )
                            if (enabled) {
                                stepExecutorFactories += discovered.stepExecutors
                                webhookFactories += discovered.webhookAdapters
                            }
                            refreshedViews[discovered.plugin.id] = discovered.toView(
                                status = status,
                                jarPath = jar.pathString,
                                error = null,
                            )
                        }
                    }
            }
        }

        stepExecutorRegistry.replaceAll(stepExecutorFactories)
        webhookAdapterRegistry.replaceAll(webhookFactories)
        views.clear()
        views.putAll(refreshedViews)
    }

    private fun loadJar(jar: Path): List<DiscoveredPlugin> = try {
        val classLoader = URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
        ServiceLoader.load(TramaiPlugin::class.java, classLoader)
            .toList()
            .map { plugin ->
                require(plugin.id.isNotBlank()) { "Plugin from '${jar.pathString}' has a blank id" }
                require(plugin.version.isNotBlank()) { "Plugin '${plugin.id}' has a blank version" }
                DiscoveredPlugin(
                    plugin = plugin,
                    stepExecutors = plugin.stepExecutors(),
                    webhookAdapters = plugin.webhookAdapters(),
                    dashboardExtensions = plugin.dashboardExtensions(),
                )
            }
            .also { discovered ->
                require(discovered.isNotEmpty()) { "No TramaiPlugin services were found in '${jar.pathString}'" }
            }
    } catch (error: Throwable) {
        logger.error("Failed to load plugin jar '{}'", jar.pathString, error)
        emptyList()
    }

    private data class DiscoveredPlugin(
        val plugin: TramaiPlugin,
        val stepExecutors: List<ExternalStepExecutorFactory>,
        val webhookAdapters: List<WebhookAdapterFactory>,
        val dashboardExtensions: List<DashboardExtension>,
    ) {
        fun toView(
            status: PluginStatus,
            jarPath: String,
            error: String?,
        ): PluginView = PluginView(
            id = plugin.id,
            version = plugin.version,
            status = status,
            jarPath = jarPath,
            error = error,
            stepTypes = stepExecutors.map { it.typeId }.sorted(),
            webhookSources = webhookAdapters.map { it.sourceId }.sorted(),
            dashboardExtensions = dashboardExtensions.map(DashboardExtension::id).sorted(),
        )
    }
}
