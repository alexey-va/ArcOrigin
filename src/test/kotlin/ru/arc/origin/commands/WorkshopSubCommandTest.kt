package ru.arc.origin.commands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.origin.originWorkshopSleepNpcIds

class WorkshopSubCommandTest : FreeSpec({
    "sleep command accepts only the four configured workshop NPC IDs" {
        originWorkshopSleepNpcIds() shouldBe listOf(430, 458, 459, 460)
        originWorkshopSleepNpcIds().forEach { id ->
            resolveWorkshopSleepNpcId(id.toString()) shouldBe id
        }
        listOf("", "0", "431", "4600", "430.0", "sleep", " 430", "+430", "0430", "-430").forEach { raw ->
            resolveWorkshopSleepNpcId(raw) shouldBe null
        }
    }

    "tab completion exposes the action then only the four exact IDs" {
        workshopSleepTabCompletions(arrayOf("")) shouldBe listOf("sleep")
        workshopSleepTabCompletions(arrayOf("Sl")) shouldBe listOf("sleep")
        workshopSleepTabCompletions(arrayOf("sleep", "")) shouldBe listOf("430", "458", "459", "460")
        workshopSleepTabCompletions(arrayOf("SLEEP", "4")) shouldBe listOf("430", "458", "459", "460")
        workshopSleepTabCompletions(arrayOf("sleep", "46")) shouldBe listOf("460")
        workshopSleepTabCompletions(arrayOf("wake", "")) shouldBe emptyList()
        workshopSleepTabCompletions(arrayOf("sleep", "", "extra")) shouldBe emptyList()
    }
})
