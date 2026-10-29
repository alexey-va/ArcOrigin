package ru.arc.origin

import com.google.gson.GsonBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import ru.arc.paper.menu.PaperDialogScreen
import java.nio.file.Files
import java.nio.file.Path

/** Exact runtime cuboids; screen text and native Minecraft lighting remain separate acceptance. */
object SlimefunTerminalPreviewExport {
    /** Same component/font export shape as the existing Skyblock native-font surface renderer. */
    internal fun exportDialog(screen: PaperDialogScreen) {
        val serializer = LegacyComponentSerializer.builder().character('&').hexColors().build()
        val glyphs = linkedMapOf<Int, String>()
        fun export(component: Component): String {
            fun collect(node: Component, inherited: Style) {
                val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
                (node as? TextComponent)?.content()?.codePoints()?.forEach { point ->
                    if (Character.getType(point) == Character.PRIVATE_USE.toInt())
                        glyphs[point] = style.font()?.asString() ?: "minecraft:default"
                }
                node.children().forEach { collect(it, style) }
            }
            collect(component, Style.empty())
            return serializer.serialize(component)
        }
        val rendered = mapOf(
            "title" to export(screen.title), "columns" to screen.columns,
            "body" to screen.body.map { mapOf("text" to export(it.text), "width" to it.width) },
            "inputs" to emptyList<Any>(),
            "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to export(it.label),
                "tooltip" to export(it.tooltip), "width" to it.width) },
            "exitTranslationKey" to (screen.exitButton?.label as? TranslatableComponent)?.key(),
        )
        val destination = Path.of("build/reports/slimefun-terminal/dialog-components.json")
        Files.createDirectories(destination.parent)
        Files.writeString(destination, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(mapOf(
            "screens" to mapOf("terminal" to rendered),
            "glyphs" to glyphs.map { (point, font) -> mapOf("character" to String(Character.toChars(point)), "provider" to font) },
        )))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val destination = Path.of(args.single())
        fun camera(id: String, x: Double, y: Double, z: Double) = mapOf(
            "id" to id, "position" to mapOf("x" to x, "y" to y, "z" to z),
            "target" to mapOf("x" to 0.0, "y" to .9, "z" to 0.0),
            "fovDegrees" to 70.0, "near" to .05, "far" to 12.0,
        )
        val views = listOf(camera("player-eye", 0.0, 1.62, -2.0), camera("side", 2.2, 1.5, -.5),
            camera("plan", 0.0, 4.0, -.01))
        val scene = mapOf(
            "source" to "OriginSlimefunTerminalModule.kt slimefunTerminalParts; static packet scene",
            "states" to views.map { view -> mapOf(
                "id" to view.getValue("id"), "title" to "Slimefun console · ${view.getValue("id")}",
                "role" to "Existing portal control · model geometry preview, no native screen text",
                "pieces" to slimefunTerminalParts(), "camera" to view,
            ) },
        )
        Files.createDirectories(destination.toAbsolutePath().parent)
        Files.writeString(destination, GsonBuilder().create().toJson(scene))
        println("Slimefun terminal source preview: $destination")
    }
}
