package ru.arc.origin

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.origin.menu.OriginMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.core.whenCompleteSync
import ru.arc.origin.api.ArcOriginAuctions
import ru.arc.origin.util.customModelDataOrNull
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.floor
import kotlin.math.PI

internal object AuctionShowcasePlanner {
    fun select(
        listings: List<ArcOriginAuctions.Listing>,
        slotCount: Int,
        page: Int,
    ): List<ArcOriginAuctions.Listing?> {
        require(slotCount > 0) { "slotCount must be positive" }
        val distinct =
            listings.distinctBy { listing ->
                listing.item.type to listing.item.customModelDataOrNull
            }
        if (distinct.isEmpty()) return List(slotCount) { null }
        val start =
            if (distinct.size <= slotCount) {
                0
            } else {
                Math.floorMod(page.toLong() * slotCount, distinct.size.toLong()).toInt()
            }
        return List(slotCount) { slot ->
            if (slot < distinct.size) distinct[(start + slot) % distinct.size] else null
        }
    }
}

internal class AuctionShowcaseManager(
    private val displays: PaperPacketDisplays,
    private val store: AuctionPedestalStore = AuctionPedestalStore(ArcOriginPlugin.instance.legacyDataPath),
) {
    private data class Pedestal(
        val spec: AuctionPedestalSpec,
        val base: PacketBlockDisplay,
        val item: PacketItemDisplay,
        val text: PacketTextDisplay,
        val interaction: Interaction,
        var listingId: Int? = null,
    )

    private val tasks = LifecycleTaskScope()
    // IO completions survive a config reload, but cannot re-enter after shutdown.
    private val persistenceTasks = LifecycleTaskScope()
    private var saved: AuctionPedestalSnapshot? = null
    private var loaded = false
    private var persistenceFailed = false
    private var saving = false
    private val pedestals = mutableListOf<Pedestal>()
    private val interactionSlots = mutableMapOf<UUID, Pedestal>()
    private val clickTimes = mutableMapOf<UUID, Long>()

    private var config: OriginSpawnConfig? = null
    private var cyclePage = 0
    private var rotation = 0f

    init {
        store.load().whenCompleteSync(persistenceTasks, persistenceTasks.token()) { snapshot, failure ->
            if (failure != null) {
                persistenceFailed = true
                warn("Failed to load Origin auction pedestal positions; showcase remains unavailable", failure)
            } else {
                saved = snapshot
                loaded = true
                reconcile()
            }
        }
    }

    fun apply(next: OriginSpawnConfig) {
        config = next
        reconcile()
    }

    private fun specs(): List<AuctionPedestalSpec> = saved?.pedestals ?: config?.pedestals.orEmpty()

    private fun reconcile() {
        val token = tasks.restart()
        removeEntities()
        cyclePage = 0
        rotation = 0f
        val next = config ?: return
        if (!loaded || persistenceFailed || !next.enabled || !next.showcaseEnabled) return
        val world = world(next.worldName) ?: return
        val positions = specs()
        if (positions.isEmpty()) {
            info("Origin auction showcase ready: world={}, pedestals=0", next.worldName)
            return
        }
        val chunks = positions.map { floor(it.x).toInt() shr 4 to (floor(it.z).toInt() shr 4) }.distinct()
        CompletableFuture.allOf(*chunks.map { (x, z) -> world.getChunkAtAsync(x, z, false) }.toTypedArray())
            .whenCompleteSync(tasks, token) { _, failure ->
                if (failure != null) {
                    warn("Failed to load Origin auction showcase chunks", failure)
                    return@whenCompleteSync
                }
                cleanupTaggedEntities(world, positions + next.pedestals)
                positions.forEach { spec -> pedestals += spawnPedestal(world, spec) }
                refresh(advance = false)
                tasks.runTimer(token, next.cycleTicks, next.cycleTicks) { refresh(advance = false) }
                tasks.runTimer(token, next.pageTicks, next.pageTicks) { refresh(advance = true) }
                tasks.runTimer(token, next.rotationTicks, next.rotationTicks) { rotate(next.rotationTicks) }
                info("Origin auction showcase ready: world={}, pedestals={}", next.worldName, pedestals.size)
            }
    }

    /** Accepts one edit on the server thread; disk IO completes before displays change. */
    fun add(player: Player): String? {
        editFailure(player)?.let { return it }
        val block = player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER)?.hitBlock ?: return "target-required"
        val region = config?.chunkRegion ?: return "not-ready"
        if ((block.x shr 4) !in region.minX..region.maxX ||
            (block.z shr 4) !in region.minZ..region.maxZ
        ) return "outside-area"
        val box = block.boundingBox
        if (!block.type.isSolid || box.height <= 0.0) return "invalid-target"
        val top = box.maxY
        if (top + AuctionPedestalRules.STAND_HEIGHT >= block.world.maxHeight) return "invalid-target"
        // Leave enough clear vertical space for the item and label above the base.
        if ((kotlin.math.ceil(top).toInt()..floor(top + AuctionPedestalRules.STAND_HEIGHT).toInt()).any {
                !block.world.getBlockAt(block.x, it, block.z).isPassable
            }) return "invalid-target"
        val candidate = AuctionPedestalRules.create(
            block.x + 0.5, top, block.z + 0.5,
            ((player.location.yaw % 360f) + 360f) % 360f,
        )
        AuctionPedestalRules.placementFailure(candidate, specs())?.let { return it.name.lowercase(Locale.ROOT) }
        persist(player, specs() + candidate, "saved")
        return null
    }

    private fun editFailure(player: Player): String? = when {
        !player.hasPermission(ADMIN_PERMISSION) -> "admin-only"
        !player.world.name.equals(config?.worldName, ignoreCase = true) -> "wrong-world"
        !loaded || persistenceFailed || config?.enabled != true || config?.showcaseEnabled != true -> "not-ready"
        saving -> "saving"
        else -> null
    }

    private fun persist(player: Player, positions: List<AuctionPedestalSpec>, success: String) {
        saving = true
        val snapshot = AuctionPedestalSnapshot(pedestals = positions.toList())
        store.save(snapshot).whenCompleteSync(persistenceTasks, persistenceTasks.token()) { _, failure ->
            saving = false
            if (failure != null) {
                persistenceFailed = true
                warn("Failed to persist Origin auction pedestal edit; further edits disabled", failure)
                if (player.isOnline) player.sendMessage(config?.message("save-failed", player.locale().toLanguageTag()) ?: Component.empty())
                return@whenCompleteSync
            }
            saved = snapshot
            reconcile()
            if (player.isOnline) player.sendMessage(config?.message(success, player.locale().toLanguageTag()) ?: Component.empty())
        }
    }

    fun handle(event: PlayerInteractEntityEvent): Boolean {
        if (event.hand != EquipmentSlot.HAND) return false
        if (OriginServices.caseFiles?.isCaseFile(event.player.inventory.itemInMainHand) == true) return false
        val pedestal = interactionSlots[event.rightClicked.uniqueId] ?: return false
        event.isCancelled = true
        val player = event.player
        val now = System.currentTimeMillis()
        val debounce = config?.clickDebounceMillis ?: 500L
        val previous = clickTimes.put(player.uniqueId, now)
        if (previous != null && now - previous < debounce) return true

        if (player.hasPermission(ADMIN_PERMISSION)) {
            OriginMenus.beginDialogFlow(player)
            openAdmin(player, pedestal.spec.id)
        } else {
            openListing(player, pedestal)
        }
        return true
    }

    private fun openListing(player: Player, pedestal: Pedestal) {
        val listingId = pedestal.listingId ?: return
        val auctions = OriginServices.auctions
        if (auctions == null) {
            player.sendActionBar(config?.message("unavailable", player.locale().toLanguageTag()) ?: Component.empty())
            return
        }
        auctions.openShowcaseListing(player, listingId) { result ->
            when (result) {
                ArcOriginAuctions.OpenResult.CONFIRMATION_OPENED,
                ArcOriginAuctions.OpenResult.INSUFFICIENT_FUNDS,
                ArcOriginAuctions.OpenResult.BUSY,
                -> Unit
                ArcOriginAuctions.OpenResult.STALE -> {
                    if (player.isOnline) player.sendActionBar(config?.message("stale", player.locale().toLanguageTag()) ?: Component.empty())
                    refresh(advance = false)
                }
                ArcOriginAuctions.OpenResult.UNAVAILABLE ->
                    if (player.isOnline) player.sendActionBar(config?.message("unavailable", player.locale().toLanguageTag()) ?: Component.empty())
                ArcOriginAuctions.OpenResult.FAILED ->
                    if (player.isOnline) player.sendActionBar(config?.message("failed", player.locale().toLanguageTag()) ?: Component.empty())
            }
        }
    }

    private fun currentAdminPedestal(player: Player, id: String, token: LifecycleTaskScope.Token): Pedestal? {
        val failure = editFailure(player)
        if (failure != null) {
            player.sendMessage(config?.message(failure, player.locale().toLanguageTag()) ?: Component.empty())
            return null
        }
        val current = pedestals.firstOrNull { it.spec.id == id }
        if (!player.isOnline || !tasks.isCurrent(token) || current == null ||
            current.base.location.distanceSquared(player.location) > 36.0
        ) {
            player.sendMessage(config?.message("stale-pedestal", player.locale().toLanguageTag()) ?: Component.empty())
            return null
        }
        return current
    }

    internal fun openAdmin(player: Player, id: String) {
        if (config == null) return
        val token = tasks.token()
        val pedestal = currentAdminPedestal(player, id, token) ?: return
        val current = config ?: return
        val listingId = pedestal.listingId
        val buttons = mutableListOf<PaperDialogButton>()
        if (listingId != null) buttons += PaperDialogButton(
            id = PaperDialogActionId.of("open_lot"),
            label = current.adminDialogText("open-lot", player.locale().toLanguageTag()),
            closeDialogBeforeAction = true,
            onClick = { context ->
                val active = currentAdminPedestal(context.player, id, token)
                if (active != null) {
                    if (active.listingId == listingId) openListing(context.player, active)
                    else context.player.sendMessage(current.message("stale", context.player.locale().toLanguageTag()))
                }
            },
        )
        buttons += PaperDialogButton(
            id = PaperDialogActionId.of("delete"),
            label = current.adminDialogText("delete", player.locale().toLanguageTag()),
            onClick = { context ->
                if (currentAdminPedestal(context.player, id, token) != null) openDelete(context.player, id, token)
            },
        )
        OriginMenus.openDialog(player, PaperDialogScreen(
            id = "auction.pedestal",
            title = current.adminDialogText("title", player.locale().toLanguageTag()),
            body = listOf(PaperDialogBody(
                if (listingId == null) current.adminDialogText("empty-body", player.locale().toLanguageTag()) else pedestal.text.text(),
            )),
            buttons = buttons,
        ), reopen = { openAdmin(player, id) })
    }

    private fun openDelete(player: Player, id: String, token: LifecycleTaskScope.Token) {
        val current = config ?: return
        if (currentAdminPedestal(player, id, token) == null) return
        OriginMenus.openDialog(player, PaperDialogScreen(
            id = "auction.pedestal.delete",
            title = current.adminDialogText("confirm-title", player.locale().toLanguageTag()),
            body = listOf(PaperDialogBody(current.adminDialogText("confirm-body", player.locale().toLanguageTag()))),
            buttons = listOf(
                PaperDialogButton(
                    id = PaperDialogActionId.of("confirm_delete"),
                    label = current.adminDialogText("confirm-delete", player.locale().toLanguageTag()),
                    closeDialogBeforeAction = true,
                    onClick = { context ->
                        if (currentAdminPedestal(context.player, id, token) != null) {
                            persist(context.player, specs().filterNot { it.id == id }, "removed")
                        }
                    },
                ),
                PaperDialogButton(
                    id = PaperDialogActionId.of("cancel"),
                    label = current.adminDialogText("cancel", player.locale().toLanguageTag()),
                    onClick = { context ->
                        if (currentAdminPedestal(context.player, id, token) != null) openAdmin(context.player, id)
                    },
                ),
            ),
        ), reopen = { openDelete(player, id, token) })
    }

    fun forget(player: Player) {
        clickTimes.remove(player.uniqueId)
    }

    fun shutdown() {
        tasks.close()
        persistenceTasks.close()
        store.closeAsync().whenComplete { _, failure ->
            if (failure != null) warn("Failed to finish saving Origin auction pedestals at shutdown", failure)
        }
        removeEntities()
        config = null
    }

    private fun refresh(advance: Boolean) {
        if (pedestals.isEmpty()) return
        val listings = OriginServices.auctions?.showcaseListings().orEmpty()
        if (advance && listings.isNotEmpty()) {
            cyclePage = if (cyclePage == Int.MAX_VALUE) 0 else cyclePage + 1
        }
        val selected = AuctionShowcasePlanner.select(listings, pedestals.size, cyclePage)
        pedestals.zip(selected).forEach { (pedestal, listing) ->
            render(pedestal, listing)
        }
    }

    private fun render(
        pedestal: Pedestal,
        listing: ArcOriginAuctions.Listing?,
    ) {
        val current = config ?: return
        pedestal.listingId = listing?.id
        pedestal.interaction.isResponsive = true
        pedestal.item.itemStack = listing?.item?.clone() ?: ItemStack(Material.AIR)
        pedestal.text.text(
            listing?.let { current.listingText(it.itemName, it.sellerName, it.price) } ?: Component.empty(),
        )
    }

    private fun rotate(periodTicks: Long) {
        // Keep the angle unwrapped so Display interpolation never crosses 2π -> 0.
        rotation += (2.0 * PI * periodTicks / 240.0).toFloat()
        pedestals.forEach { pedestal ->
            if (pedestal.listingId == null || !pedestal.item.isValid) return@forEach
            val pulse = kotlin.math.sin(rotation.toDouble()).toFloat()
            pedestal.item.interpolationDelay = 0
            pedestal.item.transformation =
                Transformation(
                    Vector3f(0f, ITEM_BOB * pulse, 0f),
                    AxisAngle4f(rotation, 0f, 1f, 0f),
                    Vector3f(
                        ITEM_SCALE * (1f + ITEM_SCALE_PULSE * pulse),
                        ITEM_SCALE * (1f + ITEM_SCALE_PULSE * pulse),
                        ITEM_SCALE * (1f + ITEM_SCALE_PULSE * pulse),
                    ),
                    AxisAngle4f(),
                )
        }
    }

    private fun spawnPedestal(world: org.bukkit.World, spec: AuctionPedestalSpec): Pedestal {
        val created = mutableListOf<PacketDisplay>()
        var createdInteraction: Interaction? = null
        try {
            val floor = Location(world, spec.x, spec.y, spec.z, spec.yaw, 0f)
            val base = displays.spawnBlock(floor, Material.POLISHED_DEEPSLATE.createBlockData()).also(created::add)
            configureDisplay(base)
            base.block = Material.POLISHED_DEEPSLATE.createBlockData()
            base.transformation =
                Transformation(
                    Vector3f(-0.45f, 0f, -0.45f),
                    AxisAngle4f(),
                    Vector3f(0.9f, 0.24f, 0.9f),
                    AxisAngle4f(),
                )

            val item = displays.spawnItem(floor.clone().add(0.0, 1.22, 0.0), ItemStack(Material.AIR)).also(created::add)
            configureDisplay(item)
            item.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            item.interpolationDelay = 0
            item.interpolationDuration = maxOf((config?.rotationTicks ?: 2L).toInt(), 2)
            item.transformation =
                Transformation(Vector3f(), AxisAngle4f(), Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE), AxisAngle4f())

            val text = displays.spawnText(floor.clone().add(0.0, 3.62, 0.0), Component.empty()).also(created::add)
            configureDisplay(text)
            text.billboard = Display.Billboard.CENTER
            text.alignment = TextDisplay.TextAlignment.CENTER
            text.lineWidth = 220
            text.backgroundColor = Color.fromARGB(96, 0, 0, 0)
            text.isShadowed = true
            text.isSeeThrough = false

            val interaction = world.spawn(floor.clone().add(0.0, 0.08, 0.0), Interaction::class.java)
                .also { createdInteraction = it }
            interaction.interactionWidth = 2.8f
            interaction.interactionHeight = 4.15f
            interaction.isResponsive = false
            interaction.isPersistent = false
            interaction.isInvulnerable = true

            interaction.addScoreboardTag(ENTITY_TAG)
            return Pedestal(spec, base, item, text, interaction).also {
                interactionSlots[interaction.uniqueId] = it
            }
        } catch (failure: Exception) {
            created.asReversed().forEach { display ->
                runCatching { display.remove() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            runCatching { createdInteraction?.remove() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun configureDisplay(display: PacketDisplay) {
        display.billboard = Display.Billboard.FIXED
        display.brightness = Display.Brightness(12, 15)
        display.shadowRadius = 0f
        display.shadowStrength = 0f
        display.viewRange = 1.5f
        display.displayWidth = 4f
        display.displayHeight = 4.5f
    }

    private fun cleanupTaggedEntities(world: org.bukkit.World, positions: List<AuctionPedestalSpec>) {
        positions
            .map { (floor(it.x).toInt() shr 4) to (floor(it.z).toInt() shr 4) }
            .distinct()
            .forEach { (x, z) ->
                if (!world.isChunkLoaded(x, z)) return@forEach
                world.getChunkAt(x, z).entities
                    .filter { ENTITY_TAG in it.scoreboardTags }
                    .forEach(Entity::remove)
            }
    }

    private fun removeEntities() {
        pedestals.forEach { pedestal ->
            listOf(pedestal.base, pedestal.item, pedestal.text).forEach(PacketDisplay::remove)
            if (pedestal.interaction.isValid) pedestal.interaction.remove()
        }
        pedestals.clear()
        interactionSlots.clear()
        clickTimes.clear()
    }

    private fun world(name: String): org.bukkit.World? =
        Bukkit.getWorld(name)
            ?: Bukkit.getWorlds().firstOrNull { it.name.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) }

    internal companion object {
        const val ADMIN_PERMISSION = "arc.origin.auction.admin"
        const val ENTITY_TAG = "arc_origin_auction_showcase"
        const val ITEM_SCALE = 3.75f
        const val ITEM_SCALE_PULSE = 0.035f
        const val ITEM_BOB = 0.045f
    }
}
