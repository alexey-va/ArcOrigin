package ru.arc.origin.scene

import org.bukkit.Material

internal class OriginSceneCropProtection(
    private val world: String,
    private val actorIds: Set<Int>,
) {
    fun protects(actualWorld: String, actorId: Int?, material: Material): Boolean =
        actualWorld == world && actorId in actorIds && material in CROP_BLOCKS

    companion object {
        private val CROP_BLOCKS = setOf(
            Material.FARMLAND, Material.WHEAT, Material.CARROTS,
            Material.POTATOES, Material.BEETROOTS,
        )

        fun from(plan: OriginScenePlan): OriginSceneCropProtection {
            val actors = plan.scenes.flatMap { scene ->
                scene.cycles.filter { cycle ->
                    cycle.steps.any { step ->
                        val profile = when (step) {
                            is OriginSceneStep.Move -> step.routeProfile
                            is OriginSceneStep.MoveGroup -> step.routeProfile
                            else -> null
                        }
                        Material.FARMLAND in scene.routeProfiles[profile]?.allowedSupportMaterials.orEmpty()
                    }
                }.flatMap { it.actorIds }
            }.toSet()
            return OriginSceneCropProtection(plan.world, actors)
        }
    }
}
