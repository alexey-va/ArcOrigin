package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import ru.arc.core.LifecycleTaskScope
import ru.arc.origin.OriginNpcHolograms
import ru.arc.origin.scene.OriginSceneResources
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import java.util.UUID
import kotlin.math.ceil

/** Native adapter. Conversation tokens own bubbles, facing and every delayed gesture. */
internal class OriginDiningConversationEffects(
    private val life: OriginDiningLife,
    private val venues: Map<Int, String>,
    private val partners: Map<Int, Set<Int>>,
    private val available: (Int) -> Boolean,
) : OriginDiningConversations.Effects {
    private class Owned {
        val resources = OriginSceneResources()
        val gestures = OriginSceneResources()
        val tasks = LifecycleTaskScope()
        val speakers = mutableSetOf<Int>()
        val targets = mutableMapOf<Int, Location>()
    }

    private val owners = mutableMapOf<UUID, Owned>()

    override fun snapshot(actorId: Int): OriginDiningConversations.ActorSnapshot? {
        val npc = actor(actorId) ?: return null
        val venue = venues[actorId] ?: return null
        val location = npc.entity.location
        val partnerNearby = partners[actorId].orEmpty().any { id ->
            actor(id)?.entity?.location?.let {
                it.world == location.world && it.distanceSquared(location) <=
                    OriginDiningLayout.ambientDialogueRange * OriginDiningLayout.ambientDialogueRange
            } == true
        }
        return OriginDiningConversations.ActorSnapshot(actorId, venue,
            available(actorId) && partnerNearby &&
                (!OriginNpcHolograms.hasTemporaryBubble(actorId) || owners.values.any { actorId in it.speakers }),
            life.hasAudience(location))
    }

    override fun face(ownerToken: UUID, actorId: Int, targetActorId: Int) {
        val speaker = actor(actorId) ?: return
        val other = actor(targetActorId) ?: return
        val owned = owners.getOrPut(ownerToken, ::Owned)
        val target = other.entity.location
        owned.targets[actorId] = target.clone()
        owned.resources.faceHorizontal(speaker, target)
    }

    override fun speak(ownerToken: UUID, actorId: Int, text: String, ttlMillis: Long) {
        val owned = owners.getOrPut(ownerToken, ::Owned)
        owned.speakers += actorId
        OriginNpcHolograms.showTemporaryBubble(actorId, listOf(text),
            ceil(ttlMillis / 50.0).toInt(), ownerToken.toString())
        info("ORIGIN_DINING phase=AMBIENT_DIALOGUE_LINE owner={} npc={} read_ms={}", ownerToken, actorId, ttlMillis)
    }

    override fun gesture(ownerToken: UUID, gesture: OriginDiningConversations.Gesture, actorIds: Set<Int>) {
        val owned = owners.getOrPut(ownerToken, ::Owned)
        if (gesture == OriginDiningConversations.Gesture.TOAST) {
            val mug = life.conversationMug() ?: return
            actorIds.forEach { id -> actor(id)?.let { npc ->
                owned.gestures.equip(npc, mug)
                owned.gestures.useItem(npc)
                (npc.entity as? LivingEntity)?.swingMainHand()
            } }
            owned.tasks.runLater(24) {
                if (owners[ownerToken] === owned) requireClean(owned.gestures)
            }
        } else {
            actorIds.forEach { id -> actor(id)?.let { npc ->
                owned.targets[id]?.let { owned.resources.faceHorizontal(npc, it, pitch = 6f) }
            } }
            owned.tasks.runLater(6) {
                if (owners[ownerToken] === owned) actorIds.forEach { id -> actor(id)?.let { npc ->
                    owned.targets[id]?.let { owned.resources.faceHorizontal(npc, it) }
                } }
            }
        }
    }

    override fun cleanup(ownerToken: UUID) {
        val owned = owners[ownerToken] ?: return
        owned.tasks.close()
        owned.speakers.forEach { OriginNpcHolograms.clearTemporaryBubble(it, ownerToken.toString()) }
        val failures = owned.gestures.cleanup() + owned.resources.cleanup()
        check(failures.isEmpty()) { "Dining conversation cleanup failed: ${failures.map { it.resource }}" }
        owners.remove(ownerToken, owned)
    }

    private fun requireClean(resources: OriginSceneResources) {
        val failures = resources.cleanup()
        if (failures.isNotEmpty()) throw IllegalStateException("Dining gesture cleanup failed").apply {
            failures.forEach { addSuppressed(it.failure) }
        }
    }

    override fun reportFailure(ownerToken: UUID, stage: String, failure: Exception) {
        warn("ORIGIN_DINING phase=CONVERSATION_FAILED owner=$ownerToken stage=$stage", failure)
    }

    private fun actor(id: Int): NPC? = CitizensAPI.getNPCRegistry().getById(id)?.takeIf {
        it.isSpawned && it.entity.world.name == OriginDiningLayout.WORLD
    }
}
