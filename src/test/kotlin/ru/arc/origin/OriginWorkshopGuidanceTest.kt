package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.sqrt

class OriginWorkshopGuidanceTest : FreeSpec({
    "guidance anchors stay behind the work surface and behind a stock target" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val target = OriginWorkshopPoint(-0.55, 0.38, -0.25)

        originWorkshopGuidanceAnchor(dimensions, target, stock = false) shouldBe OriginWorkshopPoint(
            0.0,
            dimensions.height + 1.15,
            dimensions.depth / 2.0 - 0.42,
        )
        originWorkshopGuidanceAnchor(dimensions, target, stock = true) shouldBe OriginWorkshopPoint(
            target.x,
            1.45,
            target.z + 0.55,
        )
    }

    "assembler stage 14 exports the actual stock guidance and player label style" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val recipe = originWorkshopGameRecipe(
            OriginWorkshopTableRole.ASSEMBLER,
            "preview:assembler",
            dimensions,
            OriginWorkshopMachineTuning(),
            OriginWorkshopPoint(-7.5, 0.54, 0.30),
            OriginWorkshopGameRules(),
        )
        val stage = OriginWorkshopGameStage.ASSEMBLER_PICK_LEFT_LEG
        val interaction = recipe.interactions.getValue(stage)
        val metadata = WorkshopPreviewExport.guidanceMetadata(dimensions, recipe, stage)
        val anchor = metadata.getValue("anchor") as Map<*, *>
        val style = metadata.getValue("style") as Map<*, *>

        stage.step shouldBe 14
        originWorkshopUsesStockGuidance(OriginWorkshopTableRole.ASSEMBLER, interaction) shouldBe true
        metadata.getValue("stage") shouldBe stage.name
        metadata.getValue("step") shouldBe 14
        metadata.getValue("totalSteps") shouldBe 19
        metadata.getValue("text") shouldBe "14/19\n${stage.instruction} · ЛКМ"
        metadata.getValue("anchorMode") shouldBe "stock-behind-target"
        anchor["x"] shouldBe interaction.target.x
        anchor["y"] shouldBe 1.45
        anchor["z"] shouldBe interaction.target.z + 0.55
        style["scale"] shouldBe 0.42f
        style["lineWidth"] shouldBe 220
        style["viewRange"] shouldBe 0.55f
        style["billboard"] shouldBe "CENTER"
        style["shadowed"] shouldBe true
        style["textOpacity"] shouldBe 230
    }

    "front-edge camera frames the workbench interaction and its label at standing eye height" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val recipe = originWorkshopGameRecipe(
            OriginWorkshopTableRole.ASSEMBLER,
            "preview:assembler-camera",
            dimensions,
            OriginWorkshopMachineTuning(),
            OriginWorkshopPoint(-7.5, 0.54, 0.30),
            OriginWorkshopGameRules(),
        )
        val stage = OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL
        val camera = WorkshopPreviewExport.frontEdgeCameraMetadata(dimensions, recipe, stage)
        val metadata = WorkshopPreviewExport.guidanceMetadata(dimensions, recipe, stage)
        val position = camera.getValue("position") as Map<*, *>
        val target = camera.getValue("target") as Map<*, *>
        val style = metadata.getValue("style") as Map<*, *>

        position["x"] shouldBe 0.0
        position["y"] shouldBe 1.62
        position["z"] shouldBe -dimensions.depth / 2.0 - 0.45
        camera.getValue("eyeHeight") shouldBe 1.62
        camera.getValue("fovDegrees") shouldBe 70.0
        camera.getValue("near") shouldBe 0.05
        camera.getValue("far") shouldBe 8.0
        metadata.getValue("text") shouldBe "8/19\n${stage.instruction} · ЛКМ"
        style["lineWidth"] shouldBe 220
        style["scale"] shouldBe 0.42f

        val guide = WorkshopPreviewExport.guidanceAnchor(dimensions, recipe, stage)
        val workTarget = recipe.interactions.getValue(stage).target
        val viewTarget = OriginWorkshopPoint(
            (workTarget.x + guide.x) / 2.0,
            (workTarget.y + guide.y) / 2.0,
            (workTarget.z + guide.z) / 2.0,
        )
        target["x"] shouldBe viewTarget.x
        target["y"] shouldBe viewTarget.y
        target["z"] shouldBe viewTarget.z
        val labelDistance = distance(camera, guide)
        val interactionDistance = distance(camera, workTarget)
        (labelDistance > 0.05 && labelDistance < 8.0) shouldBe true
        (interactionDistance > 0.05 && interactionDistance < 8.0) shouldBe true
        (abs(verticalAngleDegrees(camera, guide)) < 35.0) shouldBe true
        (abs(verticalAngleDegrees(camera, workTarget)) < 35.0) shouldBe true
        val horizontalHalfFov = Math.toDegrees(kotlin.math.atan(kotlin.math.tan(Math.toRadians(35.0)) * 16.0 / 9.0))
        (abs(horizontalAngleDegrees(camera, guide)) < horizontalHalfFov) shouldBe true
        (abs(horizontalAngleDegrees(camera, workTarget)) < horizontalHalfFov) shouldBe true
    }

    "stage 14 uses an independent stock camera that frames the stock target and its label" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val recipe = originWorkshopGameRecipe(
            OriginWorkshopTableRole.ASSEMBLER,
            "preview:assembler-stock-camera",
            dimensions,
            OriginWorkshopMachineTuning(),
            OriginWorkshopPoint(-7.5, 0.54, 0.30),
            OriginWorkshopGameRules(),
        )
        val stage = OriginWorkshopGameStage.ASSEMBLER_PICK_LEFT_LEG
        val camera = WorkshopPreviewExport.stockCameraMetadata(dimensions, recipe, stage)
        val position = camera.getValue("position") as Map<*, *>
        val interaction = recipe.interactions.getValue(stage).target
        val guide = WorkshopPreviewExport.guidanceAnchor(dimensions, recipe, stage)

        position["x"] shouldBe interaction.x
        position["y"] shouldBe 1.62
        position["z"] shouldBe interaction.z - 2.0
        camera.getValue("coordinateSpace") shouldBe "table-local"
        camera.getValue("near") shouldBe 0.05
        camera.getValue("far") shouldBe 8.0
        (distance(camera, interaction) in 0.05..8.0) shouldBe true
        (distance(camera, guide) in 0.05..8.0) shouldBe true
        (abs(verticalAngleDegrees(camera, interaction)) < 35.0) shouldBe true
        (abs(verticalAngleDegrees(camera, guide)) < 35.0) shouldBe true
    }

    "assembler fastener cue clears the pin while staying within the click target radius" {
        val pin = originWorkshopAssemblerLegPieces(fastened = true).last()
        val marker = originWorkshopPlacementMarker(
            OriginWorkshopGameAction.TIGHTEN_RIGHT,
            targetFaceDepth = pin.size.z.toDouble(),
        ).single()
        val pinFront = pin.center.z - pin.size.z / 2.0
        val markerBack = pin.center.z + marker.center.z + marker.size.z / 2.0
        val clearance = pinFront - markerBack
        val targetOffset = sqrt(marker.center.x * marker.center.x + marker.center.y * marker.center.y + marker.center.z * marker.center.z)

        (abs(clearance - 0.0025) < 1.0e-7) shouldBe true
        (targetOffset <= 0.36) shouldBe true

        val carpenterMarker = originWorkshopPlacementMarker(OriginWorkshopGameAction.TIGHTEN_RIGHT).single()
        carpenterMarker.size shouldBe OriginWorkshopGamePartSize(0.14f, 0.14f, 0.14f)
        carpenterMarker.center shouldBe OriginWorkshopPoint(0.0, 0.08, 0.0)
    }
})

private fun distance(camera: Map<String, Any>, point: OriginWorkshopPoint): Double {
    val position = cameraPoint(camera, "position")
    val dx = point.x - position.x
    val dy = point.y - position.y
    val dz = point.z - position.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun verticalAngleDegrees(camera: Map<String, Any>, point: OriginWorkshopPoint): Double {
    val position = cameraPoint(camera, "position")
    val target = cameraPoint(camera, "target")
    val forward = normalize(
        target.x - position.x,
        target.y - position.y,
        target.z - position.z,
    )
    val right = normalize(forward.third, 0.0, -forward.first)
    val up = normalize(
        forward.second * right.third - forward.third * right.second,
        forward.third * right.first - forward.first * right.third,
        forward.first * right.second - forward.second * right.first,
    )
    val relative = Triple(
        point.x - position.x,
        point.y - position.y,
        point.z - position.z,
    )
    val along = forward.first * relative.first + forward.second * relative.second + forward.third * relative.third
    val vertical = up.first * relative.first + up.second * relative.second + up.third * relative.third
    return Math.toDegrees(kotlin.math.atan2(vertical, along))
}

private fun horizontalAngleDegrees(camera: Map<String, Any>, point: OriginWorkshopPoint): Double {
    val position = cameraPoint(camera, "position")
    val target = cameraPoint(camera, "target")
    val forward = normalize(
        target.x - position.x,
        target.y - position.y,
        target.z - position.z,
    )
    val right = normalize(forward.third, 0.0, -forward.first)
    val relative = Triple(
        point.x - position.x,
        point.y - position.y,
        point.z - position.z,
    )
    val along = forward.first * relative.first + forward.second * relative.second + forward.third * relative.third
    val sideways = right.first * relative.first + right.second * relative.second + right.third * relative.third
    return Math.toDegrees(kotlin.math.atan2(sideways, along))
}

private fun cameraPoint(camera: Map<String, Any>, key: String): OriginWorkshopPoint {
    val point = camera.getValue(key) as Map<*, *>
    return OriginWorkshopPoint(
        point["x"] as Double,
        point["y"] as Double,
        point["z"] as Double,
    )
}

private fun normalize(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
    val length = sqrt(x * x + y * y + z * z)
    return Triple(x / length, y / length, z / length)
}
