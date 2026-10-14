package ru.arc.origin.mountyard

import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.origin.OriginServices
import ru.arc.origin.api.ArcOriginNpcHolograms
import ru.arc.logging.ArcLogging.warn
import ru.arc.util.TextUtils
import java.nio.file.Path
import java.util.UUID

/** Immutable authoring data for one NPC-owned mount-yard animal. */
data class OriginMountYardAnimal(
    val npcId: Int,
    val name: String,
    val entityType: String,
    /** Citizens' stable NPC UUID; the spawned Bukkit entity UUID is not used as the fence. */
    val npcUuid: UUID,
    val friendlyLines: List<String>,
    val warningLines: List<String>,
    val annoyedSound: String,
) {
    init {
        require(npcId > 0) { "Mount-yard animal NPC id must be positive" }
        require(name.isNotBlank()) { "Mount-yard animal name must not be blank" }
        require(entityType.isNotBlank() && !entityType.equals("ANY", ignoreCase = true)) {
            "Mount-yard animal entity type must be exact"
        }
        require(friendlyLines.isNotEmpty() && friendlyLines.all(String::isNotBlank)) {
            "Mount-yard animal friendly lines must not be empty"
        }
        require(warningLines.isNotEmpty() && warningLines.all(String::isNotBlank)) {
            "Mount-yard animal warning lines must not be empty"
        }
        require(annoyedSound.isNotBlank()) { "Mount-yard animal annoyed sound must not be blank" }
    }
}

/** ID plus mandatory stable Citizens UUID fence; no world-wide entity scan is permitted. */
class OriginMountYardAnimalCatalog(animals: Collection<OriginMountYardAnimal>) {
    private val byNpcId: Map<Int, OriginMountYardAnimal> = animals
        .also { require(it.map(OriginMountYardAnimal::npcId).distinct().size == it.size) }
        .associateBy(OriginMountYardAnimal::npcId)

    val animals: List<OriginMountYardAnimal> = byNpcId.values.sortedBy(OriginMountYardAnimal::npcId)

    /** Resolves only the configured NPC and its stable Citizens UUID fence. */
    fun resolve(npcId: Int, npcUuid: UUID): OriginMountYardAnimal? =
        byNpcId[npcId]?.takeIf { it.npcUuid == npcUuid }
}

data class OriginMountYardPetRules(
    val petWindowMillis: Long = 6_000L,
    val petCooldownMillis: Long = 750L,
    val petsBeforeAnnoyed: Int = 4,
    val annoyedDurationMillis: Long = 10_000L,
    val maxTrackedStates: Int = 4_096,
    val reactionDamage: Double = 2.0,
) {
    init {
        require(petWindowMillis > 0L)
        require(petCooldownMillis >= 0L)
        require(petsBeforeAnnoyed > 0)
        require(annoyedDurationMillis > 0L)
        require(maxTrackedStates > 0)
        require(reactionDamage.isFinite() && reactionDamage >= 0.0)
    }
}

sealed interface OriginMountYardPetResult {
    data class Friendly(val petCount: Int) : OriginMountYardPetResult
    data object Cooldown : OriginMountYardPetResult
    data class AnnoyedTriggered(val untilMillis: Long) : OriginMountYardPetResult
    data class Annoyed(val untilMillis: Long) : OriginMountYardPetResult
}

/** Bounded per-player/per-animal state machine. It intentionally has no Bukkit dependency. */
class OriginMountYardPetTracker(
    private val rules: OriginMountYardPetRules,
) {
    private data class Key(val playerId: UUID, val npcId: Int)

    private data class State(
        var lastInteractionMillis: Long,
        var annoyedUntilMillis: Long = 0L,
        val petTimesMillis: ArrayDeque<Long> = ArrayDeque(),
    )

    private val states = linkedMapOf<Key, State>()

    @Synchronized
    fun pet(playerId: UUID, npcId: Int, nowMillis: Long): OriginMountYardPetResult {
        require(nowMillis >= 0L) { "Pet interaction time must not be negative" }
        evictExpired(nowMillis)
        val key = Key(playerId, npcId)
        val state = states[key]
        if (state != null && nowMillis - state.lastInteractionMillis < rules.petCooldownMillis) {
            return OriginMountYardPetResult.Cooldown
        }

        val current = state ?: State(lastInteractionMillis = nowMillis).also { states[key] = it }
        current.lastInteractionMillis = nowMillis
        if (nowMillis < current.annoyedUntilMillis) {
            trimPetTimes(current, nowMillis)
            enforceBound()
            return OriginMountYardPetResult.Annoyed(current.annoyedUntilMillis)
        }

        trimPetTimes(current, nowMillis)
        current.petTimesMillis.addLast(nowMillis)
        if (current.petTimesMillis.size >= rules.petsBeforeAnnoyed) {
            current.annoyedUntilMillis = nowMillis + rules.annoyedDurationMillis
            current.petTimesMillis.clear()
            enforceBound()
            return OriginMountYardPetResult.AnnoyedTriggered(current.annoyedUntilMillis)
        }
        enforceBound()
        return OriginMountYardPetResult.Friendly(current.petTimesMillis.size)
    }

    @Synchronized
    fun clearPlayer(playerId: UUID) {
        states.keys.removeIf { it.playerId == playerId }
    }

    @Synchronized
    fun clear() {
        states.clear()
    }

    @Synchronized
    fun stateCount(): Int = states.size

    private fun trimPetTimes(state: State, nowMillis: Long) {
        while (state.petTimesMillis.firstOrNull()?.let { nowMillis - it > rules.petWindowMillis } == true) {
            state.petTimesMillis.removeFirst()
        }
    }

    private fun evictExpired(nowMillis: Long) {
        states.entries.removeIf { (_, state) ->
            nowMillis - state.lastInteractionMillis > maxOf(rules.petWindowMillis, rules.annoyedDurationMillis) &&
                nowMillis >= state.annoyedUntilMillis
        }
    }

    private fun enforceBound() {
        while (states.size > rules.maxTrackedStates) {
            states.minByOrNull { it.value.lastInteractionMillis }?.key?.let(states::remove) ?: return
        }
    }
}

fun nonLethalReactionDamage(
    targetHealth: Double,
    requestedDamage: Double,
    minimumHealth: Double = 1.0,
): Double {
    if (!targetHealth.isFinite() || !requestedDamage.isFinite() || !minimumHealth.isFinite()) return 0.0
    if (targetHealth <= minimumHealth) return 0.0
    return requestedDamage.coerceAtLeast(0.0).coerceAtMost(targetHealth - minimumHealth)
}

/** Caps only the short-lived synthetic reaction damage, never ordinary combat. */
fun clampSyntheticReactionDamage(
    targetHealth: Double,
    finalDamage: Double,
    minimumHealth: Double = 1.0,
): Double {
    if (!targetHealth.isFinite() || !finalDamage.isFinite() || !minimumHealth.isFinite()) return 0.0
    return finalDamage.coerceAtLeast(0.0).coerceAtMost((targetHealth - minimumHealth).coerceAtLeast(0.0))
}

data class OriginMountYardAnimalsConfig(
    val world: String,
    val maxInteractionRange: Double,
    val speechDurationTicks: Int,
    val friendlySound: String,
    val petRules: OriginMountYardPetRules,
    val catalog: OriginMountYardAnimalCatalog,
) {
    val animals: List<OriginMountYardAnimal> get() = catalog.animals

    init {
        require(world.isNotBlank())
        require(maxInteractionRange.isFinite() && maxInteractionRange > 0.0)
        require(speechDurationTicks > 0)
        require(friendlySound.isNotBlank())
    }

    companion object {
        const val RESOURCE = "origin-mount-yard.yml"

        fun load(dataPath: Path, uuidFences: Map<Int, UUID> = emptyMap()): OriginMountYardAnimalsConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")
            return parse(source, uuidFences)
        }

        internal fun parse(source: Config, uuidFences: Map<Int, UUID> = emptyMap()): OriginMountYardAnimalsConfig {
            val interaction = source.section("interaction")
            val animals = source.keys("animals").map { rawId ->
                val npcId = rawId.toIntOrNull() ?: error("animals key must be an NPC id: $rawId")
                val section = source.section("animals.$rawId")
                val uuid = uuidFences[npcId] ?: section.stringOrNull("npc-uuid")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { raw ->
                        runCatching { UUID.fromString(raw) }
                            .getOrElse { error("animals.$rawId.npc-uuid is not a UUID: $raw") }
                    }
                    ?: error("animals.$rawId.npc-uuid is required")
                val friendly = section.stringList("friendly-lines")
                val warning = section.stringList("warning-lines")
                require(friendly.size in 2..3) { "animals.$rawId.friendly-lines must contain 2..3 lines" }
                require(warning.size in 2..3) { "animals.$rawId.warning-lines must contain 2..3 lines" }
                OriginMountYardAnimal(
                    npcId = npcId,
                    name = section.string("name"),
                    entityType = section.string("entity-type").uppercase(),
                    npcUuid = uuid,
                    friendlyLines = friendly,
                    warningLines = warning,
                    annoyedSound = section.string("annoyed-sound"),
                )
            }
            require(animals.isNotEmpty()) { "origin mount-yard animals must not be empty" }
            return OriginMountYardAnimalsConfig(
                world = source.string("world", "rc_origin_spawn"),
                maxInteractionRange = source.double("max-interaction-range", 4.0),
                speechDurationTicks = source.int("speech-duration-ticks", 50),
                friendlySound = source.string("friendly-sound", "BLOCK_AMETHYST_BLOCK_CHIME"),
                petRules = OriginMountYardPetRules(
                    petWindowMillis = interaction.durationMillis("pet-window", 6_000L),
                    petCooldownMillis = interaction.durationMillis("pet-cooldown", 750L),
                    petsBeforeAnnoyed = interaction.int("pets-before-annoyed", 4),
                    annoyedDurationMillis = interaction.durationMillis("annoyed-duration", 10_000L),
                    maxTrackedStates = interaction.int("max-tracked-states", 4_096),
                    reactionDamage = interaction.double("reaction-damage", 2.0),
                ),
                catalog = OriginMountYardAnimalCatalog(animals),
            )
        }
    }
}

/**
 * Native Citizens adapter. The parent module owns lifecycle registration and
 * injects the friendly-pet callback for the daily quest without coupling this
 * interaction owner to quest persistence.
 */
class OriginMountYardAnimals(
    private val config: OriginMountYardAnimalsConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onFriendlyPet: (Player, NPC) -> Unit = { _, _ -> },
    private val npcHolograms: ArcOriginNpcHolograms? = OriginServices.npcHolograms,
) : Listener {
    private data class SyntheticReaction(val attackerId: UUID, val expiresAtMillis: Long)

    private val tracker = OriginMountYardPetTracker(config.petRules)
    private val syntheticReactions = mutableMapOf<UUID, SyntheticReaction>()

    val animals: List<OriginMountYardAnimal> get() = config.animals

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onNpcRightClick(event: NPCRightClickEvent) {
        if (event.isCancelled) return
        val player = event.clicker
        val npc = event.npc
        val animal = ownedAnimal(player, npc) ?: return

        // This owner is authoritative only for configured animals. Cancelling
        // here prevents Citizens CommandTrait/native dialogue duplication while
        // leaving every unrelated NPC and service handler untouched.
        event.isCancelled = true
        event.setDelayedCancellation(true)
        val now = clock()
        when (val result = tracker.pet(player.uniqueId, animal.npcId, now)) {
            is OriginMountYardPetResult.Friendly -> {
                present(player, npc, animal, animal.friendlyLines, now, annoyed = false)
                runCatching { onFriendlyPet(player, npc) }
                    .onFailure { warn("ORIGIN_MOUNT_YARD pet callback failed npc={} player={}", npc.id, player.uniqueId, it) }
            }
            OriginMountYardPetResult.Cooldown -> Unit
            is OriginMountYardPetResult.AnnoyedTriggered -> {
                present(player, npc, animal, animal.warningLines, now, annoyed = true)
                attack(player, npc, now)
            }
            is OriginMountYardPetResult.Annoyed -> {
                present(player, npc, animal, animal.warningLines, now, annoyed = true)
                attack(player, npc, now)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSyntheticReactionDamage(event: EntityDamageByEntityEvent) {
        val reaction = syntheticReactions[event.entity.uniqueId] ?: return
        if (clock() > reaction.expiresAtMillis) {
            syntheticReactions.remove(event.entity.uniqueId)
            return
        }
        if (event.damager.uniqueId != reaction.attackerId) return
        syntheticReactions.remove(event.entity.uniqueId)
        val target = event.entity as? LivingEntity ?: return
        val cap = clampSyntheticReactionDamage(target.health, event.finalDamage)
        if (event.finalDamage <= cap + DAMAGE_EPSILON) return
        event.isCancelled = true
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        tracker.clearPlayer(event.player.uniqueId)
        syntheticReactions.remove(event.player.uniqueId)
    }

    /** Parent lifecycle calls this before replacing the listener on reload. */
    fun clear() {
        tracker.clear()
        syntheticReactions.clear()
    }

    private fun ownedAnimal(player: Player, npc: NPC): OriginMountYardAnimal? {
        if (!player.world.name.equals(config.world, ignoreCase = true) || !npc.isSpawned) return null
        val entity = runCatching { npc.entity }.getOrNull() as? LivingEntity ?: return null
        if (entity.world.uid != player.world.uid) return null
        if (player.location.distanceSquared(entity.location) > config.maxInteractionRange * config.maxInteractionRange) return null
        // Citizens' NPC UUID is the stable identity fence. The spawned Bukkit
        // entity UUID can change after a despawn/respawn and must not own the
        // catalog entry by itself.
        val animal = config.catalog.resolve(npc.id, npc.uniqueId) ?: return null
        if (animal.entityType != "ANY" && !entity.type.name.equals(animal.entityType, ignoreCase = true)) return null
        return animal
    }

    private fun present(
        player: Player,
        npc: NPC,
        animal: OriginMountYardAnimal,
        lines: List<String>,
        nowMillis: Long,
        annoyed: Boolean,
    ) {
        val line = lines[(nowMillis / 1_000L % lines.size).toInt()]
        val owner = "origin-mount-yard-animal:${player.uniqueId}:${npc.id}"
        if (npcHolograms?.showTemporaryBubble(npc.id, listOf(line), config.speechDurationTicks, owner) != true) {
            player.sendActionBar(TextUtils.mm(line))
        }
        val location = npc.entity.location.clone().add(0.0, npc.entity.height.coerceAtLeast(1.0) * 0.8, 0.0)
        if (annoyed) {
            location.world.spawnParticle(Particle.ANGRY_VILLAGER, location, 4, 0.18, 0.12, 0.18, 0.01)
            playSound(location, animal.annoyedSound, 0.45f, 0.9f)
        } else {
            location.world.spawnParticle(Particle.HEART, location, 3, 0.18, 0.12, 0.18, 0.01)
            playSound(location, when (animal.entityType) {
                "HORSE" -> "ENTITY_HORSE_BREATHE"
                "DONKEY" -> "ENTITY_DONKEY_AMBIENT"
                "CAT" -> "ENTITY_CAT_PURR"
                "WOLF" -> "ENTITY_WOLF_AMBIENT"
                "GOAT" -> "ENTITY_GOAT_AMBIENT"
                "LLAMA" -> "ENTITY_LLAMA_AMBIENT"
                "RAVAGER" -> "ENTITY_RAVAGER_AMBIENT"
                else -> config.friendlySound
            }, 0.25f, 1.15f)
        }
    }

    private fun attack(player: Player, npc: NPC, nowMillis: Long) {
        val attacker = runCatching { npc.entity }.getOrNull() as? LivingEntity ?: return
        val damage = nonLethalReactionDamage(player.health, config.petRules.reactionDamage)
        if (damage <= 0.0) return
        syntheticReactions[player.uniqueId] = SyntheticReaction(
            attackerId = attacker.uniqueId,
            expiresAtMillis = nowMillis + SYNTHETIC_REACTION_TIMEOUT_MILLIS,
        )
        try {
            player.damage(damage, attacker)
        } finally {
            syntheticReactions.remove(player.uniqueId)
        }
    }

    // Preserve the Bukkit enum-style sound names in existing operator configuration.
    @Suppress("DEPRECATION")
    private fun playSound(location: org.bukkit.Location, rawSound: String, volume: Float, pitch: Float) {
        runCatching { Sound.valueOf(rawSound.uppercase()) }
            .onSuccess { location.world.playSound(location, it, volume, pitch) }
    }

    private companion object {
        const val SYNTHETIC_REACTION_TIMEOUT_MILLIS = 2_000L
        const val DAMAGE_EPSILON = 1.0e-6
    }
}
