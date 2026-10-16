package ru.arc.origin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.Lidded
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** A private stock inventory and one request-bound workpiece; never replaces a player's item. */
internal class OriginOddJobSupplies(private val text: (String) -> String = { it }) {
    private val key = NamespacedKey("arcorigin", "odd_job_workpiece")

    internal class Stock(val playerId: UUID, val jobId: UUID, val block: Block) : InventoryHolder {
        val storage: Inventory = Bukkit.createInventory(this, 9, Component.text("Кузница · заготовки", TextColor.color(0x38302A)))
        override fun getInventory(): Inventory = storage
    }

    // Paper guarantees a top inventory; MockBukkit's default/closed view can temporarily omit it.
    @Suppress("UNNECESSARY_SAFE_CALL")
    fun stock(view: InventoryView): Stock? = view.topInventory?.holder as? Stock

    fun isWorkpiece(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.STRING) == true
    private fun belongs(item: ItemStack?, player: Player, job: OriginOddJobSession): Boolean =
        item?.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING) == "${player.uniqueId}:${job.id}"

    fun openStock(player: Player, job: OriginOddJobSession, block: Block) {
        val stock = Stock(player.uniqueId, job.id, block)
        val metal = job.forge?.metal ?: job.engraving?.metal ?: OriginForgeMetal.IRON
        stock.storage.setItem(4, item(player, job, "${if (job.engraving == null) "Заготовка" else "Пластина"} · ${metal.title}", metal.itemModel))
        player.openInventory(stock.storage)
        (block.state as? Lidded)?.open()
        player.playSound(block.location, Sound.BLOCK_CHEST_OPEN, 0.6f, 1f)
    }

    fun closeStock(player: Player, stock: Stock) {
        (stock.block.state as? Lidded)?.close()
        player.playSound(stock.block.location, Sound.BLOCK_CHEST_CLOSE, 0.5f, 1f)
    }

    private fun slot(player: Player, job: OriginOddJobSession): Int = player.inventory.storageContents.indexOfFirst { belongs(it, player, job) }
    private fun emptySlot(player: Player): Int = player.inventory.storageContents.indexOfFirst { it == null || it.type.isAir }
    fun has(player: Player, job: OriginOddJobSession): Boolean = slot(player, job) >= 0
    fun hasSpace(player: Player): Boolean = emptySlot(player) >= 0
    fun requiresWorkpiece(job: OriginOddJobSession): Boolean = yardItem(job) != null ||
        job.stage in setOf(OriginOddJobStage.HAUL_DELIVER, OriginOddJobStage.ENGRAVE_PLACE) ||
        job.kind == OriginOddJobKind.FORGE_ENGRAVE && job.stage == OriginOddJobStage.RETURN ||
        job.kind == OriginOddJobKind.FORGE && job.stage in setOf(
            OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_PLACE, OriginOddJobStage.FORGE_QUENCH, OriginOddJobStage.RETURN)

    fun canTransfer(player: Player, job: OriginOddJobSession): Boolean = when (job.stage) {
        OriginOddJobStage.HAUL_PICKUP, OriginOddJobStage.ENGRAVE_BLANK, OriginOddJobStage.ENGRAVE_COLLECT -> hasSpace(player)
        OriginOddJobStage.HAUL_DELIVER, OriginOddJobStage.ENGRAVE_PLACE -> has(player, job)
        OriginOddJobStage.YARD_HAY, OriginOddJobStage.YARD_WELL, OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_TACK -> hasSpace(player)
        OriginOddJobStage.YARD_FEED, OriginOddJobStage.YARD_WATER, OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_SADDLE -> has(player, job)
        OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_HEATING -> has(player, job) || hasSpace(player)
        OriginOddJobStage.FORGE_HAMMER -> job.forge?.remainingStrikes != 1 || hasSpace(player)
        OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_PLACE, OriginOddJobStage.FORGE_QUENCH -> has(player, job)
        else -> true
    }

    fun sync(player: Player, job: OriginOddJobSession): Boolean {
        if (job.kind == OriginOddJobKind.MOUNTYARD) {
            val model = yardItem(job)
            if (model == null) { clear(player, closeStock = false); return true }
            return putInHand(player, job, item(player, job, text("mountyard.items.$model"), model))
        }
        if (job.kind == OriginOddJobKind.FORGE_HAUL) {
            if (job.stage != OriginOddJobStage.HAUL_DELIVER) { clear(player, closeStock = false); return true }
            val cargo = requireNotNull(job.haul).cargo
            return putInHand(player, job, item(player, job, "Тяжёлый груз · ${cargo.title}", cargo.itemModel))
        }
        if (job.kind == OriginOddJobKind.FORGE_ENGRAVE) {
            if (job.stage !in setOf(OriginOddJobStage.ENGRAVE_PLACE, OriginOddJobStage.RETURN)) { clear(player, closeStock = false); return true }
            val work = requireNotNull(job.engraving)
            val label = if (job.stage == OriginOddJobStage.RETURN) "Гравировка «${work.word}»" else "Пластина · ${work.metal.title}"
            return putInHand(player, job, item(player, job, label, work.metal.itemModel))
        }
        if (job.kind != OriginOddJobKind.FORGE) return true
        val work = requireNotNull(job.forge)
        val label = when (job.stage) {
            OriginOddJobStage.FORGE_HEAT -> "Заготовка · ${work.metal.title}"
            OriginOddJobStage.FORGE_PLACE -> "Раскалённая заготовка · ${work.product.title}"
            OriginOddJobStage.FORGE_QUENCH -> "Выковано · ${work.product.title}"
            OriginOddJobStage.RETURN -> "Готово · ${work.product.title}"
            else -> null
        }
        if (label == null) { clear(player, closeStock = false); return true }
        val current = slot(player, job)
        val slot = current.takeIf { it >= 0 } ?: emptySlot(player)
        if (slot < 0) return false
        val item = item(player, job, label, work.metal.itemModel)
        if (player.inventory.getItem(slot) != item) player.inventory.setItem(slot, item)
        return true
    }

    private fun yardItem(job: OriginOddJobSession): String? = when (job.stage) {
        OriginOddJobStage.YARD_FEED -> "hay_block"
        OriginOddJobStage.YARD_WATER -> "water_bucket"
        OriginOddJobStage.YARD_GROOM -> "brush"
        OriginOddJobStage.YARD_SADDLE -> "saddle"
        OriginOddJobStage.RETURN -> if (job.variant == OriginOddJobVariant.YARD_GROOM) "brush" else null
        else -> null
    }

    /** Keep personal stacks intact even when the only free storage cell is outside the hotbar. */
    private fun putInHand(player: Player, job: OriginOddJobSession, item: ItemStack): Boolean {
        val available = slot(player, job).takeIf { it >= 0 } ?: emptySlot(player)
        if (available < 0) return false
        val hand = if (available < 9) available else player.inventory.heldItemSlot.also {
            player.inventory.setItem(available, player.inventory.getItem(it))
        }
        player.inventory.setItem(hand, item)
        player.inventory.heldItemSlot = hand
        return true
    }

    fun clear(player: Player, closeStock: Boolean = true) {
        player.inventory.contents.forEachIndexed { index, item -> if (isWorkpiece(item)) player.inventory.setItem(index, null) }
        if (isWorkpiece(player.itemOnCursor)) player.setItemOnCursor(null)
        if (closeStock && stock(player.openInventory) != null) player.closeInventory()
    }

    private fun item(player: Player, job: OriginOddJobSession, label: String, modelKey: String = "iron_ingot"): ItemStack = ItemStack(Material.STRUCTURE_VOID).also {
        it.editMeta { meta ->
            // Service material has no vanilla recipe or shop price; its model shows the work item.
            meta.setItemModel(NamespacedKey.minecraft(modelKey))
            meta.displayName(Component.text(label, TextColor.color(0xFFCA82)).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(Component.text(if (job.kind == OriginOddJobKind.MOUNTYARD) text("mountyard.item-lore")
                else "Рабочий материал · отнеси к отмеченной станции", TextColor.color(0xE8DFD2)).decoration(TextDecoration.ITALIC, false)))
            meta.persistentDataContainer.set(key, PersistentDataType.STRING, "${player.uniqueId}:${job.id}")
        }
    }
}
