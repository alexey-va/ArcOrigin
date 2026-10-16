package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

private fun OriginOddJobStep.stage() = (this as OriginOddJobStep.Changed).job.stage
private fun OriginOddJobStep.reason() = (this as OriginOddJobStep.Rejected).reason

class OriginOddJobsProgressTest : StringSpec({
    "yard requires separate hay feed and water work before one settlement" {
        val jobs = OriginOddJobsProgress(180_000)
        val player = UUID.randomUUID()
        val job = checkNotNull(jobs.begin(player, OriginOddJobKind.MOUNTYARD, 0))
        jobs.begin(player, OriginOddJobKind.FORGE, 0) shouldBe null
        jobs.act(player, OriginOddJobAction.RETURN, 0).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(player, OriginOddJobAction.HAY, 0).stage() shouldBe OriginOddJobStage.YARD_HAY
        jobs.act(player, OriginOddJobAction.HAY, 699).reason() shouldBe OriginOddJobRejection.TOO_EARLY
        jobs.act(player, OriginOddJobAction.HAY, 700)
        jobs.act(player, OriginOddJobAction.HAY, 1_400).stage() shouldBe OriginOddJobStage.YARD_FEED
        jobs.act(player, OriginOddJobAction.WATER, 1_401).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(player, OriginOddJobAction.FEED, 2_000).stage() shouldBe OriginOddJobStage.YARD_WATER
        jobs.act(player, OriginOddJobAction.WATER, 3_000)
        jobs.act(player, OriginOddJobAction.WATER, 3_700)
        jobs.act(player, OriginOddJobAction.WATER, 4_400).stage() shouldBe OriginOddJobStage.RETURN
        jobs.act(player, OriginOddJobAction.RETURN, 5_000).stage() shouldBe OriginOddJobStage.PAYOUT
        jobs.act(player, OriginOddJobAction.RETURN, 5_000).reason() shouldBe OriginOddJobRejection.PAYOUT_PENDING
        jobs.finish(player, UUID.randomUUID()) shouldBe null
        jobs.finish(player, job.id)?.id shouldBe job.id
        jobs.finish(player, job.id) shouldBe null
    }

    "forge heat window and three good hits cannot be skipped or click-spammed" {
        val jobs = OriginOddJobsProgress(180_000)
        val player = UUID.randomUUID()
        jobs.begin(player, OriginOddJobKind.FORGE, 0)
        jobs.act(player, OriginOddJobAction.HEAT, 0).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(player, OriginOddJobAction.BLANK, 0)
        jobs.act(player, OriginOddJobAction.HEAT, 100)
        jobs.act(player, OriginOddJobAction.HEAT, 4_099).reason() shouldBe OriginOddJobRejection.TOO_EARLY
        jobs.act(player, OriginOddJobAction.HEAT, 7_101).reason() shouldBe OriginOddJobRejection.OVERHEATED
        jobs.current(player)?.stage shouldBe OriginOddJobStage.FORGE_HEAT
        jobs.act(player, OriginOddJobAction.HEAT, 8_000)
        jobs.act(player, OriginOddJobAction.HEAT, 12_000).stage() shouldBe OriginOddJobStage.FORGE_PLACE
        jobs.act(player, OriginOddJobAction.PLACE, 13_000).stage() shouldBe OriginOddJobStage.FORGE_HAMMER
        jobs.act(player, OriginOddJobAction.HAMMER, 13_000).reason() shouldBe OriginOddJobRejection.MISSED_BEAT
        jobs.act(player, OriginOddJobAction.HAMMER, 14_100)
        jobs.current(player)?.progress shouldBe 1
        jobs.act(player, OriginOddJobAction.HAMMER, 14_100).reason() shouldBe OriginOddJobRejection.MISSED_BEAT
        jobs.act(player, OriginOddJobAction.HAMMER, 15_200)
        jobs.current(player)?.progress shouldBe 2
        jobs.act(player, OriginOddJobAction.HAMMER, 16_300).stage() shouldBe OriginOddJobStage.FORGE_QUENCH
        jobs.act(player, OriginOddJobAction.RETURN, 16_400).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(player, OriginOddJobAction.QUENCH, 17_000).stage() shouldBe OriginOddJobStage.RETURN
        jobs.act(player, OriginOddJobAction.RETURN, 18_000).stage() shouldBe OriginOddJobStage.PAYOUT
    }

    "auction manifest owns both item and packaging with harmless retries" {
        for (parcel in OriginAuctionParcel.entries) {
            val jobs = OriginOddJobsProgress(180_000)
            val player = UUID.randomUUID()
            jobs.begin(player, OriginOddJobKind.AUCTION, 0, parcel)
            jobs.choosePacking(player, parcel.packing, 0).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.chooseItem(player, OriginAuctionParcel.entries.first { it != parcel }, 0).reason() shouldBe OriginOddJobRejection.WRONG_ITEM
            jobs.chooseItem(player, parcel, 1).stage() shouldBe OriginOddJobStage.AUCTION_PACK
            jobs.choosePacking(player, OriginAuctionPacking.entries.first { it != parcel.packing }, 2).reason() shouldBe OriginOddJobRejection.WRONG_PACKING
            jobs.choosePacking(player, parcel.packing, 3).stage() shouldBe OriginOddJobStage.AUCTION_DELIVER
            jobs.act(player, OriginOddJobAction.RETURN, 4).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(player, OriginOddJobAction.HANDOFF, 5).stage() shouldBe OriginOddJobStage.RETURN
            jobs.act(player, OriginOddJobAction.RETURN, 6).stage() shouldBe OriginOddJobStage.PAYOUT
        }
    }

    "deadline cancellation and old payment receipt cannot consume a replacement job" {
        val jobs = OriginOddJobsProgress(180_000)
        val player = UUID.randomUUID()
        val old = checkNotNull(jobs.begin(player, OriginOddJobKind.AUCTION, 0, OriginAuctionParcel.BOOK))
        jobs.chooseItem(player, OriginAuctionParcel.BOOK, 180_000).reason() shouldBe OriginOddJobRejection.EXPIRED
        jobs.cancel(player) shouldBe old
        jobs.act(player, OriginOddJobAction.RETURN, 180_001).reason() shouldBe OriginOddJobRejection.NO_JOB
        val next = checkNotNull(jobs.begin(player, OriginOddJobKind.MOUNTYARD, 180_002))
        jobs.finish(player, old.id) shouldBe null
        jobs.current(player) shouldBe next
        jobs.cancel(player) shouldBe next
        jobs.entries() shouldBe emptyList()
    }
})
