package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import ru.arc.origin.api.ArcOriginNpcHolograms
import java.util.UUID

class OriginAuctionJobHologramTest : StringSpec({
    "auction job label cleanup requires the configured identity and tolerates an absent bridge" {
        val venue = OriginOddJobVenue(OriginOddJobKind.AUCTION, 365,
            UUID.fromString("db71f7f9-032c-4c5b-acc5-2ba132240c1c"), "Аукционист", null, emptySet())
        val holograms = mockk<ArcOriginNpcHolograms>(relaxed = true)
        val npc = mockk<NPC> {
            every { id } returns venue.npcId
            every { uniqueId } returns UUID.randomUUID()
            every { isSpawned } returns false
        }
        var resolvedNpc: NPC? = null
        val registry = mockk<NPCRegistry> { every { getById(venue.npcId) } answers { resolvedNpc } }
        mockkStatic(CitizensAPI::class)
        mockkObject(OriginServices)
        try {
            every { CitizensAPI.getNPCRegistry() } returns registry
            every { OriginServices.npcHolograms } returns holograms
            originClearAuctionJobHologram(venue)
            resolvedNpc = npc
            originClearAuctionJobHologram(venue)
            verify(exactly = 0) { holograms.patchHologram(any(), any(), any()) }

            every { npc.uniqueId } returns venue.npcUuid
            originClearAuctionJobHologram(venue)
            verify(exactly = 1) { holograms.patchHologram(venue.npcId, emptyList(), -1) }

            every { OriginServices.npcHolograms } returns null
            originClearAuctionJobHologram(venue)
            verify(exactly = 1) { holograms.patchHologram(any(), any(), any()) }
        } finally {
            unmockkObject(OriginServices)
            unmockkStatic(CitizensAPI::class)
        }
    }
})
