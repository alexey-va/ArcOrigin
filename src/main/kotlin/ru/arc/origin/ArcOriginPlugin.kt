package ru.arc.origin

import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
import ru.arc.core.ModuleRuntimeStatus
import ru.arc.core.PaperArcRuntime
import ru.arc.origin.api.ArcOriginCommands
import ru.arc.origin.menu.OriginMenus
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.api.ArcMetricsScope
import ru.arc.paper.metrics.PaperArcMetrics
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.origin.commands.OriginCommandService
import ru.arc.origin.scene.OriginAmbientScenesModule
import ru.arc.origin.mountyard.OriginMountYardModule
import java.nio.file.Path
import java.util.Locale
import java.util.logging.Level

/** Plugin-local owner for Origin modules and their resources. */
open class ArcOriginPlugin : JavaPlugin() {
    companion object {
        lateinit var instance: ArcOriginPlugin
            private set
    }

    lateinit var legacyDataPath: Path
        private set

    internal var redisClient: PaperRedisClient? = null
        private set
    internal var sideJobRewardPolicy = OriginSideJobRewardPolicy()
        private set
    private var redisGeneration = Long.MIN_VALUE
    private var commandService: OriginCommandService? = null
    private var runtime: PaperPluginRuntime? = null
    private var metrics: ArcMetricsScope? = null
    @Volatile private var moduleHealth = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)

    override fun onLoad() {
        instance = this
    }

    override fun onEnable() {
        val host = server.servicesManager.load(ru.arc.origin.api.ArcOriginConfiguration::class.java)
        if (host == null) {
            logger.warning("ArcOrigin requires the ARC shared-service host; disabling before initialization")
            server.pluginManager.disablePlugin(this)
            return
        }

        legacyDataPath = host.legacyDataPath()
        mergeDefaults()
        loadSideJobRewardPolicy()
        PaperArcRuntime.installScheduling(this)
        ArcLogging.install(
            PaperLoggingPlatform("ArcOrigin", "ArcOrigin"),
            LoggingConfigSource { ConfigManager.of(legacyDataPath, "modules/logging.yml") },
        )
        val localRuntime = PaperPluginRuntime(this, "arc-origin").also { runtime = it; it.start("host" to "ARC") }
        localRuntime.registerHealth("modules") { moduleHealth }
        metrics = PaperArcMetrics.open(this, "arc-origin")
        PaperArcRuntime.installModuleLifecycleReporting(
            consoleLog = { logger.info(it) },
            logError = { message, failure -> logger.log(Level.SEVERE, message, failure) },
        )
        OriginMenus.initialize(this)
        refreshRedis(host)

        ModuleRegistry.registerAll(
            OriginSpawnModule,
            OriginPortalsModule,
            OriginWorkshopTablesModule,
            OriginDiningModule,
            OriginAmbientScenesModule,
            OriginFurnitureWorkshopModule,
            OriginWorkshopGame,
            OriginTrainingDummyModule,
            OriginMountYardModule,
            OriginOddJobsModule,
        )
        ModuleRegistry.initAll()

        commandService = OriginCommandService().also {
            server.servicesManager.register(ArcOriginCommands::class.java, it, this, ServicePriority.Normal)
        }
        publishModuleHealth()
        localRuntime.ready("modules" to ModuleRegistry.getRuntimeStatuses().count { it.ready })
        localRuntime.reportHealthEvery(1_200L)
        ArcLogging.info("ArcOrigin enabled with {} Origin modules", ModuleRegistry.getModules().size)
    }

    /** Called by the ARC command bridge; resource owners remain plugin-local. */
    fun reloadOrigin() {
        ConfigManager.reloadAll()
        mergeDefaults()
        loadSideJobRewardPolicy()
        refreshRedis(OriginServices.configuration)
        ModuleRegistry.reloadAll()
        runtime?.reload()
        publishModuleHealth()
        runtime?.reportHealthEvery(1_200L)
        ArcLogging.info("ArcOrigin modules reloaded")
    }

    private fun loadSideJobRewardPolicy() {
        // Validate before closing modules; an invalid edit leaves current owners and payments intact.
        val candidate = OriginSideJobRewardPolicy.load(ConfigManager.ofModule(legacyDataPath, "origin-side-job-rewards.yml"))
        sideJobRewardPolicy = candidate
        logger.info("ORIGIN_SIDE_JOB_POLICY orders=${candidate.dailyOrderLimit} amount=${candidate.rewardPerOrder} settlement=shift-end")
    }

    private fun publishModuleHealth() {
        val statuses = ModuleRegistry.getRuntimeStatuses()
        moduleHealth = RuntimeHealthContribution(
            state = if (statuses.all { it.ready }) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
            dependencies = statuses.associate { it.name.lowercase(Locale.ROOT) to it.ready },
        )
        for (status in statuses) {
            metrics?.gauge(
                "arc_origin_module_ready",
                "ArcOrigin module readiness",
                mapOf("module" to status.name),
            )?.set(if (status.ready) 1.0 else 0.0)
        }
    }

    private fun refreshRedis(host: ru.arc.origin.api.ArcOriginConfiguration) {
        val generation = host.sharedRedisGeneration()
        if (generation == redisGeneration) return
        redisClient?.close()
        redisClient = null
        if (host.sharedRedisAvailable()) redisClient = PaperRedisClient.openPrimary(this)
        redisGeneration = generation
    }

    private fun mergeDefaults() {
        val resources = listOf(
            "modules/origin-spawn.yml",
            "modules/origin-mount-yard.yml",
            "modules/origin-mount-care.yml",
            "modules/origin-odd-jobs.yml",
            "modules/origin-side-job-rewards.yml",
            "modules/origin-dining.yml",
            "modules/origin-scenes.yml",
            "modules/origin-workshop-game.yml",
            "modules/origin-workshop-tables.yml",
            "modules/origin-furniture-workshop.yml",
        )
        resources.forEach { resource -> ConfigManager.of(legacyDataPath, resource).mergeMissingFromBundled(resource) }
    }

    override fun onDisable() {
        try {
            ModuleRegistry.shutdownAll()
        } finally {
            ru.arc.npc.NpcRouteRotation.closeAll()
        }
        OriginMenus.close()
        commandService = null
        server.servicesManager.unregisterAll(this)
        redisClient?.close()
        redisClient = null
        metrics?.close()
        metrics = null
        runtime?.close()
        runtime = null
    }
}
