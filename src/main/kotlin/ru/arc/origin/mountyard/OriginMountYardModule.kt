package ru.arc.origin.mountyard

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.config.ConfigSection
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.origin.ArcOriginPlugin
import ru.arc.origin.OriginNpcHolograms
import ru.arc.mounts.api.ArcMountService
import ru.arc.logging.ArcLogging.warn
import java.nio.file.Path
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun mountService(): ArcMountService? = Bukkit.getServicesManager().load(ArcMountService::class.java)

/** One Kotlin owner for stable petting, daily care progress and its player-facing entry. */
object OriginMountYardModule : PluginModule {
    override val name = "OriginMountYard"
    override val priority = 84
    private var runtime: MountYardCareRuntime? = null

    override fun init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        start(MountYardCareConfig.load(ArcOriginPlugin.instance.legacyDataPath), OriginMountYardAnimalsConfig.load(ArcOriginPlugin.instance.legacyDataPath))
    }

    override fun reload() {
        val care = MountYardCareConfig.load(ArcOriginPlugin.instance.legacyDataPath)
        val animals = OriginMountYardAnimalsConfig.load(ArcOriginPlugin.instance.legacyDataPath)
        shutdown()
        start(care, animals)
    }

    private fun start(care: MountYardCareConfig, animals: OriginMountYardAnimalsConfig) {
        if (Bukkit.getWorld(care.world) == null) return
        runtime = MountYardCareRuntime(care, animals).also { it.start() }
    }

    override fun shutdown() {
        runtime?.close()
        runtime = null
    }
}

private data class MountYardCareConfig(
    val world: String,
    val guideId: Int,
    val guideUuid: UUID,
    val guideName: String,
    val guideLines: List<String>,
    val range: Double,
    val requiredAnimals: Int,
    val lifetimeMillis: Long,
    val text: ConfigSection,
) {
    companion object {
        fun load(path: Path): MountYardCareConfig {
            val config = ConfigManager.of(path, "modules/origin-mount-care.yml")
            return MountYardCareConfig(
                config.string("world", "rc_origin_spawn"),
                config.integer("guide.npc-id", 440),
                UUID.fromString(config.string("guide.npc-uuid", "f975fcc7-c044-48f8-818d-ac9939eda2aa")),
                config.string("guide.name", "Сеня"),
                config.stringList("guide.lines"),
                config.real("interaction-range", 4.0).also { require(it.isFinite() && it in 1.0..6.0) },
                config.integer("required-animals", 3).also { require(it in 1..20) },
                config.integer("task-lifetime-seconds", 900).also { require(it in 60..3600) } * 1000L,
                config.section("text"),
            )
        }
    }

    fun line(key: String, vararg values: Pair<String, Any>): String = values.fold(text.string(key)) { result, (name, value) ->
        result.replace("{$name}", value.toString())
    }
}

private class MountYardCareRuntime(
    private val config: MountYardCareConfig,
    animalConfig: OriginMountYardAnimalsConfig,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val progress = MountYardCareProgress(config.requiredAnimals, config.lifetimeMillis)
    private val animals = OriginMountYardAnimals(animalConfig, onFriendlyPet = ::onPet)

    init { require(config.requiredAnimals <= animalConfig.animals.size) }

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
        Bukkit.getPluginManager().registerEvents(animals, ArcOriginPlugin.instance)
        tasks.runLater(1) {
            val registry = CitizensAPI.getNPCRegistry()
            registry.getById(config.guideId)?.takeIf { it.uniqueId == config.guideUuid }?.let { npc ->
                OriginNpcHolograms.patchName(npc.id, config.guideName)
                OriginNpcHolograms.patchNameplate(npc.id, "true")
                OriginNpcHolograms.patchHologram(npc.id, config.guideLines, viewRange = 18)
            }
            animals.animals.forEach { animal ->
                registry.getById(animal.npcId)?.takeIf { it.uniqueId == animal.npcUuid }?.let { npc ->
                    OriginNpcHolograms.patchName(npc.id, animal.name)
                    OriginNpcHolograms.patchNameplate(npc.id, "true")
                    OriginNpcHolograms.patchHologram(npc.id, listOf("Погладить"), viewRange = 12)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onGuideClick(event: NPCRightClickEvent) {
        if (event.isCancelled) return
        val npc = event.npc
        val player = event.clicker
        if (npc.id != config.guideId || npc.uniqueId != config.guideUuid || !nearGuide(player)) return
        event.isCancelled = true
        event.setDelayedCancellation(true)
        val current = progress.current(player.uniqueId, System.currentTimeMillis())
        if (current != null) {
            if (current.ready) claim(player) else showProgress(player, current)
            return
        }
        val playerId = player.uniqueId
        val requestId = progress.requestStatus(playerId) ?: return
        val token = tasks.token()
        (mountService()?.dailyCareBoostStatus(playerId)
            ?: java.util.concurrent.CompletableFuture.failedFuture<ArcMountService.MountCareBoostStatus>(
                IllegalStateException("Mount care boost service is unavailable"),
            )).whenComplete { status, failure ->
            tasks.runSync(token) {
                if (!progress.finishStatus(playerId, requestId)) return@runSync
                val online = Bukkit.getPlayer(playerId)?.takeIf(::nearGuide) ?: return@runSync
                if (failure != null) {
                    warn("ORIGIN_MOUNT_CARE phase=STATUS_FAILED player={}", playerId, failure)
                    feedback(online, config.line("unavailable"))
                } else if (status.nextClaimInMillis > 0) {
                    feedback(online, config.line("cooldown", "minutes" to minutes(status.nextClaimInMillis)))
                    if (status.active) online.sendActionBar(Component.text(config.line("active", "minutes" to minutes(status.remainingMillis)), NamedTextColor.GOLD))
                } else {
                    progress.begin(playerId, System.currentTimeMillis())
                    feedback(online, config.line("introduction", "required" to config.requiredAnimals))
                }
            }
        }
    }

    private fun onPet(player: Player, npc: NPC) {
        val now = System.currentTimeMillis()
        val previous = progress.current(player.uniqueId, now) ?: return
        val current = progress.pet(player.uniqueId, npc.uniqueId, now) ?: return
        if (current.animals.size != previous.animals.size) showProgress(player, current)
    }

    private fun showProgress(player: Player, session: MountYardCareSession) {
        val text = if (session.ready) config.line("ready") else config.line("progress", "count" to session.animals.size, "required" to config.requiredAnimals)
        player.sendActionBar(Component.text(text, NamedTextColor.GOLD))
    }

    private fun claim(player: Player) {
        val playerId = player.uniqueId
        val session = progress.claim(playerId, System.currentTimeMillis()) ?: return
        val token = tasks.token()
        (mountService()?.claimDailyCareBoost(playerId, session.id)
            ?: java.util.concurrent.CompletableFuture.failedFuture<ArcMountService.MountCareBoostClaimOutcome>(
                IllegalStateException("Mount care boost service is unavailable"),
            )).whenComplete { result, failure ->
            tasks.runSync(token) {
                if (failure != null) {
                    if (!progress.retry(playerId, session.id)) return@runSync
                    warn("ORIGIN_MOUNT_CARE phase=CLAIM_UNCONFIRMED player={} request={}", playerId, session.id, failure)
                    Bukkit.getPlayer(playerId)?.takeIf(::nearGuide)?.let { feedback(it, config.line("unavailable")) }
                    return@runSync
                }
                if (!progress.complete(playerId, session.id)) return@runSync
                val online = Bukkit.getPlayer(playerId) ?: return@runSync
                val record = result.record
                if (result.result() == ArcMountService.MountCareBoostClaimOutcome.Claim.ALREADY_GRANTED && record.requestId != session.id) {
                    feedback(online, config.line("cooldown", "minutes" to minutes(record.nextClaimAtMillis - System.currentTimeMillis())))
                    return@runSync
                }
                val reward = config.line("reward", "percent" to ((record.multiplier - 1.0) * 100).roundToInt(),
                    "hours" to ((record.expiresAtMillis - record.grantedAtMillis) / 3_600_000L))
                online.sendMessage(Component.newline()
                    .append(Component.text("  ${config.line("completed")}", NamedTextColor.GREEN))
                    .append(Component.newline()).append(Component.text("  $reward", NamedTextColor.GOLD))
                    .append(Component.newline()))
            }
        }
    }

    private fun nearGuide(player: Player): Boolean {
        if (!player.isOnline || player.world.name != config.world) return false
        val npc = CitizensAPI.getNPCRegistry().getById(config.guideId) ?: return false
        if (npc.uniqueId != config.guideUuid || !npc.isSpawned) return false
        val location = npc.entity.location
        return location.world == player.world && player.location.distanceSquared(location) <= config.range * config.range
    }

    private fun feedback(player: Player, text: String) {
        player.sendMessage(Component.text("${config.guideName} » ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.WHITE)))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        progress.clearPlayer(event.player.uniqueId)
    }

    override fun close() {
        tasks.close()
        HandlerList.unregisterAll(this)
        HandlerList.unregisterAll(animals)
        animals.clear()
        progress.clear()
    }

    private fun minutes(millis: Long): Long = ceil(millis.coerceAtLeast(0) / 60_000.0).toLong()
}
