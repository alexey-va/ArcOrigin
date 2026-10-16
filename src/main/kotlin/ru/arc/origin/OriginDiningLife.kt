package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.arc.origin.OriginNpcHolograms
import ru.arc.origin.scene.OriginSceneCoordinator
import ru.arc.origin.scene.OriginSceneExecution
import ru.arc.origin.scene.OriginSceneExecutionEffects
import ru.arc.origin.scene.OriginSceneRecoveryPolicy
import ru.arc.origin.scene.OriginSceneResources
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dining choreography, not ordering/payment. All calls run on the server thread.
 * Uses the same execution, actor leases and resource recovery as forge/stables.
 * Existing interactive meals are borrowed, never destroyed here. At most one
 * short cycle per actor and a configured small global prop budget are active.
 */
internal class OriginDiningLife(
    private val config: OriginDiningLifeConfig,
    val coordinator: OriginSceneCoordinator,
    private val mayWork: (Int) -> Boolean = { true },
) : AutoCloseable {
    private data class Meal(
        val key: String,
        val actorId: Int,
        val dish: String,
        val display: ItemDisplay,
        val anchor: Location,
        val original: Transformation,
        var portion: DiningPortion,
        var nextBite: Long,
        var warmth: Int,
    )

    private data class Beat(val name: String, val ticks: Int, val action: (OriginSceneResources, OriginSceneExecution) -> Unit)
    private data class Running(val actors: Set<Int>, val execution: OriginSceneExecution)
    private val meals = linkedMapOf<String, Meal>()
    private val cycles = linkedMapOf<String, Running>()
    private val itemCache = mutableMapOf<String, ItemStack?>()
    private var viewers = emptyList<Player>()
    private var ticks = 0L
    val drinks = OriginDiningDrinks(config, ::item)
    private var closed = false

    /** Called once per service reconciliation; no world or entity scan per prop. */
    fun tick() {
        if (closed) return
        ticks += 20
        drinks.tick(ticks)
        viewers = Bukkit.getWorld(OriginDiningLayout.WORLD)?.players.orEmpty().filter { !it.isDead }
        cycles.values.toList().forEach { running ->
            if (running.actors.any { actor(it) == null } || running.actors.none { id -> actor(id)?.entity?.location?.let(::hasAudience) == true }) {
                running.execution.interrupt("audience-or-actor-gone", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
            }
        }
        meals.values.toList().forEach { meal ->
            if (!meal.display.isValid) { forgetMeal(meal.key); return@forEach }
            if (!hasAudience(meal.anchor)) return@forEach
            if (meal.warmth > 0 && meal.portion != DiningPortion.EMPTY) {
                particle(Particle.SMOKE, meal.anchor.clone().add(0.0, 0.18, 0.0), 1, 0.09, 0.02, 0.09)
                meal.warmth -= 20
            }
            if (meal.portion != DiningPortion.EMPTY && ticks >= meal.nextBite && mayWork(meal.actorId) && bite(meal)) {
                meal.nextBite = ticks + config.biteIntervalTicks.random()
            }
        }
        config.bars.forEach { bar ->
            val npc = actor(bar.actorId) ?: return@forEach
            if (mayWork(bar.actorId) && hasAudience(npc.entity.location)) bartender(bar)
        }
    }

    fun hasAudience(location: Location): Boolean = viewers.any {
        it.world == location.world && it.location.distanceSquared(location) <= config.audienceRange * config.audienceRange
    }

    fun mealServed(key: String, actorId: Int, dish: String, display: ItemDisplay) {
        forgetMeal(key)
        meals[key] = Meal(key, actorId, dish, display, display.location.clone(), copyTransform(display.transformation),
            DiningPortion.FULL, ticks + config.biteIntervalTicks.random(), config.steamDurationTicks)
    }

    fun forgetMeal(key: String) {
        cycles["bite:$key"]?.execution?.interrupt("meal-removed", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
        meals.remove(key)
    }

    fun isEmpty(key: String): Boolean = meals[key]?.portion == DiningPortion.EMPTY

    /** A player waiter owns this guest's lease, so the ambient bite cycle cannot acquire it. */
    fun finishEating(key: String): Boolean {
        val meal = meals[key]?.takeIf { it.display.isValid } ?: return false
        if (meal.portion == DiningPortion.EMPTY) return true
        cycles["bite:$key"]?.execution?.interrupt("player-waiter-meal-finished", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
        return runCatching { updatePortion(meal, DiningPortion.EMPTY); true }.getOrElse { failure ->
            warn("ORIGIN_DINING_LIFE phase=WAITER_PLATE_FAILED table={}", key, failure)
            false
        }
    }

    fun preempt(actorId: Int) {
        cycles.values.filter { actorId in it.actors }.toList().forEach {
            it.execution.interrupt("player-or-service-priority", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
        }
    }

    /** Animate the existing display; both normal completion and interruption settle exactly. */
    fun serve(display: ItemDisplay, waiterId: Int) {
        val npc = actor(waiterId) ?: return
        val anchor = display.location.clone()
        if (!hasAudience(anchor)) return
        val finalTransform = copyTransform(display.transformation)
        val start = handPoint(npc)
        fun settle() {
            if (display.isValid) {
                display.interpolationDuration = 2
                display.interpolationDelay = 0
                display.transformation = copyTransform(finalTransform)
            }
        }
        val key = "serve:$waiterId"
        startCycle(key, setOf(waiterId), listOf(Beat("place", config.servingTicks + 2) { resources, execution ->
            resources.facePoint(npc, anchor)
            swing(npc)
            var elapsed = 0
            fun frame() {
                if (!display.isValid) return
                val offset = diningServingOffset(elapsed.toDouble() / config.servingTicks,
                    start.x - anchor.x, start.y - anchor.y, start.z - anchor.z)
                val frame = copyTransform(finalTransform)
                frame.translation.add(diningLocalOffset(offset, anchor.yaw, anchor.pitch))
                display.interpolationDuration = 2
                display.interpolationDelay = 0
                display.transformation = frame
            }
            frame()
            execution.repeat(2) {
                elapsed += 2
                frame()
                if (elapsed >= config.servingTicks) sound(anchor, Sound.BLOCK_DECORATED_POT_PLACE, 0.28f, 1.45f)
                display.isValid && elapsed < config.servingTicks
            }
        }), cleanup = ::settle)
    }

    /** The caller retains meal/route ownership until the crockery is actually collected. */
    fun collect(display: ItemDisplay, waiterId: Int, cancelled: () -> Unit, collected: () -> Unit): Boolean {
        val npc = actor(waiterId) ?: return false
        val anchor = display.location.clone()
        if (!hasAudience(anchor)) { collected(); return true }
        val original = copyTransform(display.transformation)
        val target = handPoint(npc)
        return startCycle("collect:$waiterId", setOf(waiterId), listOf(
            Beat("lift-plate", config.servingTicks + 2) { resources, execution ->
                resources.facePoint(npc, anchor)
                swing(npc)
                sound(anchor, Sound.BLOCK_DECORATED_POT_PLACE, 0.2f, 1.6f)
                var elapsed = 0
                execution.repeat(2) {
                    elapsed += 2
                    if (display.isValid) {
                        val offset = diningServingOffset(1.0 - elapsed.toDouble() / config.servingTicks,
                            target.x - anchor.x, target.y - anchor.y, target.z - anchor.z)
                        val frame = copyTransform(original)
                        frame.translation.add(diningLocalOffset(offset, anchor.yaw, anchor.pitch))
                        display.interpolationDuration = 2
                        display.interpolationDelay = 0
                        display.transformation = frame
                    }
                    display.isValid && elapsed < config.servingTicks
                }
            },
        ), cleanup = {
            if (display.isValid) {
                display.interpolationDuration = 0
                display.transformation = original
            }
        }, completed = collected, interrupted = cancelled)
    }

    /** Native empty crockery in the waiter's hand is restored by the existing service owner. */
    fun emptyPlateItem(dish: String): ItemStack = item(config.emptyPlates[dish] ?: config.emptyPlate) ?: ItemStack(Material.BOWL)

    fun conversationMug(): ItemStack? = item(config.mug)

    /** One guest takes one bite. The display's plate never changes size. */
    private fun bite(meal: Meal): Boolean {
        val npc = actor(meal.actorId) ?: return false
        return startCycle("bite:${meal.key}", setOf(npc.id), listOf(
            Beat("look-at-food", 8) { resources, _ -> resources.facePoint(npc, meal.anchor) },
            Beat("eat", config.biteDurationTicks) { resources, execution ->
                resources.equip(npc, Material.AIR)
                swing(npc)
                sound(npc.entity.location, Sound.ENTITY_GENERIC_EAT, 0.23f, 1.0f)
                execution.after((config.biteDurationTicks / 2).toLong()) {
                    swing(npc)
                    particle(Particle.CRIT, meal.anchor.clone().add(0.0, 0.15, 0.0), 2, 0.08, 0.02, 0.08)
                    sound(npc.entity.location, Sound.ENTITY_GENERIC_EAT, 0.18f, 1.15f)
                }
            },
            Beat("portion", 4) { _, _ ->
                if (meals[meal.key] === meal && meal.display.isValid) {
                    updatePortion(meal, meal.portion.next())
                    if (meal.portion == DiningPortion.EMPTY && !OriginNpcHolograms.hasTemporaryBubble(npc.id)) {
                        OriginNpcHolograms.showTemporaryBubble(npc.id, listOf(config.emptyLines.random()), 70)
                    }
                }
            },
        ))
    }

    private fun updatePortion(meal: Meal, next: DiningPortion) {
        if (next != DiningPortion.EMPTY) {
            meal.portion = next
            return
        }
        val prop = config.emptyPlates[meal.dish] ?: config.emptyPlate
        val stack = checkNotNull(item(prop)) { "Missing dining portion model: ${prop.item}" }
        meal.display.setItemStack(stack)
        val transform = copyTransform(meal.original)
        transform.translation.set(0f, prop.lift, 0f)
        transform.scale.set(prop.scale)
        meal.display.interpolationDuration = 0
        meal.display.transformation = transform
        meal.portion = next
        info("ORIGIN_DINING_LIFE phase=PLATE_EMPTY table={} guest={} dish={}", meal.key, meal.actorId, meal.dish)
    }

    private fun bartender(bar: DiningBar) {
        if (drinks.hasPending(bar.actorId) || bar.waiterIds.isEmpty()) return
        val npc = actor(bar.actorId) ?: return
        val station = bar.station.inWorld(npc.entity.world)
        if (npc.entity.location.distanceSquared(station) > 9.0) return
        val mug = item(config.emptyMug) ?: return
        val filledMug = item(config.mug) ?: return
        val bottle = item(config.pouringBottle) ?: return
        val owner = "dining-bar:${npc.id}"
        val beats = listOf(
            Beat("take-mug", 20) { resources, _ ->
                resources.facePoint(npc, station)
                resources.equip(npc, Material.GLASS_BOTTLE)
                resources.item("mug", station, mug, config.emptyMug.scale, config.emptyMug.lift)
                swing(npc)
                sound(station, Sound.BLOCK_DECORATED_POT_PLACE, 0.25f, 1.5f)
            },
            Beat("pour", 60) { resources, execution ->
                var poured = 0
                execution.repeat(5) {
                    val cup = station.clone()
                    val spout = cup.clone().add(0.0, 0.62, 0.0)
                    val pouring = resources.item("bottle", spout, bottle, config.pouringBottle.scale, 0f)
                    pouring.transformation = diningPourTransform(config.pouringBottle.scale, config.bottleMouthY)
                    val at = cup.clone().add(0.0, 0.3 + config.pourHeight * (1.0 - (poured % 4) / 4.0), 0.0)
                    particle(Particle.FALLING_WATER, at, 2, 0.015, 0.015, 0.015)
                    if (poured % 4 == 0) { swing(npc); sound(station, Sound.ITEM_BOTTLE_FILL, 0.16f, 1.1f) }
                    ++poured < 11
                }
            },
            Beat("ready", 55) { resources, _ ->
                resources.removeItem("bottle")
                resources.removeItem("mug")
                drinks.ready(bar, station)
                sound(station, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.14f, 1.3f)
                if (!OriginNpcHolograms.hasTemporaryBubble(npc.id))
                    OriginNpcHolograms.showTemporaryBubble(npc.id, listOf(config.readyLines.random()), 60, owner)
            },
            Beat("wipe-counter", 50) { resources, execution ->
                resources.equip(npc, Material.PAPER)
                resources.facePoint(npc, station.clone().add(-0.2, 0.0, 0.0))
                swing(npc)
                execution.after(15) { resources.facePoint(npc, station.clone().add(0.2, 0.0, 0.0)); swing(npc) }
                execution.after(32) { resources.facePoint(npc, station); swing(npc) }
                var wipe = 0
                execution.repeat(4) {
                    resources.solid("cloth", station.clone().add(-0.1 + sin(wipe * 0.8) * 0.22, 0.002, -0.08),
                        Material.WHITE_WOOL, Vector3f(0.22f, 0.015f, 0.16f))
                    ++wipe < 11
                }
            },
        )
        startCycle(owner, setOf(npc.id), beats, config.bartenderRestTicks.random(), cleanup = {
                OriginNpcHolograms.clearTemporaryBubble(npc.id, owner)
            })
    }

    private fun startCycle(
        key: String,
        actorIds: Set<Int>,
        beats: List<Beat>,
        cooldownTicks: Int = 0,
        cleanup: () -> Unit = {},
        completed: () -> Unit = {},
        interrupted: () -> Unit = {},
    ): Boolean {
        if (closed || key in cycles || cycles.size >= config.maxCycles || actorIds.any { actor(it) == null }) return false
        val lease = coordinator.tryAcquire("dining-life", key, actorIds, System.currentTimeMillis()) ?: return false
        val resources = OriginSceneResources()
        lateinit var execution: OriginSceneExecution
        execution = OriginSceneExecution(beats.map(Beat::name), beats.sumOf { it.ticks.toLong() } + 80,
            object : OriginSceneExecutionEffects {
                override fun execute(stepIndex: Int) {
                    if (actorIds.any { actor(it) == null }) {
                        execution.interrupt("actor-unavailable", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
                        return
                    }
                    val beat = beats[stepIndex]
                    beat.action(resources, execution)
                    execution.after(beat.ticks.toLong()) { execution.advance(stepIndex + 1) }
                }
                override fun cleanup(keepMounted: Boolean) {
                    val failures = resources.cleanup()
                    var extra: Exception? = null
                    try { cleanup() } catch (failure: Exception) { extra = failure }
                    if (failures.isNotEmpty() || extra != null) throw IllegalStateException("Dining cycle cleanup: $key").apply {
                        failures.forEach { addSuppressed(it.failure) }; extra?.let(::addSuppressed)
                    }
                }
                override fun returnHome(reason: String, immediate: Boolean) { if (!immediate) execution.completeReturn(reason) }
                override fun release(reason: String) {
                    cycles.remove(key)
                    coordinator.release(lease, System.currentTimeMillis(), cooldownTicks * 50L)
                    info("ORIGIN_DINING_LIFE phase=FINISHED cycle={} result={}", key, reason)
                    if (reason == "complete") completed() else interrupted()
                }
                override fun reportFailure(stage: String, failure: Exception) {
                    warn("ORIGIN_DINING_LIFE phase=FAILED cycle=$key stage=$stage", failure)
                }
            })
        cycles[key] = Running(actorIds, execution)
        execution.start()
        return true
    }

    internal fun item(prop: DiningProp): ItemStack? {
        if (!itemCache.containsKey(prop.item)) {
            itemCache[prop.item] = if (prop.item.startsWith("minecraft:")) Material.matchMaterial(prop.item.substringAfter(':'))?.let(::ItemStack)
                else CustomStack.getInstance(prop.item)?.itemStack?.clone()
            if (itemCache[prop.item] == null) warn("ORIGIN_DINING_LIFE phase=MODEL_MISSING item={}", prop.item)
        }
        return itemCache[prop.item]?.clone()
    }

    internal fun handPoint(npc: NPC): Location {
        val location = npc.entity.location.clone()
        val radians = Math.toRadians(location.yaw.toDouble())
        return location.add(cos(radians) * config.handSide - sin(radians) * config.handForward,
            config.handHeight, sin(radians) * config.handSide + cos(radians) * config.handForward)
    }

    private fun nearby(location: Location): List<Player> = viewers.filter {
        it.isOnline && it.world == location.world && it.location.distanceSquared(location) <= config.effectRange * config.effectRange
    }
    private fun sound(location: Location, sound: Sound, volume: Float, pitch: Float) {
        nearby(location).forEach { it.playSound(location, sound, SoundCategory.NEUTRAL, volume, pitch) }
    }
    private fun particle(particle: Particle, at: Location, count: Int, x: Double, y: Double, z: Double) {
        nearby(at).forEach { it.spawnParticle(particle, at, count, x, y, z, 0.0) }
    }
    private fun actor(id: Int): NPC? = CitizensAPI.getNPCRegistry().getById(id)?.takeIf {
        it.isSpawned && it.entity.world.name == OriginDiningLayout.WORLD
    }
    private fun swing(npc: NPC) { (npc.entity as? LivingEntity)?.swingMainHand() }

    override fun close() {
        if (closed) return
        closed = true
        cycles.values.toList().forEach { it.execution.close() }
        cycles.clear()
        drinks.close()
        meals.clear()
        itemCache.clear()
        viewers = emptyList()
    }

    private companion object {
        fun copyTransform(value: Transformation) = Transformation(Vector3f(value.translation), org.joml.Quaternionf(value.leftRotation),
            Vector3f(value.scale), org.joml.Quaternionf(value.rightRotation))
    }
}
