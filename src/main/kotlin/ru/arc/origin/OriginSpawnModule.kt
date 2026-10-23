package ru.arc.origin

import dev.lone.itemsadder.api.Events.CustomBlockBreakEvent
import dev.lone.itemsadder.api.Events.CustomBlockPlaceEvent
import dev.lone.itemsadder.api.Events.FurnitureBreakEvent
import dev.lone.itemsadder.api.Events.FurniturePlaceEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.core.PluginModule

object OriginSpawnModule : PluginModule, Listener {
    override val name = "OriginSpawn"
    override val priority = 22

    private var currentConfig: OriginSpawnConfig? = null
    private var chunks: OriginSpawnChunkManager? = null
    private var showcase: AuctionShowcaseManager? = null
    private var breakProtection: OriginBreakProtection? = null
    private var itemsAdderProtection: Listener? = null
    private var breakBypassPermission = OriginSpawnConfig.DEFAULT_BREAK_BYPASS_PERMISSION

    override fun init() {
        breakProtection = OriginBreakProtection(BukkitOriginBreakRuntime())
        Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            itemsAdderProtection = OriginItemsAdderProtection().also { Bukkit.getPluginManager().registerEvents(it, ArcOriginPlugin.instance) }
        }
        chunks = OriginSpawnChunkManager(OriginServices.chunkLeases)
        showcase = AuctionShowcaseManager(ArcOriginPlugin.instance.propDisplays())
        apply(OriginSpawnConfig.load(ArcOriginPlugin.instance.legacyDataPath, mergeDefaults = false))
    }

    override fun reload() {
        apply(OriginSpawnConfig.load(ArcOriginPlugin.instance.legacyDataPath, mergeDefaults = false))
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        itemsAdderProtection?.let(HandlerList::unregisterAll)
        itemsAdderProtection = null
        showcase?.shutdown()
        showcase = null
        currentConfig = null
        breakProtection?.shutdown()
        breakProtection = null
        chunks?.shutdown()
        chunks = null
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        showcase?.handle(event)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        showcase?.forget(event.player)
        breakProtection?.forget(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        val protected =
            breakProtection?.handle(
                OriginBreakIllusionTarget(
                    playerId = event.player.uniqueId,
                    worldId = event.block.world.uid,
                    worldName = event.block.world.name,
                    x = event.block.x,
                    y = event.block.y,
                    z = event.block.z,
                    originalBlockData = event.block.blockData.asString,
                ),
                bypassProtection = event.player.hasPermission(breakBypassPermission),
            ) == true
        if (!protected) return
        event.isDropItems = false
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) {
        if (!deniesPlacement(event.player, event.blockPlaced.world.name)) return
        event.setBuild(false)
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (deniesPlacement(event.player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (deniesPlacement(event.player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (deniesPlacement(player, event.entity.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        if (deniesPlacement(player, event.entity.world.name)) event.isCancelled = true
    }

    internal fun deniesPlacement(player: Player, worldName: String): Boolean =
        breakProtection?.isProtected(worldName, player.hasPermission(breakBypassPermission)) == true

    internal fun addAuctionPedestal(player: Player): String? {
        val current = showcase ?: return "not-ready"
        return current.add(player)
    }

    internal fun auctionPedestalMessage(key: String, locale: String = "ru"): net.kyori.adventure.text.Component =
        currentConfig?.message(key, locale) ?: ru.arc.util.TextUtils.mm("<#ff9f0f>Витрина сейчас недоступна.")

    private fun apply(config: OriginSpawnConfig) {
        currentConfig = config
        chunks?.apply(config)
        showcase?.apply(config)
        breakBypassPermission = config.regenerativeBreakingBypassPermission
        breakProtection?.apply(
            OriginBreakProtectionSettings(
                protected = config.enabled,
                illusionEnabled = config.regenerativeBreakingEnabled,
                worldName = config.worldName,
                restoreDelayTicks = config.regenerativeBreakingRestoreDelayTicks,
                feedback = config.regenerativeBreakingFeedback,
            ),
        )
    }
}

private class OriginItemsAdderProtection : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onFurniturePlace(event: FurniturePlaceEvent) {
        if (OriginSpawnModule.deniesPlacement(event.player, event.player.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onFurnitureBreak(event: FurnitureBreakEvent) {
        if (OriginSpawnModule.deniesPlacement(event.player, event.player.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCustomBlockPlace(event: CustomBlockPlaceEvent) {
        if (OriginSpawnModule.deniesPlacement(event.player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCustomBlockBreak(event: CustomBlockBreakEvent) {
        if (OriginSpawnModule.deniesPlacement(event.player, event.block.world.name)) event.isCancelled = true
    }
}
