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
    private var shutdownDrain = CompletableFuture.completedFuture(Unit)
    private var predecessorDrain = CompletableFuture.completedFuture(Unit)
    private var enableScope: LifecycleTaskScope? = null
    private var generationOpen = false
    private var generationStarted = false
    @Volatile private var generation = 0L
    @Volatile private var stopping = false
    private lateinit var io: BoundedAsyncExecutor
    private lateinit var workScope: LifecycleTaskScope
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
        check(!generationOpen) { "Plugin generation is already open" }
        generationOpen = true
        generationStarted = false
        predecessorDrain = shutdownDrain
        shutdownDrain = CompletableFuture()
        val scope = ru.arc.core.LifecycleTaskScope(ru.arc.core.BukkitTaskScheduler(this))
        enableScope = scope
        val token = scope.token()
        predecessorDrain.whenComplete { _, failure ->
            scope.callSync(token) {
                if (enableScope !== scope || !generationOpen) return@callSync
                if (failure != null) {
                    logger.log(Level.SEVERE, "Previous plugin generation failed to drain; startup remains closed", failure)
                    server.pluginManager.disablePlugin(this)
                } else {
                    generationStarted = true
                    startGeneration()
                }
            }.whenComplete { _, startFailure ->
                if (startFailure != null) scope.callSync(token) {
                    if (enableScope === scope && generationOpen) {
                        logger.log(Level.SEVERE, "Plugin generation startup failed closed", startFailure)
                        server.pluginManager.disablePlugin(this)
                    }
                }
            }
        }
    }

    /** Cold preparation starts only after the exact predecessor's terminal writer/lease drain. */
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

    /** Called by ARC on the server thread; all cold preparation stays on the owned worker. */
    fun reloadOrigin() {
        if (stopping || runtime == null) return
        val host = OriginServices.configuration
        generation++
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
        val token = ++generation
        val scopeToken = workScope.token()
        host.ready().whenComplete { _, failure ->
            workScope.runSync(scopeToken) {
                if (token != generation || stopping) return@runSync
                if (failure != null) {
                    logger.log(Level.SEVERE, "ArcOrigin host preparation failed", failure)
                    if (!reload) server.pluginManager.disablePlugin(this)
                } else prepareReady(host, reload, token, scopeToken)
            }
        }
    }

    private fun prepareReady(
        host: ru.arc.origin.api.ArcOriginConfiguration,
        reload: Boolean,
        token: Long,
        scopeToken: LifecycleTaskScope.Token,
    ) {
        val redisService = if (host.sharedRedisAvailable()) server.servicesManager.load(ArcRedisService::class.java) else null
        val ownerName = name
        io.submit {
            if (reload) ConfigManager.reloadAll()
            mergeDefaults()
            ConfigManager.of(legacyDataPath, "modules/logging.yml")
            val policy = OriginSideJobRewardPolicy.load(ConfigManager.ofModule(legacyDataPath, "origin-side-job-rewards.yml"))
            // This packaged JSON is pure model data. Its lazy initializer must never run on a player callback.
            OriginWorkshopRewardPool.entries
            val redis = redisService?.let { PaperRedisClient.openPrimary(it, ownerName).also { client -> redisLeases += client; client.retainDuringShutdown(); client.init() } }
            policy to redis
        }.whenComplete { prepared, failure ->
            if (stopping || token != generation) {
                prepared?.second?.let(::retireRedis)
            } else if (failure != null) {
                workScope.runSync(scopeToken) {
                    logger.log(Level.SEVERE, "ArcOrigin preparation failed closed", failure)
                    moduleHealth = RuntimeHealthContribution(state = RuntimeHealthState.DOWN)
                    if (!reload) server.pluginManager.disablePlugin(this)
                }
            } else {
                workScope.callSync(scopeToken) {
                    check(token == generation && !stopping) { "Origin preparation superseded" }
                    sideJobRewardPolicy = prepared.first
                    redisClient = prepared.second
                    ArcLogging.install(
                        PaperLoggingPlatform("ArcOrigin", "ArcOrigin"),
                        LoggingConfigSource { ConfigManager.of(legacyDataPath, "modules/logging.yml") },
                    )
                    logger.info("ORIGIN_SIDE_JOB_POLICY orders=${prepared.first.dailyOrderLimit} amount=${prepared.first.rewardPerOrder} settlement=shift-end")
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
                }.whenComplete { _, activationFailure ->
                    if (activationFailure != null) {
                        prepared.second?.let(::retireRedis)
                        workScope.callSync(scopeToken) {
                            if (token == generation && !stopping) {
                                moduleHealth = RuntimeHealthContribution(state = RuntimeHealthState.DOWN)
                                logger.log(Level.SEVERE, "ArcOrigin activation failed closed", activationFailure)
                                server.pluginManager.disablePlugin(this)
                            }
                        }
                    }
                }
            }
        }
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
        if (!generationOpen) return
        generationOpen = false
        enableScope?.close()
        enableScope = null
        val completion = shutdownDrain
        val previous = predecessorDrain
        val retiring = if (generationStarted) runCatching { closeGeneration() }.getOrElse { CompletableFuture.failedFuture(it) }
            else CompletableFuture.completedFuture(Unit)
        generationStarted = false
        CompletableFuture.allOf(previous, retiring).whenComplete { _, failure ->
            if (failure == null) completion.complete(Unit) else completion.completeExceptionally(failure)
        }
    }

    /** Native detach is synchronous; the returned completion owns every accepted terminal IO action. */
    protected open fun closeGeneration(): CompletableFuture<Unit> {
        stopping = true
        generation++
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
        // Retained clients belong to the finalizer, including a preparation that finishes during detach.
        redisClient = null
        val retiringMetrics = metrics
        metrics = null
        detach { retiringMetrics?.close() }
        val retiringRuntime = runtime
        runtime = null
        detach { retiringRuntime?.close() }
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
