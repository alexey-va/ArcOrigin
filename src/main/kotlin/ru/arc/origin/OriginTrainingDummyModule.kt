@file:Suppress("DEPRECATION") // Paper exposes no replacement cooldown-reset event in 1.21.11.

package ru.arc.origin

import com.denizenscript.denizen.objects.PlayerTag
import com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.event.NPCSpawnEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Pig
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.origin.scene.OriginAmbientScenesModule
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.origin.util.showTitleMM
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

internal val originTrainingDummyCooldownKey = NamespacedKey("arc", "origin_forge_training_cooldown_until")

internal data class OriginTrainingDummyConfig(
    val world: String,
    val dummyNpcId: Int,
    val trainerNpcId: Int,
    val location: OriginTrainingDummyPoint,
    val strongCharge: Double,
    val ambientMaxHealth: Double,
    val comboTimeoutMillis: Long,
    val challengeHits: Int,
    val challengeDurationTicks: Long,
    val cooldownMillis: Long,
    val partialReward: Int,
    val fullReward: Int,
) {
    companion object {
        fun load(): OriginTrainingDummyConfig {
            val source = ConfigManager.ofModule(ArcOriginPlugin.instance.legacyDataPath, "origin-scenes.yml")
            source.mergeMissingFromBundled("modules/origin-scenes.yml")
            val root = "training-dummy"
            return OriginTrainingDummyConfig(
                world = source.string("world", "rc_origin_spawn"),
                dummyNpcId = source.integer("$root.dummy-npc-id", 364),
                trainerNpcId = source.integer("$root.trainer-npc-id", 355),
                location = OriginTrainingDummyPoint.parse(
                    position = source.string("$root.position"),
                    yaw = source.real("$root.yaw"),
                    pitch = source.real("$root.pitch"),
                ),
                strongCharge = source.real("$root.strong-charge", 0.9).coerceIn(0.1, 1.0),
                ambientMaxHealth = source.real("$root.ambient-max-health", 1024.0).coerceIn(20.0, 1024.0),
                comboTimeoutMillis = source.integer("$root.combo-timeout-seconds", 15).toLong().coerceIn(2L, 60L) * 1_000L,
                challengeHits = source.integer("$root.challenge-hits", 8).coerceIn(2, 20),
                challengeDurationTicks = source.integer("$root.challenge-duration-seconds", 18).toLong().coerceIn(5L, 60L) * 20L,
                cooldownMillis = source.integer("$root.cooldown-hours", 20).toLong().coerceIn(1L, 168L) * 3_600_000L,
                partialReward = source.integer("$root.partial-reward", 20).coerceIn(0, 10_000),
                fullReward = source.integer("$root.full-reward", 60).coerceIn(0, 10_000),
            )
        }
    }
}

internal data class OriginTrainingDummyPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    fun inWorld(world: org.bukkit.World) = Location(world, x, y, z, yaw, pitch)

    companion object {
        fun parse(position: String, yaw: Double, pitch: Double): OriginTrainingDummyPoint {
            val values = position.split(',').map(String::trim)
            require(values.size == 3) { "training-dummy.position must be x,y,z" }
            require(yaw.isFinite() && yaw in -180.0..180.0) { "training-dummy.yaw must be within -180..180" }
            require(pitch.isFinite() && pitch in -90.0..90.0) { "training-dummy.pitch must be within -90..90" }
            return OriginTrainingDummyPoint(
                values[0].toDouble(),
                values[1].toDouble(),
                values[2].toDouble(),
                yaw.toFloat(),
                pitch.toFloat(),
            )
        }
    }
}

internal data class OriginTrainingCombo(val count: Int, val lastStrongHitAt: Long)

internal fun nextTrainingCombo(previous: OriginTrainingCombo?, now: Long, timeoutMillis: Long, maximum: Int): OriginTrainingCombo {
    val previousCount = previous?.takeIf { now - it.lastStrongHitAt <= timeoutMillis }?.count ?: 0
    return OriginTrainingCombo((previousCount + 1).coerceAtMost(maximum), now)
}

internal class OriginTrainingComboCounter {
    private val combos = mutableMapOf<UUID, OriginTrainingCombo>()

    fun recordStrong(playerId: UUID, now: Long, timeoutMillis: Long, maximum: Int): OriginTrainingCombo =
        nextTrainingCombo(combos[playerId], now, timeoutMillis, maximum).also { combos[playerId] = it }

    fun reset(playerId: UUID) {
        combos.remove(playerId)
    }

    fun clear() {
        combos.clear()
    }
}

internal fun isTrainingWeapon(material: Material): Boolean =
    material.name.endsWith("_SWORD") ||
        material.name.endsWith("_AXE") ||
        material.name.endsWith("_SPEAR") ||
        material == Material.TRIDENT ||
        material == Material.MACE

internal fun trainingReward(hits: Int, target: Int, partialReward: Int, fullReward: Int): Int = when {
    hits >= target -> fullReward
    hits >= target - 2 -> partialReward
    else -> 0
}

@Suppress("UNUSED_PARAMETER")
internal fun trainingDamage(probeDamage: Double?, armorStandDamage: Double): Double? =
    probeDamage?.takeIf { it.isFinite() && it >= 0.0 }

private data class OriginTrainingAttack(val charge: Double, val capturedAt: Long)

private data class OriginTrainingDamageProbe(val attacker: UUID, var damage: Double? = null)

private data class OriginTrainingChallenge(
    val token: UUID,
    var active: Boolean = false,
    var hits: Int = 0,
)

/** Kotlin owner of the forge mannequin, combo feedback and Bran's timed challenge. */
object OriginTrainingDummyModule : PluginModule, Listener {
    override val name = "OriginTrainingDummy"
    override val priority = 28

    private var config: OriginTrainingDummyConfig? = null
    private var tasks = LifecycleTaskScope()
    private val attacks = mutableMapOf<UUID, OriginTrainingAttack>()
    private val combos = OriginTrainingComboCounter()
    private val challenges = mutableMapOf<UUID, OriginTrainingChallenge>()
    private val lastDamageEventAt = mutableMapOf<UUID, Long>()
    private val damageProbes = mutableMapOf<UUID, OriginTrainingDamageProbe>()
    private val cooldownKey = originTrainingDummyCooldownKey

    override fun init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            warn("ORIGIN_TRAINING_DUMMY phase=DISABLED reason=citizens-unavailable")
            return
        }
        config = OriginTrainingDummyConfig.load()
        tasks = LifecycleTaskScope()
        Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
        tasks.runLater(5L) { anchorDummy() }
        info("ORIGIN_TRAINING_DUMMY phase=READY owner=arc-kotlin")
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        tasks.close()
        attacks.clear()
        combos.clear()
        challenges.clear()
        lastDamageEventAt.clear()
        damageProbes.clear()
        config = null
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onTrainerClick(event: NPCRightClickEvent) {
        val settings = config ?: return
        if (event.npc.id != settings.trainerNpcId || event.clicker.world.name != settings.world) return
        event.isCancelled = true
        event.setDelayedCancellation(true)
        startChallenge(event.clicker, settings)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onNpcSpawn(event: NPCSpawnEvent) {
        val settings = config ?: return
        if (event.npc.id == settings.dummyNpcId) tasks.runLater(1L) { anchorDummy() }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onPreAttack(event: PrePlayerAttackEntityEvent) {
        val settings = config ?: return
        if (!event.willAttack() || !isDummy(event.attacked.uniqueId, settings)) return
        attacks[event.player.uniqueId] = OriginTrainingAttack(
            event.player.attackCooldown.toDouble().coerceIn(0.0, 1.0),
            System.currentTimeMillis(),
        )
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onDummyCooldownReset(event: PlayerAttackEntityCooldownResetEvent) {
        val settings = config ?: return
        if (isDummy(event.attackedEntity.uniqueId, settings)) {
            // The nested living-target probe below owns the one real cooldown reset.
            // Keeping the original strength here makes its damage identical to the
            // player's intended hit instead of measuring the just-reset cooldown.
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDummyDamage(event: EntityDamageByEntityEvent) {
        val settings = config ?: return
        if (!isDummy(event.entity.uniqueId, settings)) return
        val npcAttacker = runCatching { CitizensAPI.getNPCRegistry().getNPC(event.damager) }.getOrNull()
        if (npcAttacker?.id == settings.trainerNpcId) {
            event.isCancelled = false
            info(
                "ORIGIN_TRAINING_DUMMY phase=AMBIENT_HIT attacker={} finalDamage={} health={}",
                npcAttacker.id,
                event.finalDamage,
                (event.entity as? LivingEntity)?.health,
            )
            return
        }
        event.isCancelled = true
        val player = event.damager as? Player ?: return
        if (player.world.name != settings.world) return
        val now = System.currentTimeMillis()
        if (now - lastDamageEventAt.getOrDefault(player.uniqueId, 0L) < 75L) return
        lastDamageEventAt[player.uniqueId] = now
        val material = player.inventory.itemInMainHand.type
        OriginAmbientScenesModule.interruptActor(settings.trainerNpcId, "player-at-dummy")
        if (!isTrainingWeapon(material)) {
            player.sendActionBar(Component.text("Бран » Для тренировки возьми меч, топор, копьё, трезубец или булаву.", NamedTextColor.GRAY))
            return
        }
        val attack = attacks.remove(player.uniqueId)?.takeIf { now - it.capturedAt <= 250L }
        // Paper supplies the pre-reset attack strength synchronously. If another plugin
        // suppresses that event, fail open for one hit instead of trapping the combo at 1/8.
        val charge = attack?.charge ?: 1.0
        val damage = trainingDamage(measureTrainingDamage(player, event.entity.location), event.damage)
        anchorDummy()
        (event.entity as? LivingEntity)?.playHurtAnimation(0f)
        player.playSound(event.entity.location, Sound.ENTITY_ARMOR_STAND_HIT, SoundCategory.PLAYERS, 0.9f, 0.92f)
        if (charge < settings.strongCharge) {
            weakHit(player, settings, charge, damage)
        } else {
            strongHit(player, settings, now, damage, event.isCritical)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onDamageProbe(event: EntityDamageByEntityEvent) {
        val probe = damageProbes[event.entity.uniqueId] ?: return
        if (event.damager.uniqueId != probe.attacker) return
        probe.damage = event.finalDamage.coerceAtLeast(0.0)
        event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        attacks.remove(id)
        combos.reset(id)
        challenges.remove(id)
        lastDamageEventAt.remove(id)
    }

    private fun startChallenge(player: Player, settings: OriginTrainingDummyConfig) {
        if (challenges.containsKey(player.uniqueId)) {
            player.sendMessage(Component.text("Бран » Испытание уже идёт. Продолжай серию.", NamedTextColor.GRAY))
            return
        }
        val now = System.currentTimeMillis()
        val cooldownUntil = cooldownUntil(player, now)
        if (cooldownUntil > now) {
            val hours = ceil((cooldownUntil - now) / 3_600_000.0).toInt().coerceAtLeast(1)
            player.sendMessage(Component.text("Бран » Новое испытание будет доступно через $hours ч. Манекен для обычной тренировки свободен.", NamedTextColor.GRAY))
            return
        }
        if (!isTrainingWeapon(player.inventory.itemInMainHand.type)) {
            player.sendMessage(Component.text("Бран » Для испытания нужен меч, топор, копьё, трезубец или булава в основной руке.", NamedTextColor.GRAY))
            return
        }

        player.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, now + settings.cooldownMillis)
        combos.reset(player.uniqueId)
        val challenge = OriginTrainingChallenge(UUID.randomUUID())
        challenges[player.uniqueId] = challenge
        OriginAmbientScenesModule.interruptActor(settings.trainerNpcId, "player-training")
        trainer(settings)?.let { (it.entity as? LivingEntity)?.swingMainHand() }
        player.playSound(player.location, Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.8f, 1.25f)
        player.sendMessage(Component.text("Бран » Собери серию из ${settings.challengeHits} мощных ударов.", NamedTextColor.GRAY))
        showCountdown(player, 3, settings.challengeHits)
        tasks.runLater(20L) { continueCountdown(player.uniqueId, challenge.token, 2, settings) }
    }

    private fun continueCountdown(playerId: UUID, token: UUID, number: Int, settings: OriginTrainingDummyConfig) {
        val player = Bukkit.getPlayer(playerId)
        val challenge = challenges[playerId]
        if (player == null || challenge?.token != token || player.world.name != settings.world) {
            challenges.remove(playerId, challenge)
            return
        }
        if (number > 0) {
            showCountdown(player, number, settings.challengeHits)
            tasks.runLater(20L) { continueCountdown(playerId, token, number - 1, settings) }
            return
        }
        challenge.active = true
        challenge.hits = 0
        combos.reset(playerId)
        player.showTitleMM("<green><bold>Начали", "<gray>Серия из <yellow>${settings.challengeHits} мощных ударов", 0, 16, 3)
        tasks.runLater(settings.challengeDurationTicks) { finishChallenge(playerId, token, settings) }
    }

    private fun showCountdown(player: Player, number: Int, target: Int) {
        player.showTitleMM(
            "<gold><bold>Приготовься <yellow>$number",
            "<gray>Полный замах <dark_gray>• <gray>успей сделать <white>$target ударов",
            0,
            16,
            2,
        )
    }

    private fun weakHit(player: Player, settings: OriginTrainingDummyConfig, charge: Double, damage: Double?) {
        combos.reset(player.uniqueId)
        challenges[player.uniqueId]?.takeIf { it.active }?.hits = 0
        val percent = (charge * 100).roundToInt()
        val title = if (challenges[player.uniqueId]?.active == true) "Серия сброшена" else "Слабый удар"
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 0.55f, 0.9f)
        player.spawnParticle(Particle.DAMAGE_INDICATOR, dummyEffectLocation(settings), 3, 0.12, 0.2, 0.12, 0.01)
        player.showTitleMM("<red><bold>$title", "<gray>Дождись полного замаха <dark_gray>• <white>$percent%", 0, 10, 4)
        player.sendActionBar(Component.text("${damageLabel(damage)} • заряд $percent% • серия 0/${settings.challengeHits}", NamedTextColor.RED))
    }

    private fun strongHit(player: Player, settings: OriginTrainingDummyConfig, now: Long, damage: Double?, critical: Boolean) {
        val combo = combos.recordStrong(player.uniqueId, now, settings.comboTimeoutMillis, settings.challengeHits)
        val titles = listOf("", "Мощный удар", "Связка ×2", "Комбо ×3", "Серия ×4", "Серия ×5", "Ритм ×6", "Почти ×7", "Цепь замкнута")
        val colors = listOf("", "<yellow>", "<gold>", "<red>", "<light_purple>", "<light_purple>", "<aqua>", "<aqua>", "<green>")
        val color = if (critical) "<aqua>" else colors.getOrElse(combo.count) { "<green>" }
        val location = dummyEffectLocation(settings)
        player.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.65f, 1.1f)
        player.spawnParticle(Particle.DAMAGE_INDICATOR, location, 3 + combo.count, 0.15, 0.25, 0.15, 0.01)
        player.spawnParticle(Particle.SWEEP_ATTACK, location.clone().add(0.0, -0.2, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
        player.spawnParticle(Particle.CRIT, location, 5 + combo.count * 2, 0.18, 0.28, 0.18, 0.05)
        player.showTitleMM(
            "$color<bold>${titles.getOrElse(combo.count) { "Цепь замкнута" }}",
            "<white>${damageLabel(damage)} <dark_gray>• <yellow>серия ${combo.count}/${settings.challengeHits}",
            0,
            8,
            4,
        )
        val challenge = challenges[player.uniqueId]?.takeIf { it.active }
        if (challenge == null) {
            player.sendActionBar(Component.text("Мощный удар • ${damageLabel(damage)} • серия ${combo.count}/${settings.challengeHits}", NamedTextColor.GREEN))
            return
        }
        challenge.hits = combo.count
        player.sendActionBar(Component.text("Испытание • ${combo.count}/${settings.challengeHits} • мощный удар • ${damageLabel(damage)}", NamedTextColor.GOLD))
        if (combo.count >= settings.challengeHits) finishChallenge(player.uniqueId, challenge.token, settings)
    }

    private fun finishChallenge(playerId: UUID, token: UUID, settings: OriginTrainingDummyConfig) {
        val challenge = challenges[playerId]?.takeIf { it.token == token && it.active } ?: return
        challenges.remove(playerId, challenge)
        combos.reset(playerId)
        val player = Bukkit.getPlayer(playerId) ?: return
        val reward = trainingReward(challenge.hits, settings.challengeHits, settings.partialReward, settings.fullReward)
        if (reward > 0 && !pay(player, reward, token)) {
            player.sendMessage(Component.text("Бран » Касса не подтвердила награду. Сообщи администрации.", NamedTextColor.RED))
            warn("ORIGIN_TRAINING_DUMMY phase=PAYOUT_FAILED player={} hits={} reward={}", player.name, challenge.hits, reward)
            return
        }
        when {
            challenge.hits >= settings.challengeHits -> {
                player.showTitleMM("<green>Испытание пройдено", "<yellow>${challenge.hits}/${settings.challengeHits} <gray>• награда <yellow>$reward <white>💰", 10, 60, 20)
                player.sendMessage(Component.text("Бран » Чистая серия. Держи $reward 💰.", NamedTextColor.GRAY))
            }
            reward > 0 -> {
                player.showTitleMM("<yellow>Почти", "<yellow>${challenge.hits}/${settings.challengeHits} <gray>• награда <yellow>$reward <white>💰", 10, 60, 20)
                player.sendMessage(Component.text("Бран » Ритм почти удержал. За попытку — $reward 💰.", NamedTextColor.GRAY))
            }
            else -> {
                player.showTitleMM("<red>Испытание не пройдено", "<yellow>${challenge.hits}/${settings.challengeHits} <gray>• серия оборвалась", 10, 60, 20)
                player.sendMessage(Component.text("Бран » Серия оборвалась. Потренируй ритм.", NamedTextColor.GRAY))
            }
        }
        info("ORIGIN_TRAINING_DUMMY phase=CHALLENGE_FINISHED player={} hits={} reward={}", player.name, challenge.hits, reward)
    }

    private fun pay(player: Player, amount: Int, challengeToken: UUID): Boolean {
        val economy = OriginServices.economy?.takeIf { it.isAvailable() } ?: return false
        return runCatching {
            economy.depositDefaultCurrency(
                player.uniqueId,
                amount.toDouble(),
                "arc-forge-training:${player.uniqueId}:$challengeToken",
            )
        }.getOrDefault(false)
    }

    private fun cooldownUntil(player: Player, now: Long): Long {
        val stored = player.persistentDataContainer.get(cooldownKey, PersistentDataType.LONG) ?: 0L
        val legacy = if (Bukkit.getPluginManager().isPluginEnabled("Denizen")) {
            runCatching {
                val tracker = PlayerTag(player).flagTracker
                if (tracker.hasFlag(LEGACY_COOLDOWN_FLAG)) tracker.getFlagExpirationTime(LEGACY_COOLDOWN_FLAG)?.millis() ?: 0L else 0L
            }.getOrDefault(0L)
        } else {
            0L
        }
        val result = maxOf(stored, legacy)
        if (result > now && result > stored) player.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, result)
        return result
    }

    private fun anchorDummy() {
        val settings = config ?: return
        val world = Bukkit.getWorld(settings.world) ?: return
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(settings.dummyNpcId) }.getOrNull()?.takeIf { it.isSpawned } ?: return
        npc.navigator.cancelNavigation()
        npc.entity.setGravity(false)
        npc.entity.velocity = Vector()
        (npc.entity as? LivingEntity)?.let { living ->
        val attribute = living.getAttribute(Attribute.MAX_HEALTH) ?: return@let
        attribute.baseValue = maxOf(attribute.baseValue, settings.ambientMaxHealth)
            if (!living.isDead) living.health = attribute.value
        }
        npc.entity.teleport(settings.location.inWorld(world))
        npc.entity.setRotation(settings.location.yaw, settings.location.pitch)
    }

    /**
     * Runs the real server melee pipeline against an invisible, unarmoured living
     * target. The nested damage event is cancelled before health, durability or
     * enchantment side effects are committed, while its raw damage is retained.
     */
    private fun measureTrainingDamage(player: Player, location: Location): Double? {
        val probeEntity = runCatching {
            player.world.spawn(location, Pig::class.java) { pig ->
                pig.setAdult()
                pig.setAI(false)
                pig.isSilent = true
                pig.isInvisible = true
                pig.isCollidable = false
                pig.setGravity(false)
                pig.isPersistent = false
                pig.addScoreboardTag(DAMAGE_PROBE_TAG)
            }
        }.getOrNull() ?: return null
        val probe = OriginTrainingDamageProbe(player.uniqueId)
        damageProbes[probeEntity.uniqueId] = probe
        return try {
            Bukkit.getOnlinePlayers().forEach { viewer -> viewer.hideEntity(ArcOriginPlugin.instance, probeEntity) }
            runCatching {
                player.attack(probeEntity)
                probe.damage
            }.getOrNull()
        } finally {
            damageProbes.remove(probeEntity.uniqueId)
            probeEntity.remove()
        }
    }

    private fun dummyEffectLocation(settings: OriginTrainingDummyConfig): Location {
        val world = Bukkit.getWorld(settings.world) ?: error("Origin training world is unavailable")
        return settings.location.inWorld(world).add(0.0, 1.25, 0.0)
    }

    private fun isDummy(entityId: UUID, settings: OriginTrainingDummyConfig): Boolean =
        runCatching { CitizensAPI.getNPCRegistry().getById(settings.dummyNpcId) }.getOrNull()?.takeIf { it.isSpawned }?.entity?.uniqueId == entityId

    private fun trainer(settings: OriginTrainingDummyConfig) =
        runCatching { CitizensAPI.getNPCRegistry().getById(settings.trainerNpcId) }.getOrNull()?.takeIf { it.isSpawned }

    private fun damageLabel(damage: Double?): String =
        damage?.let { "${String.format(java.util.Locale.US, "%.1f", it)} урона" } ?: "урон не измерен"

    private const val LEGACY_COOLDOWN_FLAG = "rc_origin_forge_test_cooldown"
    private const val DAMAGE_PROBE_TAG = "arc_origin_training_damage_probe"
}
