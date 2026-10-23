package ru.arc.origin

import org.bukkit.Location
import org.bukkit.inventory.ItemStack
import ru.arc.origin.scene.OriginSceneResources
import ru.arc.logging.ArcLogging.info
import ru.arc.paper.display.PacketItemDisplay
import java.util.UUID

internal enum class DiningDrinkPhase { READY, RESERVED, CARRIED, SERVED }

/** One physical mug: reserve does not remove it from the counter; pickup does. */
internal class DiningDrinkOrder(val bar: DiningBar, val station: Location) {
    val token: UUID = UUID.randomUUID()
    var phase = DiningDrinkPhase.READY
        private set
    var tableId: String? = null
        private set
    var waiterId: Int? = null
        private set

    fun reserve(table: String, waiter: Int): Boolean {
        if (phase != DiningDrinkPhase.READY || waiter !in bar.waiterIds) return false
        tableId = table
        waiterId = waiter
        phase = DiningDrinkPhase.RESERVED
        return true
    }
    fun pickup(): Boolean {
        if (phase != DiningDrinkPhase.RESERVED) return false
        phase = DiningDrinkPhase.CARRIED
        return true
    }
    fun serve(): Boolean {
        if (phase != DiningDrinkPhase.CARRIED) return false
        phase = DiningDrinkPhase.SERVED
        return true
    }
    fun returnToCounter(): Boolean {
        if (phase == DiningDrinkPhase.SERVED) return false
        phase = DiningDrinkPhase.READY
        tableId = null
        waiterId = null
        return true
    }
}

/** Server-thread dining cargo, bounded to one pending mug per bar and one per table.
 * Navigation and player priority stay in the existing waiter service. These are
 * decorative drinks, never player inventory, purchases or persistent rewards.
 */
internal class OriginDiningDrinks(
    private val config: OriginDiningLifeConfig,
    private val item: (DiningProp) -> ItemStack?,
    private val createResources: () -> OriginSceneResources = { OriginSceneResources() },
) : AutoCloseable {
    private data class Counter(val order: DiningDrinkOrder, val resources: OriginSceneResources)
    private data class Served(val resources: OriginSceneResources, val anchor: Location, val emptyAt: Long,
        val clearAt: Long, var empty: Boolean = false)
    private val counters = linkedMapOf<Int, Counter>()
    private val tables = linkedMapOf<String, Served>()
    private val lastServed = mutableMapOf<String, Long>()
    private var ticks = 0L

    fun hasPending(barId: Int): Boolean = barId in counters
    fun needsDrink(table: String): Boolean = table !in tables && counters.values.none { it.order.tableId == table }
    fun lastServedAt(table: String): Long = lastServed[table] ?: Long.MIN_VALUE

    fun ready(bar: DiningBar, station: Location) {
        if (hasPending(bar.actorId)) return
        val stack = item(config.mug) ?: return
        val resources = createResources()
        try {
            resources.item("drink", station, stack, config.mug.scale, config.mug.lift)
            counters[bar.actorId] = Counter(DiningDrinkOrder(bar, station.clone()), resources)
            info("ORIGIN_DINING_DRINK phase=READY bar={}", bar.actorId)
        } catch (failure: Exception) { resources.cleanup(); throw failure }
    }

    fun reserve(table: String, waiter: Int): DiningDrinkOrder? {
        if (!needsDrink(table)) return null
        return counters.values.firstOrNull { it.order.reserve(table, waiter) }?.order
    }

    fun pickup(order: DiningDrinkOrder): ItemStack? {
        val counter = counters[order.bar.actorId]?.takeIf { it.order === order } ?: return null
        val stack = item(config.mug) ?: return null
        if (!order.pickup()) return null
        counter.resources.removeItem("drink")
        info("ORIGIN_DINING_DRINK phase=PICKED_UP bar={} waiter={} table={}", order.bar.actorId, order.waiterId, order.tableId)
        return stack
    }

    fun deliver(order: DiningDrinkOrder, mealAnchor: Location): PacketItemDisplay? {
        val counter = counters[order.bar.actorId]?.takeIf { it.order === order } ?: return null
        if (order.phase != DiningDrinkPhase.CARRIED) return null
        val table = order.tableId ?: return null
        val stack = item(config.mug) ?: return null
        val anchor = diningSurfaceAnchor(mealAnchor.clone().add(config.drinkTableOffsetX, 0.0, config.drinkTableOffsetZ))
        val display = counter.resources.item("drink", anchor, stack, config.mug.scale, config.mug.lift)
        check(order.serve())
        counters.remove(order.bar.actorId)
        tables[table] = Served(counter.resources, anchor, ticks + config.drinkFullTicks,
            ticks + config.drinkFullTicks + config.drinkEmptyTicks)
        lastServed[table] = ticks
        info("ORIGIN_DINING_DRINK phase=SERVED bar={} waiter={} table={}", order.bar.actorId, order.waiterId, table)
        return display
    }

    fun cancel(order: DiningDrinkOrder) {
        val counter = counters[order.bar.actorId]?.takeIf { it.order === order } ?: return
        if (!order.returnToCounter()) return
        item(config.mug)?.let { counter.resources.item("drink", order.station, it, config.mug.scale, config.mug.lift) }
        info("ORIGIN_DINING_DRINK phase=RETURNED_TO_COUNTER bar={}", order.bar.actorId)
    }

    fun tick(now: Long) {
        ticks = now
        tables.entries.toList().forEach { (id, served) ->
            if (ticks >= served.clearAt) {
                served.resources.cleanup()
                tables.remove(id)
            } else if (!served.empty && ticks >= served.emptyAt) {
                item(config.emptyMug)?.let {
                    served.resources.item("drink", served.anchor, it, config.emptyMug.scale, config.emptyMug.lift)
                    served.empty = true
                }
            }
        }
    }

    override fun close() {
        counters.values.forEach { it.resources.cleanup() }
        tables.values.forEach { it.resources.cleanup() }
        counters.clear()
        tables.clear()
        lastServed.clear()
    }
}
