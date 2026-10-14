package ru.arc.origin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.TileState
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import java.util.UUID

internal data class OriginBreakProtectionSettings(
    val protected: Boolean,
    val illusionEnabled: Boolean,
    val worldName: String,
    val restoreDelayTicks: Long,
    val feedback: OriginBreakFeedbackSettings = OriginBreakFeedbackSettings.disabled(),
)

internal data class OriginBreakFeedbackSettings(
    val enabled: Boolean,
    val countIntervalTicks: Long,
    val messageCooldownTicks: Long,
    val resetAfterTicks: Long,
    val tiers: List<OriginBreakFeedbackTier>,
) {
    companion object {
        fun disabled() = OriginBreakFeedbackSettings(false, 20L, 60L, 2_400L, emptyList())
    }
}

internal data class OriginBreakFeedbackTier(
    val fromAttempt: Int,
    val messages: List<String>,
)

internal data class OriginBreakIllusionTarget(
    val playerId: UUID,
    val worldId: UUID,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalBlockData: String,
) {
    val key: OriginBreakIllusionKey
        get() = OriginBreakIllusionKey(playerId, worldId, x, y, z)
}

internal data class OriginBreakIllusionKey(
    val playerId: UUID,
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)

internal interface OriginBreakRuntime {
    fun schedule(delayTicks: Long, action: () -> Unit): ScheduledTask

    fun currentBlockData(target: OriginBreakIllusionTarget): String?

    fun showBroken(target: OriginBreakIllusionTarget): Boolean

    fun restore(target: OriginBreakIllusionTarget)

    fun currentTick(): Long

    fun randomIndex(bound: Int): Int

    fun showFeedback(playerId: UUID, message: String): Boolean
}

internal class BukkitOriginBreakRuntime : OriginBreakRuntime {
    override fun schedule(delayTicks: Long, action: () -> Unit): ScheduledTask =
        Tasks.scheduler.runLater(delayTicks, Runnable(action))

    override fun currentBlockData(target: OriginBreakIllusionTarget): String? {
        val world = Bukkit.getWorld(target.worldId) ?: return null
        if (!world.isChunkLoaded(target.x shr 4, target.z shr 4)) return null
        return world.getBlockAt(target.x, target.y, target.z).blockData.asString
    }

    override fun showBroken(target: OriginBreakIllusionTarget): Boolean {
        val player = Bukkit.getPlayer(target.playerId)?.takeIf { it.isOnline } ?: return false
        val world = Bukkit.getWorld(target.worldId) ?: return false
        if (player.world.uid != world.uid || !world.isChunkLoaded(target.x shr 4, target.z shr 4)) return false
        player.sendBlockChange(
            world.getBlockAt(target.x, target.y, target.z).location,
            Material.AIR.createBlockData(),
        )
        return true
    }

    override fun restore(target: OriginBreakIllusionTarget) {
        val player = Bukkit.getPlayer(target.playerId)?.takeIf { it.isOnline } ?: return
        val world = Bukkit.getWorld(target.worldId) ?: return
        if (player.world.uid != world.uid || !world.isChunkLoaded(target.x shr 4, target.z shr 4)) return
        val block = world.getBlockAt(target.x, target.y, target.z)
        player.sendBlockChange(block.location, block.blockData)
        (block.state as? TileState)?.let { player.sendBlockUpdate(block.location, it) }
    }

    override fun currentTick(): Long = Bukkit.getCurrentTick().toLong()

    override fun randomIndex(bound: Int): Int = kotlin.random.Random.nextInt(bound)

    override fun showFeedback(playerId: UUID, message: String): Boolean {
        val player = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline } ?: return false
        player.sendActionBar(Component.text(message, FEEDBACK_COLOR))
        return true
    }

    private companion object {
        val FEEDBACK_COLOR: TextColor = TextColor.color(0xE6FFF3)
    }
}

internal class OriginBreakProtection(
    private val runtime: OriginBreakRuntime,
) {
    private data class PendingIllusion(
        val target: OriginBreakIllusionTarget,
        val generation: Long,
        var showTask: ScheduledTask? = null,
        var restoreTask: ScheduledTask? = null,
        var visible: Boolean = false,
    )

    private data class FeedbackKey(
        val playerId: UUID,
        val worldId: UUID,
    )

    private data class FeedbackState(
        var attempts: Int = 0,
        var lastCountTick: Long? = null,
        var lastAttemptTick: Long? = null,
        var lastMessageTick: Long? = null,
        var lastMessage: String? = null,
    ) {
        fun reset() {
            attempts = 0
            lastCountTick = null
            lastAttemptTick = null
            lastMessageTick = null
            lastMessage = null
        }
    }

    private var settings = OriginBreakProtectionSettings(false, false, "", 100L)
    private var generation = 0L
    private val pending = mutableMapOf<OriginBreakIllusionKey, PendingIllusion>()
    private val feedback = mutableMapOf<FeedbackKey, FeedbackState>()

    fun apply(newSettings: OriginBreakProtectionSettings) {
        if (settings == newSettings) return
        restoreAll()
        feedback.clear()
        settings = newSettings
    }

    fun handle(
        target: OriginBreakIllusionTarget,
        bypassProtection: Boolean = false,
    ): Boolean {
        val current = settings
        if (!isProtected(target.worldName, bypassProtection)) return false
        showFeedback(target, current.feedback)
        if (!current.illusionEnabled) return true

        retire(target.key, restore = true)
        val pendingIllusion = PendingIllusion(target, ++generation)
        pending[target.key] = pendingIllusion
        pendingIllusion.showTask = runtime.schedule(SHOW_DELAY_TICKS) {
            show(target.key, pendingIllusion.generation)
        }
        return true
    }

    fun isProtected(worldName: String, hasBuildPermission: Boolean): Boolean =
        settings.protected && worldName == settings.worldName && !hasBuildPermission

    fun forget(playerId: UUID) {
        pending.keys.filter { it.playerId == playerId }.forEach { retire(it, restore = false) }
        feedback.keys.removeIf { it.playerId == playerId }
    }

    fun shutdown() {
        restoreAll()
        feedback.clear()
        settings = settings.copy(protected = false, illusionEnabled = false)
    }

    private fun showFeedback(
        target: OriginBreakIllusionTarget,
        feedbackSettings: OriginBreakFeedbackSettings,
    ) {
        if (!feedbackSettings.enabled || feedbackSettings.tiers.isEmpty()) return
        val now = runtime.currentTick()
        val state = feedback.getOrPut(FeedbackKey(target.playerId, target.worldId), ::FeedbackState)
        val lastAttempt = state.lastAttemptTick
        if (lastAttempt != null && (now < lastAttempt || now - lastAttempt > feedbackSettings.resetAfterTicks)) {
            state.reset()
        }
        state.lastAttemptTick = now

        val lastCount = state.lastCountTick
        if (lastCount == null || now < lastCount || now - lastCount >= feedbackSettings.countIntervalTicks) {
            state.attempts++
            state.lastCountTick = now
        }

        val lastMessageTick = state.lastMessageTick
        if (lastMessageTick != null && now >= lastMessageTick && now - lastMessageTick < feedbackSettings.messageCooldownTicks) {
            return
        }
        val tier = feedbackSettings.tiers.lastOrNull { state.attempts >= it.fromAttempt } ?: return
        val choices = tier.messages.filterNot { tier.messages.size > 1 && it == state.lastMessage }
        if (choices.isEmpty()) return
        val message = choices[runtime.randomIndex(choices.size).coerceIn(0, choices.lastIndex)]
        if (runtime.showFeedback(target.playerId, message)) {
            state.lastMessageTick = now
            state.lastMessage = message
        }
    }

    private fun show(key: OriginBreakIllusionKey, expectedGeneration: Long) {
        val entry = pending[key]?.takeIf { it.generation == expectedGeneration } ?: return
        if (runtime.currentBlockData(entry.target) != entry.target.originalBlockData || !runtime.showBroken(entry.target)) {
            retire(key, restore = false)
            return
        }
        entry.visible = true
        entry.restoreTask = runtime.schedule(settings.restoreDelayTicks) {
            retire(key, restore = true, expectedGeneration = expectedGeneration)
        }
    }

    private fun restoreAll() {
        pending.keys.toList().forEach { retire(it, restore = true) }
    }

    private fun retire(
        key: OriginBreakIllusionKey,
        restore: Boolean,
        expectedGeneration: Long? = null,
    ) {
        val entry = pending[key] ?: return
        if (expectedGeneration != null && entry.generation != expectedGeneration) return
        pending.remove(key)
        entry.showTask?.cancel()
        entry.restoreTask?.cancel()
        if (restore && entry.visible) runtime.restore(entry.target)
    }

    private companion object {
        const val SHOW_DELAY_TICKS = 1L
    }
}
