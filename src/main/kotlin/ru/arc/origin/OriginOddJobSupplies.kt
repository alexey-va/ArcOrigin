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
internal class OriginOddJobSupplies {
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
        stock.storage.setItem(4, item(player, job, "Железная заготовка"))
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
    fun requiresWorkpiece(job: OriginOddJobSession): Boolean = job.kind == OriginOddJobKind.FORGE && job.stage in setOf(
        OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_PLACE, OriginOddJobStage.FORGE_QUENCH, OriginOddJobStage.RETURN)

    fun canTransfer(player: Player, job: OriginOddJobSession): Boolean = when (job.stage) {
        OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_HEATING -> has(player, job) || hasSpace(player)
        OriginOddJobStage.FORGE_HAMMER -> job.forge?.remainingStrikes != 1 || hasSpace(player)
        OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_PLACE, OriginOddJobStage.FORGE_QUENCH -> has(player, job)
        else -> true
    }

    fun sync(player: Player, job: OriginOddJobSession): Boolean {
        if (job.kind != OriginOddJobKind.FORGE) return true
        val label = when (job.stage) {
            OriginOddJobStage.FORGE_HEAT -> if (job.variant == OriginOddJobVariant.FORGE_NEW) "Железная заготовка" else "Подкова для правки"
            OriginOddJobStage.FORGE_PLACE -> if (job.variant == OriginOddJobVariant.FORGE_NEW) "Раскалённая заготовка" else "Раскалённая подкова"
            OriginOddJobStage.FORGE_QUENCH -> "Выкованная подкова"
            OriginOddJobStage.RETURN -> "Закалённая подкова"
            else -> null
        }
        if (label == null) { clear(player, closeStock = false); return true }
        val current = slot(player, job)
        val slot = current.takeIf { it >= 0 } ?: emptySlot(player)
        if (slot < 0) return false
        val item = item(player, job, label)
        if (player.inventory.getItem(slot) != item) player.inventory.setItem(slot, item)
        return true
    }

    fun clear(player: Player, closeStock: Boolean = true) {
        player.inventory.contents.forEachIndexed { index, item -> if (isWorkpiece(item)) player.inventory.setItem(index, null) }
        if (isWorkpiece(player.itemOnCursor)) player.setItemOnCursor(null)
        if (closeStock && stock(player.openInventory) != null) player.closeInventory()
    }

    private fun item(player: Player, job: OriginOddJobSession, label: String): ItemStack = ItemStack(Material.STRUCTURE_VOID).also {
        it.editMeta { meta ->
            // Service material has no vanilla recipe or shop price; the client still shows an iron blank.
            meta.setItemModel(NamespacedKey.minecraft("iron_ingot"))
            meta.displayName(Component.text(label, TextColor.color(0xFFCA82)).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(Component.text("Рабочий материал · отнеси к отмеченной станции", TextColor.color(0xE8DFD2)).decoration(TextDecoration.ITALIC, false)))
            meta.persistentDataContainer.set(key, PersistentDataType.STRING, "${player.uniqueId}:${job.id}")
        }
    }
}
