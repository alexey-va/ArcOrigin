package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class OriginDiningDialogueSequenceTest : FreeSpec({
    "sequence alternates speakers and exposes indexed lines" {
        val dialogue = OriginDiningDialogue(
            id = "test",
            firstNpcId = 416,
            secondNpcId = 417,
            lines = listOf("Первый вопрос", "Второй ответ", "Третий вопрос", "Четвёртый ответ"),
        )

        listOf(0, 2).map(dialogue::speakerNpcId) shouldContainExactly listOf(416, 416)
        listOf(1, 3).map(dialogue::speakerNpcId) shouldContainExactly listOf(417, 417)
        dialogue.line(0) shouldBe OriginDiningDialogueLine(0, 416, "Первый вопрос")
        dialogue.line(1) shouldBe OriginDiningDialogueLine(1, 417, "Второй ответ")
    }

    "missing lines list uses the legacy two-line fallback" {
        parseOriginDiningDialogueLines("legacy", null, "Старая первая", "Старый второй") shouldContainExactly
            listOf("Старая первая", "Старый второй")
    }

    "tokenized cursor advances once and rejects stale callbacks" {
        val token = UUID.randomUUID()
        val dialogue = OriginDiningDialogue(
            id = "cursor",
            firstNpcId = 416,
            secondNpcId = 417,
            lines = listOf("Раз", "Два", "Три", "Четыре"),
        )
        val sequence = OriginDiningDialogueSequence(token, dialogue)

        sequence.nextLine(token)?.index shouldBe 0
        sequence.nextLine(UUID.randomUUID()) shouldBe null
        sequence.nextLine(token)?.index shouldBe 1
        sequence.nextLine(token)?.index shouldBe 2
        sequence.nextLine(token)?.index shouldBe 3
        sequence.isComplete shouldBe true
        sequence.nextLine(token) shouldBe null

        val cancelled = OriginDiningDialogueSequence(UUID.randomUUID(), dialogue)
        cancelled.cancel(cancelled.token) shouldBe true
        cancelled.cancel(cancelled.token) shouldBe false
        cancelled.nextLine(cancelled.token) shouldBe null
    }

    "invalid configured sequence shape is rejected with the dialogue id" - {
        listOf(
            "too short" to listOf("одна"),
            "odd" to listOf("раз", "два", "три"),
            "blank" to listOf("раз", " ", "три", "четыре"),
        ).forEach { (case, lines) ->
            "$case" {
                val error = shouldThrow<IllegalArgumentException> {
                    parseOriginDiningDialogueLines("broken", lines, "legacy first", "legacy second")
                }
                error.message shouldContain "broken"
            }
        }
    }

    "blank legacy fallback is rejected" {
        val error = shouldThrow<IllegalArgumentException> {
            parseOriginDiningDialogueLines("legacy-broken", null, " ", "")
        }
        error.message shouldContain "legacy-broken"
    }
})
