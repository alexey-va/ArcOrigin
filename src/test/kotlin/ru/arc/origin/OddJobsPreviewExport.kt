package ru.arc.origin

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/** Exact production workpieces above their contact plane; this is not a live-world or client capture. */
internal object OddJobsPreviewExport {
    fun write(output: Path) {
        val damaged = OriginForgeWork(listOf(2, 3, 2), listOf(0, 3, 2))
        val repaired = damaged.copy(shape = damaged.target)
        fun state(id: String, title: String, pieces: List<OriginWorkshopWorkpiecePiece>) = mapOf(
            "id" to id, "title" to title, "role" to "odd-jobs", "pieces" to pieces.mapIndexed { index, piece ->
                mapOf("key" to "$id-$index", "material" to piece.material.name,
                    "x" to piece.center.x, "y" to piece.center.y, "z" to piece.center.z,
                    "width" to piece.size.x, "height" to piece.size.y, "depth" to piece.size.z)
            })
        val states = listOf(
            state("repair-cold", "Ремонт · укороченная левая ветвь", originOddJobForgePieces(damaged, false)),
            state("repair-hot", "Ремонт · та же форма после нагрева", originOddJobForgePieces(damaged, true)),
            state("repaired-hot", "Ремонт · восстановленная ветвь", originOddJobForgePieces(repaired, true)),
            state("repaired-cold", "Ремонт · форма после закалки", originOddJobForgePieces(repaired, false)),
            state("trough-dirty", "Поилка · три грязных участка", originOddJobCleaningPieces(0)),
            state("trough-middle-clean", "Поилка · очищен средний участок", originOddJobCleaningPieces(2)),
            state("trough-clean", "Поилка · очищены все участки", originOddJobCleaningPieces(7)),
        )
        val scene = mapOf("states" to states,
            "coordinates" to mapOf("space" to "station-contact-plane", "origin" to "actual supporting plane", "unit" to "block", "yaw" to 0),
            "camera" to mapOf("position" to mapOf("x" to 0.75, "y" to 0.72, "z" to -1.2),
                "target" to mapOf("x" to 0.0, "y" to 0.04, "z" to 0.0), "fovDegrees" to 70, "near" to 0.05, "far" to 8),
            "evidence" to "Production forge profile and cleaning geometry. Supporting world, player hand, native lighting and glow are not simulated.")
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(scene))
    }
}
