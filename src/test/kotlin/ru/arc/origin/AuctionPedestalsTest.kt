package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

class AuctionPedestalsTest :
    FreeSpec({
        "stand rules allow an empty list and reject duplicates or overlapping hitboxes" {
            AuctionPedestalRules.validate(emptyList())
            val first = AuctionPedestalRules.create(0.5, 64.0, 0.5, 0f, "one")
            val duplicate = AuctionPedestalRules.create(0.5, 64.0, 0.5, 180f, "two")
            val overlap = AuctionPedestalRules.create(2.0, 64.0, 0.5, 0f, "three")

            AuctionPedestalRules.placementFailure(duplicate, listOf(first)) shouldBe
                AuctionPedestalPlacementFailure.DUPLICATE
            AuctionPedestalRules.placementFailure(overlap, listOf(first)) shouldBe
                AuctionPedestalPlacementFailure.OVERLAP
        }

        "full snapshots persist, including an explicit empty override" {
            val directory = Files.createTempDirectory("arc-auction-pedestals-store")
            val store = AuctionPedestalStore(directory)
            try {
                val initial = AuctionPedestalSnapshot(pedestals = listOf(AuctionPedestalRules.create(-3.5, 72.0, 8.5, 90f)))
                store.save(initial).join()
                store.load().join() shouldBe initial

                val empty = AuctionPedestalSnapshot(pedestals = emptyList())
                store.save(empty).join()
                store.load().join() shouldBe empty
                Files.exists(directory.resolve("data/auction-showcase-pedestals.json")) shouldBe true
            } finally {
                store.closeAsync().join()
                directory.toFile().deleteRecursively()
            }
        }

        "a load queued behind a save sees the committed snapshot and close waits for writer initialization" {
            val directory = Files.createTempDirectory("arc-auction-pedestals-order")
            val executor = ManualExecutor()
            val store = AuctionPedestalStore(directory, executor)
            val snapshot = AuctionPedestalSnapshot(pedestals = listOf(AuctionPedestalRules.create(1.5, 70.5, -8.5, 0f)))
            try {
                val save = store.save(snapshot)
                val load = store.load()
                val close = store.closeAsync()
                close.isDone shouldBe false

                executor.runAll()

                save.join()
                load.join() shouldBe snapshot
                close.join()
            } finally {
                executor.runAll()
                directory.toFile().deleteRecursively()
            }
        }
    })

private class ManualExecutor : Executor {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    override fun execute(command: Runnable) {
        queue += command
    }

    fun runAll() {
        while (true) (queue.poll() ?: return).run()
    }
}
