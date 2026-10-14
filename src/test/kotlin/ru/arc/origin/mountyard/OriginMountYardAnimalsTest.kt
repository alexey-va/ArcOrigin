package ru.arc.origin.mountyard

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OriginMountYardAnimalsTest : FreeSpec({
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val npcUuid = UUID.fromString("00000000-0000-0000-0000-000000000371")
    val animal = OriginMountYardAnimal(
        npcId = 371,
        name = "Ветер",
        entityType = "HORSE",
        npcUuid = npcUuid,
        friendlyLines = listOf("Ветер доверчиво тянется к ладони."),
        warningLines = listOf("Ветер перестаёт терпеть щекотку."),
        annoyedSound = "ENTITY_HORSE_ANGRY",
    )

    "catalog accepts only the owned npc and stable Citizens uuid fence" {
        val catalog = OriginMountYardAnimalCatalog(listOf(animal))

        catalog.resolve(371, npcUuid) shouldBe animal
        // A respawned Bukkit entity may have another UUID; it must not become
        // the owner of the configured NPC without the stable Citizens UUID.
        catalog.resolve(371, UUID.fromString("00000000-0000-0000-0000-000000000372")) shouldBe null
        catalog.resolve(999, npcUuid) shouldBe null
    }

    "four accepted pets inside six seconds trigger ten seconds of annoyance" {
        val tracker = OriginMountYardPetTracker(
            OriginMountYardPetRules(
                petWindowMillis = 6_000,
                petCooldownMillis = 500,
                petsBeforeAnnoyed = 4,
                annoyedDurationMillis = 10_000,
            ),
        )

        tracker.pet(player, animal.npcId, 1_000) shouldBe OriginMountYardPetResult.Friendly(1)
        tracker.pet(player, animal.npcId, 1_100) shouldBe OriginMountYardPetResult.Cooldown
        tracker.pet(player, animal.npcId, 1_500) shouldBe OriginMountYardPetResult.Friendly(2)
        tracker.pet(player, animal.npcId, 2_000) shouldBe OriginMountYardPetResult.Friendly(3)
        tracker.pet(player, animal.npcId, 2_500) shouldBe OriginMountYardPetResult.AnnoyedTriggered(untilMillis = 12_500)
        // The cooldown is elapsed at the exact boundary, then starts again.
        tracker.pet(player, animal.npcId, 3_000) shouldBe OriginMountYardPetResult.Annoyed(untilMillis = 12_500)
        tracker.pet(player, animal.npcId, 3_100) shouldBe OriginMountYardPetResult.Cooldown
        tracker.pet(player, animal.npcId, 12_500) shouldBe OriginMountYardPetResult.Friendly(1)
    }

    "old pets leave the six second window and quit clears bounded state" {
        val tracker = OriginMountYardPetTracker(OriginMountYardPetRules(petCooldownMillis = 0))

        tracker.pet(player, animal.npcId, 1_000)
        tracker.pet(player, animal.npcId, 7_001) shouldBe OriginMountYardPetResult.Friendly(1)
        tracker.stateCount() shouldBe 1
        tracker.clearPlayer(player)
        tracker.stateCount() shouldBe 0
    }

    "reaction damage never reduces a target below one health" {
        nonLethalReactionDamage(targetHealth = 20.0, requestedDamage = 2.0) shouldBe 2.0
        nonLethalReactionDamage(targetHealth = 1.5, requestedDamage = 2.0) shouldBe 0.5
        nonLethalReactionDamage(targetHealth = 1.0, requestedDamage = 2.0) shouldBe 0.0
        nonLethalReactionDamage(targetHealth = 0.5, requestedDamage = 2.0) shouldBe 0.0
    }

    "synthetic event clamp handles a plugin-adjusted final damage" {
        clampSyntheticReactionDamage(targetHealth = 4.0, finalDamage = 20.0) shouldBe 3.0
        clampSyntheticReactionDamage(targetHealth = 4.0, finalDamage = 2.0) shouldBe 2.0
        clampSyntheticReactionDamage(targetHealth = 1.0, finalDamage = 20.0) shouldBe 0.0
    }
})
