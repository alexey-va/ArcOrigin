package ru.arc.origin

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/** Uses the same phase sampler and geometry as the live packet owner, at its two-tick cadence. */
object SlimefunWorkshopPreviewExport {
    @JvmStatic
    fun main(args: Array<String>) {
        val destination = Path.of(args.single())
        val timing = SlimefunWorkshopTiming()
        fun state(machine: SlimefunWorkshopMachine, age: Int): Map<String, Any> {
            val frame = slimefunWorkshopFrame(machine, age.toDouble(), timing)
            return mapOf(
                "id" to "${machine.name.lowercase()}-$age",
                "title" to "${machine.name} · ${frame.phase} · ${age / 20.0} s",
                "role" to "Slimefun electromagnetic component · geometry / sampled motion",
                "tick" to age,
                "pieces" to frame.parts,
                "camera" to mapOf(
                    "id" to "player-eye", "position" to mapOf("x" to .15, "y" to 1.62, "z" to -3.4),
                    "target" to mapOf("x" to 0.0, "y" to 1.47, "z" to -.1), "fovDegrees" to 70.0,
                    "near" to .05, "far" to 12.0,
                ),
            )
        }
        val sampleAges = SlimefunWorkshopPhase.entries.flatMap { phase ->
            val start = timing.start(phase)
            val duration = timing.durations[phase.ordinal]
            listOf(start, start + duration / 2)
        } + (timing.start(SlimefunWorkshopPhase.PROCESS) + timing.process * .16).toInt()
        val export = mapOf(
            "source" to "OriginSlimefunWorkshopGeometry.kt; runtime cadence=2 ticks; no native lighting/particles/NPC skin",
            "states" to SlimefunWorkshopMachine.entries.flatMap { machine -> sampleAges.distinct().sorted().map { state(machine, it) } },
            "animations" to SlimefunWorkshopMachine.entries.associate { machine ->
                machine.name.lowercase() to (0 until timing.total step 2).map { state(machine, it) }
            },
        )
        Files.createDirectories(destination.toAbsolutePath().parent)
        Files.writeString(destination, GsonBuilder().create().toJson(export))
        println("Slimefun workshop source preview: $destination; cycle=${timing.total} ticks")
    }
}
