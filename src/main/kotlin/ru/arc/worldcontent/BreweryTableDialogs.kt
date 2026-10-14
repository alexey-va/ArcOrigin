package ru.arc.worldcontent

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.origin.OriginDiningModule
import ru.arc.origin.menu.OriginMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

/** Native order dialogs for seated service in the Origin brewery and restaurant. */
object BreweryTableDialogs {
    internal const val ORIGIN_WORLD = "rc_origin_spawn"
    internal const val BREWERY_X = -8.5
    internal const val BREWERY_Y = 70.0
    internal const val BREWERY_Z = 42.5
    internal const val BREWERY_RADIUS = 18.0
    internal const val RESTAURANT_X = -50.0
    internal const val RESTAURANT_Y = 72.0
    internal const val RESTAURANT_Z = 51.0
    internal const val RESTAURANT_RADIUS = 16.0

    internal enum class Menu(
        val id: String,
        val title: String,
        val command: String,
    ) {
        FOOD("food", "Заказ за столиком", "rcbreweryorder"),
        DRINKS("drinks", "Напитки у костра", "rcdiningorder"),
        COURTYARD("courtyard", "Меню во дворе", "rcdiningorder"),
        RESTAURANT("restaurant", "Меню ресторана", "rcdiningorder"),
        ;

        companion object {
            fun parse(value: String?): Menu? = entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
        }
    }

    private val titleColor = TextColor.color(0x92BED8)
    private val bodyColor = TextColor.color(0xE8DFD2)
    private val eggColor = TextColor.color(0xF4D87A)
    private val fishColor = TextColor.color(0x92BED8)
    private val steakColor = TextColor.color(0xD98A75)
    private val drinkColor = TextColor.color(0xBCA8E8)
    private val coinColor = TextColor.color(0xFFFFFF)

    internal data class Dish(
        val id: String,
        val label: String,
        val choice: String,
        val price: Int,
        val color: TextColor,
        val tooltip: String,
    )

    internal val food =
        listOf(
            Dish("egg", "Яичница с травами", "egg", 300, eggColor, "Восстанавливает 5 сытости и 3 насыщения."),
            Dish("fish", "Запечённая рыба", "fish", 450, fishColor, "Восстанавливает 7 сытости и 5 насыщения."),
            Dish("steak", "Стейк с перцем", "steak", 650, steakColor, "Восстанавливает 9 сытости и 7 насыщения."),
        )

    internal val drinks =
        listOf(
            Dish("herbal_tea", "Пиво", "herbal_tea", 250, drinkColor, "Бутылка пива с обычной крепостью."),
            Dish("berry_kvass", "Красное вино", "berry_kvass", 400, fishColor, "Бутылка выдержанного красного вина."),
            Dish("spiced_mead", "Медовуха", "spiced_mead", 600, eggColor, "Бутылка крепкой медовухи."),
        )

    internal fun openOrder(player: Player, menu: Menu = Menu.FOOD) {
        if (!canOpen(player, menu)) return
        OriginMenus.beginDialogFlow(player)
        OriginMenus.openDialog(player, orderScreen(player, menu), reopen = { showOrder(player, menu) })
    }

    private fun showOrder(player: Player, menu: Menu) {
        if (canOpen(player, menu)) OriginMenus.openDialog(player, orderScreen(player, menu), reopen = { showOrder(player, menu) })
    }

    internal fun orderScreen(player: Player, menu: Menu = Menu.FOOD): PaperDialogScreen {
        val choices =
            when (menu) {
                Menu.FOOD -> food
                Menu.DRINKS -> drinks
                Menu.COURTYARD -> food + drinks
                Menu.RESTAURANT -> food + drinks.take(1)
            }
        val body =
            when (menu) {
                Menu.FOOD -> listOf("Луи подаст блюдо к вашему столику.", "После подачи нажмите на блюдо, чтобы съесть его.")
                Menu.DRINKS -> listOf("Перед вами нет стола — здесь подают напитки.", "После оплаты официант положит бутылку в свободный слот инвентаря.")
                Menu.COURTYARD -> listOf("Выберите блюдо или напиток.", "Официант принесёт заказ к вашему столику.")
                Menu.RESTAURANT -> listOf("Официант примет заказ и принесёт его к столу.", "Стоимость списывается, когда блюдо поставлено на стол.")
            }
        return PaperDialogScreen(
            id = "dining.${menu.id}.order",
            title = styled(menu.title, titleColor),
            body = body.map(::prose),
            buttons =
                choices.map { dish ->
                    PaperDialogButton(
                        id = PaperDialogActionId.of("${menu.id}_${dish.id}"),
                        label = priceLabel(dish),
                        tooltip = component(dish.tooltip),
                        width = 320,
                        closeDialogBeforeAction = true,
                        onClick = { context -> orderIfCurrent(context.player, menu, dish) },
                    )
                },
            columns = 1,
        )
    }

    private fun orderIfCurrent(player: Player, menu: Menu, dish: Dish) {
        if (!canOpen(player, menu)) return
        if (!OriginDiningModule.order(player, menu, dish)) {
            // Tests and non-Origin development servers retain the old command bridge.
            player.performCommand("${menu.command} ${dish.choice}")
        }
    }

    private fun canOpen(player: Player, menu: Menu): Boolean {
        OriginDiningModule.canOpen(player, menu)?.let { return it }
        if (!player.isOnline) return false
        val location = player.location
        val allowed =
            when (menu) {
                Menu.RESTAURANT -> within(location.x, location.y, location.z, RESTAURANT_X, RESTAURANT_Y, RESTAURANT_Z, RESTAURANT_RADIUS)
                Menu.FOOD, Menu.DRINKS, Menu.COURTYARD -> within(location.x, location.y, location.z, BREWERY_X, BREWERY_Y, BREWERY_Z, BREWERY_RADIUS)
            }
        if (location.world?.name != ORIGIN_WORLD || !allowed) {
            player.sendMessage(component("Это меню доступно только с занятого места в Origin."))
            return false
        }
        return true
    }

    private fun within(x: Double, y: Double, z: Double, cx: Double, cy: Double, cz: Double, radius: Double): Boolean {
        val dx = x - cx
        val dy = y - cy
        val dz = z - cz
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    private fun prose(text: String): PaperDialogBody = PaperDialogBody(component(text), width = 320)

    private fun styled(text: String, color: TextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun priceLabel(dish: Dish): Component =
        styled("${dish.label} · ${dish.price} ", dish.color)
            .append(
                Component.text("💰", coinColor)
                    .decoration(TextDecoration.BOLD, false)
                    .decoration(TextDecoration.ITALIC, false),
            )

    private fun component(text: String): Component =
        Component.text(text, bodyColor).decoration(TextDecoration.ITALIC, false)
}
