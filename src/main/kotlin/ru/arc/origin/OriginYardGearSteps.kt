package ru.arc.origin

/** The job owns the loan; rendering and input derive from the same exact stage. */
internal data class OriginYardGearStep(val gear: OriginYardGear, val action: OriginOddJobAction,
    val atRack: Boolean, val returning: Boolean = false, val zone: Int = 1)

internal fun originYardGearStep(stage: OriginOddJobStage): OriginYardGearStep? = when (stage) {
    OriginOddJobStage.YARD_HAY -> OriginYardGearStep(OriginYardGear.HAY, OriginOddJobAction.HAY, true)
    OriginOddJobStage.YARD_BUCKET -> OriginYardGearStep(OriginYardGear.BUCKET, OriginOddJobAction.WATER, true)
    OriginOddJobStage.YARD_BRUSH -> OriginYardGearStep(OriginYardGear.BRUSH, OriginOddJobAction.BRUSH, true)
    OriginOddJobStage.YARD_LEAD_RACK -> OriginYardGearStep(OriginYardGear.LEAD, OriginOddJobAction.TACK, true)
    OriginOddJobStage.YARD_TACK -> OriginYardGearStep(OriginYardGear.SADDLE, OriginOddJobAction.TACK, true)
    OriginOddJobStage.YARD_PAD_RACK -> OriginYardGearStep(OriginYardGear.PAD, OriginOddJobAction.GEAR, true)
    OriginOddJobStage.YARD_BRIDLE_RACK -> OriginYardGearStep(OriginYardGear.BRIDLE, OriginOddJobAction.GEAR, true)
    OriginOddJobStage.CLEAN_TOOL -> OriginYardGearStep(OriginYardGear.SHOVEL, OriginOddJobAction.PICKUP, true)
    OriginOddJobStage.YARD_BRUSH_RETURN -> OriginYardGearStep(OriginYardGear.BRUSH, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.YARD_BUCKET_RETURN -> OriginYardGearStep(OriginYardGear.BUCKET, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.YARD_PAD_RETURN -> OriginYardGearStep(OriginYardGear.PAD, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.YARD_SADDLE_RETURN -> OriginYardGearStep(OriginYardGear.SADDLE, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.YARD_BRIDLE_RETURN -> OriginYardGearStep(OriginYardGear.BRIDLE, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.YARD_LEAD_RETURN -> OriginYardGearStep(OriginYardGear.LEAD, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.CLEAN_TOOL_RETURN -> OriginYardGearStep(OriginYardGear.SHOVEL, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.YARD_PAD, OriginOddJobStage.YARD_UNTACK_PAD -> OriginYardGearStep(OriginYardGear.PAD, OriginOddJobAction.GEAR, false)
    OriginOddJobStage.YARD_SADDLE -> OriginYardGearStep(OriginYardGear.SADDLE, OriginOddJobAction.SADDLE, false)
    OriginOddJobStage.YARD_GIRTH, OriginOddJobStage.YARD_UNTACK_SADDLE -> OriginYardGearStep(OriginYardGear.SADDLE, OriginOddJobAction.GEAR, false)
    OriginOddJobStage.YARD_BRIDLE, OriginOddJobStage.YARD_UNTACK_BRIDLE -> OriginYardGearStep(OriginYardGear.BRIDLE, OriginOddJobAction.GEAR, false)
    OriginOddJobStage.YARD_STIRRUP_LEFT -> OriginYardGearStep(OriginYardGear.SADDLE, OriginOddJobAction.GEAR, false, zone = 0)
    OriginOddJobStage.YARD_STIRRUP_RIGHT -> OriginYardGearStep(OriginYardGear.SADDLE, OriginOddJobAction.GEAR, false, zone = 1)
    else -> null
}

internal fun originYardCarriedGear(job: OriginOddJobSession): OriginYardGear? = when (job.stage) {
    OriginOddJobStage.YARD_FEED -> OriginYardGear.HAY
    OriginOddJobStage.YARD_WELL, OriginOddJobStage.YARD_WATER, OriginOddJobStage.YARD_BUCKET_RETURN -> OriginYardGear.BUCKET
    OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_BRUSH_RETURN -> OriginYardGear.BRUSH
    OriginOddJobStage.YARD_PAD, OriginOddJobStage.YARD_PAD_RETURN -> OriginYardGear.PAD
    OriginOddJobStage.YARD_SADDLE, OriginOddJobStage.YARD_SADDLE_RETURN -> OriginYardGear.SADDLE
    OriginOddJobStage.YARD_BRIDLE, OriginOddJobStage.YARD_BRIDLE_RETURN -> OriginYardGear.BRIDLE
    OriginOddJobStage.YARD_LEAD, OriginOddJobStage.YARD_LEAD_OUT, OriginOddJobStage.YARD_DELIVER,
    OriginOddJobStage.YARD_HANDOFF, OriginOddJobStage.YARD_LESSON_WALK, OriginOddJobStage.YARD_LEAD_RETURN -> OriginYardGear.LEAD
    OriginOddJobStage.CLEAN_CART, OriginOddJobStage.CLEAN_PUSH, OriginOddJobStage.CLEAN_RETURN, OriginOddJobStage.CLEAN_SCOOP, OriginOddJobStage.CLEAN_LOAD,
    OriginOddJobStage.CLEAN_UNLOAD, OriginOddJobStage.CLEAN_PARK, OriginOddJobStage.CLEAN_TOOL_RETURN -> OriginYardGear.SHOVEL
    else -> null
}

internal fun originYardUntacking(stage: OriginOddJobStage): Boolean = stage in setOf(
    OriginOddJobStage.YARD_UNTACK_BRIDLE, OriginOddJobStage.YARD_BRIDLE_RETURN,
    OriginOddJobStage.YARD_UNTACK_SADDLE, OriginOddJobStage.YARD_SADDLE_RETURN,
    OriginOddJobStage.YARD_UNTACK_PAD, OriginOddJobStage.YARD_PAD_RETURN, OriginOddJobStage.YARD_LEAD_RETURN,
)
