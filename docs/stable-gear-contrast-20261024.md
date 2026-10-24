# Stable supply targets and concrete supports

ArcOrigin 0.1.69 fixes the distance cue in `OriginYardEquipment`: only the
current rack target grows. Inactive tools, the public idle rack and unrelated
return plates stay at ordinary size. The full-height shovel remains unscaled;
its empty hook grows only during `CLEAN_TOOL_RETURN`. Rendered geometry, hit
planes and hint positions use the same pose.

`originOddJobStations` accepts `BARREL` or `WHITE_CONCRETE` at the three exact
authored supply blocks. It still does not substitute adjacent blocks. These
props are native BlockDisplay composites from `OriginYardGearGeometry`, not
ItemsAdder furniture; no resource-pack change is involved.

Native read-only survey on 2026-10-24 at 21:52 MSK confirmed the owner's
construction in `rc_origin_spawn`:

| Supply | Supporting blocks | Resulting contact |
| --- | --- | --- |
| Bucket | White concrete at `55,70,-135` and `55,71,-135`; east side is air | Side shelf touches X56 at Y71; bucket bottom is Y71.025, in front of the wall |
| Brush | White concrete at `55,70,-134` and `55,71,-134`; east side is air | Wall plate touches X56; brush points east, its back touches the plate at X56.025 |
| Shovel | Same exposed wall | Hook touches X56 at scale 1 through 1.55; shovel clears the wall by 0.015 block |
| Saddle/lead | White concrete at `65,70,-134`, air above | Existing rack remains at the authored source |

The two remaining adjacent barrels at `66,70,-133` and `66,71,-133` were
replaced with white concrete through managed scene `origin_stable_gear_supports`
revision 1. Native readback is healthy; a second preview has zero changes.
Other player construction, the carpet and inventories were not changed.

Focused JDK25 verification passed 29 tests across `OriginYardEquipmentTest`,
`OriginYardGearPoseTest`, `OriginYardGearTest` and `OriginOddJobsSurfaceTest`.
It covers independent viewers, target changes, concrete lookup and support
contact at scales 1/1.275/1.55. Rewards, task progress and settlement are unchanged.

The existing Kotlin exporter supplies twelve pickup/return views at 2m and
6m. The source-driven preview uses actual Minecraft 1.21.11 block models and
textures; its mesh check reports zero near-coplanar overlapping face pairs.
Independent visual review rejected the initial bucket-on-top placement because
its empty return plate was hidden behind the two-high wall at player eye level.
The bucket and shelf were moved together to the exposed side; no separate
interaction target is left behind the wall.
The three affected specs passed another 15 tests after that correction.
Local scene, viewer, input hashes and survey receipt are retained under
`task-artifacts/stable-gear-contrast-20261024` in the workspace. Preview lighting
does not reproduce native Minecraft occlusion. Delivery, activation and
ordinary-client pixels remain separate evidence.
