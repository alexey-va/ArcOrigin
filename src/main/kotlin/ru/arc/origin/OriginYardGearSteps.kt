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
    OriginOddJobStage.CLEAN_TOOL -> OriginYardGearStep(OriginYardGear.SHOVEL, OriginOddJobAction.PICKUP, true)
    OriginOddJobStage.YARD_BRUSH_RETURN -> OriginYardGearStep(OriginYardGear.BRUSH, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.YARD_BUCKET_RETURN -> OriginYardGearStep(OriginYardGear.BUCKET, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.CLEAN_TOOL_RETURN -> OriginYardGearStep(OriginYardGear.SHOVEL, OriginOddJobAction.GEAR, true, true)
    OriginOddJobStage.YARD_SADDLE -> OriginYardGearStep(OriginYardGear.SADDLE, OriginOddJobAction.SADDLE, false)
    else -> null
}

internal fun originYardCarriedGear(job: OriginOddJobSession): OriginYardGear? = when (job.stage) {
    OriginOddJobStage.YARD_FEED -> OriginYardGear.HAY
    OriginOddJobStage.YARD_WELL, OriginOddJobStage.YARD_WATER, OriginOddJobStage.YARD_BUCKET_RETURN -> OriginYardGear.BUCKET
    OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_BRUSH_RETURN -> OriginYardGear.BRUSH
    OriginOddJobStage.YARD_SADDLE -> OriginYardGear.SADDLE
    OriginOddJobStage.YARD_LEAD, OriginOddJobStage.YARD_LEAD_OUT, OriginOddJobStage.YARD_DELIVER,
    OriginOddJobStage.YARD_HANDOFF, OriginOddJobStage.YARD_LESSON_WALK -> OriginYardGear.LEAD
    OriginOddJobStage.CLEAN_CART, OriginOddJobStage.CLEAN_PUSH, OriginOddJobStage.CLEAN_RETURN, OriginOddJobStage.CLEAN_SCOOP, OriginOddJobStage.CLEAN_LOAD,
    OriginOddJobStage.CLEAN_UNLOAD, OriginOddJobStage.CLEAN_PARK, OriginOddJobStage.CLEAN_TOOL_RETURN -> OriginYardGear.SHOVEL
    else -> null
}
