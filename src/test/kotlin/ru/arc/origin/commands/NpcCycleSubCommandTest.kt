package ru.arc.origin.commands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.command.CommandSender
import ru.arc.origin.scene.OriginAmbientScenesModule
import ru.arc.origin.scene.OriginSceneCycleKey
import ru.arc.origin.scene.OriginSceneStatus

class NpcCycleSubCommandTest : FreeSpec({
    val keys = listOf(
        OriginSceneCycleKey("forge", "apprentice-engraving"),
        OriginSceneCycleKey("forge", "furnace-check"),
        OriginSceneCycleKey("mount-yard", "groom-wind"),
    )

    "resolves legacy forge actor aliases" {
        resolveOriginSceneCycle(keys, arrayOf("forge-luka")) shouldBe
            OriginSceneCycleKey("forge", "apprentice-engraving")
        resolveOriginSceneCycle(keys, arrayOf("forge-yar")) shouldBe
            OriginSceneCycleKey("forge", "furnace-check")
    }

    "resolves unique ids and explicit scene ids" {
        resolveOriginSceneCycle(keys, arrayOf("furnace-check")) shouldBe
            OriginSceneCycleKey("forge", "furnace-check")
        resolveOriginSceneCycle(keys, arrayOf("mount-yard", "groom-wind")) shouldBe
            OriginSceneCycleKey("mount-yard", "groom-wind")
    }

    "rejects unknown and malformed selectors" {
        resolveOriginSceneCycle(keys, arrayOf("missing")) shouldBe null
        resolveOriginSceneCycle(keys, arrayOf("forge", "furnace-check", "extra")) shouldBe null
    }

    "status filters scene and cycle without starting an animation" {
        val sender = mockk<CommandSender>(relaxed = true)
        val status = OriginSceneStatus("forge", "furnace-check", "RUNNING", "cool", 2, 5,
            listOf(351), 1, 4500, 0, "working", null)
        mockkObject(OriginAmbientScenesModule)
        try {
            every { OriginAmbientScenesModule.cycleKeys() } returns keys
            every { OriginAmbientScenesModule.status() } returns listOf(status,
                status.copy(sceneId = "mount-yard", cycleId = "groom-wind"))

            NpcCycleSubCommand.execute(sender, arrayOf("status", "forge", "furnace-check")) shouldBe true
            verify(exactly = 1) { sender.sendMessage(match<String> {
                it.startsWith("ORIGIN_SCENE_STATUS scene=forge cycle=furnace-check") &&
                    it.contains("step=cool progress=3/5") && it.contains("displays=1")
            }) }
            verify(exactly = 0) { OriginAmbientScenesModule.startCycle(any(), any()) }
            NpcCycleSubCommand.tabComplete(sender, arrayOf("status", "f")) shouldBe listOf("forge")
            NpcCycleSubCommand.tabComplete(sender, arrayOf("status", "forge", "fur")) shouldBe listOf("furnace-check")
        } finally { unmockkObject(OriginAmbientScenesModule) }
    }
})
