package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import java.nio.file.Files

class OriginSideJobRewardPolicyTest : StringSpec({
    "disk reload updates the common policy and all employer price text" {
        val directory = Files.createTempDirectory("origin-reward-policy")
        try {
            val file = directory.resolve("rewards.yml")
            Files.writeString(file, "daily-order-limit: 100\nreward-per-order: 20\npayout-interval-seconds: 20\n")
            val source = Config(directory, file.fileName.toString())
            val before = OriginSideJobRewardPolicy.load(source)
            before shouldBe OriginSideJobRewardPolicy(100, 20)
            Files.writeString(file, "daily-order-limit: 60\nreward-per-order: 35\nsaddling-reward-per-order: 75\npayout-interval-seconds: 7\n")
            source.reload()
            val after = OriginSideJobRewardPolicy.load(source)
            after shouldBe OriginSideJobRewardPolicy(60, 35, 75)
            before.rewardPerOrder shouldBe 20
            val waiter = waiterTestConfig().copy(rewardPolicy = after)
            val oddJobs = oddJobTestConfig().copy(rewardPolicy = after)
            val status = SideJobRewardStatus(true, 51, 0, false)
            val screens = listOf(waiterScreen(waiter, waiter.venues.first(), null, emptyList(), after.rewardPerOrder, status, 0)) +
                oddJobs.venues
                    .map { originOddJobsScreen(oddJobs, it, null, after.rewardPerOrder, status, 0) }
            for (screen in screens) {
                val text = screen.body.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it.text) }
                text shouldContain "35 💰"
                text shouldContain "51"
                text shouldContain "60"
                text.contains("{daily-") shouldBe false
            }
            after.dailyAmount shouldBe 4500
            after.limits("{daily-orders} / {daily-amount} / {saddling-amount}") shouldBe "60 / 4500 / 75"
            waiter.line("daily-limit") shouldContain "60"
            // The in-world forge receipt uses the same policy as its employer dialog.
            oddJobs.line("table.pay-value", "amount" to after.rewardPerOrder) shouldContain "35 💰"
        } finally { directory.toFile().deleteRecursively() }
    }

    "ordinary orders pay twenty and every preparation variant pays fifty within the shared order quota" {
        val policy = OriginSideJobRewardPolicy()
        for (variant in OriginOddJobVariant.entries)
            policy.amountFor(variant) shouldBe if (variant.isYardPreparation()) 50 else 20
        policy.dailyOrderLimit shouldBe 100
        policy.dailyAmount shouldBe 5000
    }

    "invalid policy values are rejected instead of being coerced or silently defaulted" {
        val directory = Files.createTempDirectory("origin-reward-invalid")
        try {
            val file = directory.resolve("rewards.yml")
            for ((key, value) in listOf("daily-order-limit" to "0", "daily-order-limit" to "10001",
                "reward-per-order" to "-1", "reward-per-order" to "1.5", "reward-per-order" to "invalid",
                "saddling-reward-per-order" to "0", "saddling-reward-per-order" to "1.5")) {
                Files.writeString(file, "$key: $value\n")
                shouldThrowAny { OriginSideJobRewardPolicy.load(Config(directory, file.fileName.toString())) }
            }
            shouldThrowAny { OriginSideJobRewardPolicy(10_000, 1_000_000) }
            shouldThrowAny { OriginSideJobRewardPolicy(10_000, 20, 1_000_000) }
        } finally { directory.toFile().deleteRecursively() }
    }
})
