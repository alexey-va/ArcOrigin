package ru.arc.origin.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class OriginCompletionTest : StringSpec({
    "public portal entry does not advertise administrative moves" {
        val player = mockk<Player>()
        every { player.hasPermission(any<String>()) } returns false
        OriginPortalsSubCommand.tabComplete(player, arrayOf("")) shouldBe listOf("enter")
        OriginPortalsSubCommand.tabComplete(player, arrayOf("enter", "sl")) shouldBe listOf("slimefun")
        OriginPortalsSubCommand.tabComplete(player, arrayOf("move", "")) shouldBe emptyList()
        every { player.hasPermission("arc.origin.portals.admin") } returns true
        OriginPortalsSubCommand.tabComplete(player, arrayOf("m")) shouldBe listOf("move")
        OriginPortalsSubCommand.tabComplete(player, arrayOf("move", "sur")) shouldBe listOf("survival")
    }

    "brewery choices require the order action and stop at its terminal argument" {
        val player = mockk<Player>()
        BrewerySubCommand.tabComplete(player, arrayOf("unknown", "")) shouldBe emptyList()
        BrewerySubCommand.tabComplete(player, arrayOf("ORDER", "fo")) shouldBe listOf("food")
        BrewerySubCommand.tabComplete(player, arrayOf("order", "food", "")) shouldBe emptyList()
        BrewerySubCommand.tabComplete(mockk<CommandSender>(), arrayOf("")) shouldBe emptyList()
    }
})
