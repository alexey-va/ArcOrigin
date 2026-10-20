package ru.arc.origin

import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.api.ArcRedisService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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
    private var modulesRegistered = false
    private var lifecycleOpen = false
    private var pendingClose = CompletableFuture.completedFuture(Unit)
    private var nativeDetachFailed = false
    @Volatile private var stopping = false
    private lateinit var io: BoundedAsyncExecutor
    private lateinit var workScope: LifecycleTaskScope
    internal var questProgress: OriginQuestProgress? = null
        private set
    private lateinit var nativeWork: BudgetedTaskQueue
    private var commandService: OriginCommandService? = null
    private val redisLeases = ConcurrentHashMap.newKeySet<PaperRedisClient>()
    @Volatile private var runtime: PaperPluginRuntime? = null
    private var metrics: ArcMetricsScope? = null
    @Volatile private var moduleHealth = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)

    override fun onLoad() {
        instance = this
    }

    override fun onEnable() {
        check(!lifecycleOpen) { "Plugin lifecycle is already open" }
        try {
            check(pendingClose.isDone) { "Previous native cleanup is still pending" }
            pendingClose.getNow(Unit)
            lifecycleOpen = true
            startGeneration()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ArcOrigin startup failed closed", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    protected open fun startGeneration() {
        stopping = false
        val host = server.servicesManager.load(ru.arc.origin.api.ArcOriginConfiguration::class.java)
        if (host == null) {
            logger.warning("ArcOrigin requires the ARC shared-service host; disabling before initialization")
            server.pluginManager.disablePlugin(this)
            return
        }

        legacyDataPath = host.legacyDataPath()
        PaperArcRuntime.installScheduling(this)
        workScope = LifecycleTaskScope(Tasks.scheduler)
        questProgress = if (server.pluginManager.isPluginEnabled("ArcRanks")) OriginQuestProgress(workScope, {
            server.servicesManager.load(ru.ruscrafting.ranks.api.RankQuestApi::class.java)
        }, logger) else null
        nativeWork = BudgetedTaskQueue(workScope)
        io = BoundedAsyncExecutor("arc-origin-prepare")
        val localRuntime = PaperPluginRuntime(this, "arc-origin").also { runtime = it; it.start("host" to "ARC") }
        localRuntime.registerHealth("modules") { moduleHealth }
        metrics = PaperArcMetrics.open(this, "arc-origin")
        PaperArcRuntime.installModuleLifecycleReporting(
            consoleLog = { logger.info(it) },
            logError = { message, failure -> logger.log(Level.SEVERE, message, failure) },
        )
        prepare(host, reload = false)
    }

    internal fun <T> compute(action: () -> T): CompletableFuture<T> = io.submit(action)

    internal fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> =
        nativeWork.submit({ !stopping && isCurrent() }, step)

    /** Called by ARC on the server thread; manual reload is a sequential lifecycle operation. */
    fun reloadOrigin() {
        if (stopping || runtime == null) return
        val host = OriginServices.configuration
        moduleHealth = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)
        server.servicesManager.unregisterAll(this)
        commandService = null
        nativeWork.close()
        ModuleRegistry.shutdownAll()
        OriginMenus.close()
        workScope.restart()
        nativeWork = BudgetedTaskQueue(workScope)
        redisClient?.let(::retireRedis)
        redisClient = null
        runtime?.reload()
        prepare(host, reload = true)
    }

    private fun prepare(host: ru.arc.origin.api.ArcOriginConfiguration, reload: Boolean) {
        val ready = host.ready()
        check(ready.isDone) { "ARC host must be ready before ArcOrigin starts" }
        ready.getNow(null)
        val redisService = if (host.sharedRedisAvailable()) server.servicesManager.load(ArcRedisService::class.java) else null
        if (reload) ConfigManager.reloadAll()
        mergeDefaults()
        ConfigManager.of(legacyDataPath, "modules/logging.yml")
        sideJobRewardPolicy = OriginSideJobRewardPolicy.load(ConfigManager.ofModule(legacyDataPath, "origin-side-job-rewards.yml"))
        OriginWorkshopRewardPool.entries
        redisClient = redisService?.let {
            PaperRedisClient.openPrimary(it, name).also { client ->
                redisLeases += client
                client.retainDuringShutdown()
                client.init()
            }
        }
        ArcLogging.install(
            PaperLoggingPlatform("ArcOrigin", "ArcOrigin"),
            LoggingConfigSource { ConfigManager.of(legacyDataPath, "modules/logging.yml") },
        )
        logger.info("ORIGIN_SIDE_JOB_POLICY orders=${sideJobRewardPolicy.dailyOrderLimit} amount=${sideJobRewardPolicy.rewardPerOrder} settlement=shift-end")
        OriginMenus.initialize(this)
        if (!modulesRegistered) {
            ModuleRegistry.registerAll(
                OriginSpawnModule, OriginPortalsModule, OriginWorkshopTablesModule, OriginDiningModule,
                OriginAmbientScenesModule, OriginFurnitureWorkshopModule, OriginWorkshopGame,
                OriginTrainingDummyModule, OriginMountYardModule, OriginOddJobsModule,
            )
            modulesRegistered = true
        }
        ModuleRegistry.initAll()
        commandService = OriginCommandService().also {
            server.servicesManager.register(ArcOriginCommands::class.java, it, this, ServicePriority.Normal)
        }
        publishModuleHealth()
        runtime?.ready("modules" to ModuleRegistry.getRuntimeStatuses().count { it.ready })
        runtime?.reportHealthEvery(1_200L)
        ArcLogging.info("ArcOrigin modules ready")
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
        resources.forEach { resource ->
            ConfigManager.of(legacyDataPath, resource).mergeMissingFromBundled(resource)
            ConfigManager.ofModule(legacyDataPath, resource.removePrefix("modules/"))
                .mergeMissingFromBundled(resource)
        }
    }

    private fun retireRedis(client: PaperRedisClient) {
        val durable = OriginSideJobRewards.shutdownDrain()
        io.submit {
            try { try { durable.join() } finally { client.close() } } finally { redisLeases.remove(client) }
        }
        // A rejected retirement is owned by the terminal worker finalizer below.
    }

    override fun onDisable() {
        if (!lifecycleOpen) return
        lifecycleOpen = false
        val closing = runCatching { closeGeneration() }.getOrElse { CompletableFuture.failedFuture(it) }
        pendingClose = closing
        if (!nativeDetachFailed) {
            // Closed reward owners complete their remaining Redis receipts without a Paper callback.
            runCatching { closing.join() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcOrigin storage", it) }
        } else {
            // A failed native detach may leave a callback on main; never join that tail here.
            closing.whenComplete { _, failure ->
                if (failure != null) logger.log(Level.SEVERE, "ArcOrigin cleanup failed", failure)
            }
        }
    }

    /** Native detach is synchronous; the returned completion owns every accepted terminal IO action. */
    protected open fun closeGeneration(): CompletableFuture<Unit> {
        stopping = true
        nativeDetachFailed = false
        var nativeFailure: Throwable? = null
        fun detach(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (nativeFailure == null) nativeFailure = problem else nativeFailure.addSuppressed(problem)
            }
        }
        detach { if (::nativeWork.isInitialized) nativeWork.close() }
        detach { if (::workScope.isInitialized) workScope.close() }
        detach { detachModules() }
        detach { ru.arc.npc.NpcRouteRotation.closeAll() }
        detach { OriginMenus.close() }
        commandService = null
        detach { server.servicesManager.unregisterAll(this) }
        // Retained clients close only after every accepted reward write has finished.
        redisClient = null
        val retiringMetrics = metrics
        metrics = null
        detach { retiringMetrics?.close() }
        val retiringRuntime = runtime
        runtime = null
        detach { retiringRuntime?.close() }
        nativeDetachFailed = nativeFailure != null
        val durable = OriginSideJobRewards.shutdownDrain()
        val terminal = closeStorage(durable)
        return terminal.handle { _, storageFailure ->
            val failure = nativeFailure
            if (failure != null) {
                storageFailure?.let(failure::addSuppressed)
                throw failure
            }
            storageFailure?.let { throw it }
            Unit
        }
    }

    /** Small lifecycle seam for exercising a throwing module detach with the real shutdown ordering. */
    protected open fun detachModules() { ModuleRegistry.shutdownAll() }

    protected open fun closeStorage(durable: CompletableFuture<Void>): CompletableFuture<Unit> =
        if (::io.isInitialized) io.closeAsync { closeOriginStorage(durable, redisLeases) }
        else durable.thenApply { Unit }
}

/** Worker-only; a failed receipt or lease must not prevent the remaining retained clients from closing. */
internal fun closeOriginStorage(durable: CompletableFuture<Void>, leases: MutableSet<out AutoCloseable>) {
    var failure: Throwable? = runCatching { durable.join() }.exceptionOrNull()
    val pending = leases.iterator()
    while (pending.hasNext()) {
        val resource = pending.next()
        try { resource.close() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        } finally { pending.remove() }
    }
    failure?.let { throw it }
}
