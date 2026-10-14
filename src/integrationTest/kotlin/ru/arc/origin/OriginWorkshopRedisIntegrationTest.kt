package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import ru.arc.testing.containers.RedisTestService
import java.util.UUID
import java.util.concurrent.TimeUnit

class OriginWorkshopRedisIntegrationTest : FreeSpec({
    "workshop quota uses an ARC primary lease for atomic claim and duplicate rejection" {
        RedisTestService.start().use { redis ->
            MockBukkitTestRuntime.open().use { paper ->
                val host = paper.createSimplePlugin("ARC")
                val consumer = paper.createSimplePlugin("ArcOrigin")
                PaperRedisServiceProvider(host).use { provider ->
                    provider.configurePrimary(
                        ArcRedisSettings(redis.endpoint.host, redis.endpoint.port, null, null),
                        "origin",
                    )
                    PaperRedisClient.openPrimary(consumer).use { client ->
                        client.healthCheck().get(10, TimeUnit.SECONDS) shouldBe true

                        val key = "arc:origin:workshop-integration:${UUID.randomUUID()}"
                        val field = UUID.randomUUID().toString()
                        val request = UUID.randomUUID()
                        val claim = WorkshopCraftClaim(request.toString(), System.currentTimeMillis() + 60_000)
                        val updater = RedisHashUpdater(client, key, workshopCraftClaimCodec())

                        updater.update(field) { current ->
                            if (workshopClaimAllowed(current, request, System.currentTimeMillis())) {
                                RedisHashDecision.Write(claim)
                            } else {
                                RedisHashDecision.Reject
                            }
                        }.get(10, TimeUnit.SECONDS) shouldBe RedisHashUpdateResult.Changed(null, claim)

                        client.loadMapEntries(key, field).get(10, TimeUnit.SECONDS)
                            .single()?.let(workshopCraftClaimCodec()::decode) shouldBe claim

                        updater.update(field) { current ->
                            if (workshopClaimAllowed(current, request, System.currentTimeMillis())) {
                                RedisHashDecision.Write(claim)
                            } else {
                                RedisHashDecision.Reject
                            }
                        }.get(10, TimeUnit.SECONDS) shouldBe RedisHashUpdateResult.Rejected(claim)
                    }
                    provider.diagnostics().activeLeases shouldBe 0
                }
            }
        }
    }
})
