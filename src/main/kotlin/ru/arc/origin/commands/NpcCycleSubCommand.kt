package ru.arc.origin.commands

import org.bukkit.command.CommandSender
import ru.arc.origin.scene.OriginAmbientScenesModule
import ru.arc.origin.scene.OriginSceneCycleKey
import ru.arc.origin.scene.OriginSceneStartResult
import ru.arc.util.TextUtils

/** Administrative trigger for one configured Origin ambient NPC cycle. */
object NpcCycleSubCommand : OriginCommandHandler {
    const val USAGE = "/arc npccycle <list|status [сцена] [цикл]|цикл|сцена цикл>"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val keys = OriginAmbientScenesModule.cycleKeys()
        if (args.firstOrNull()?.equals("status", ignoreCase = true) == true) {
            if (args.size > 3) {
                sendOriginUsage(sender, USAGE)
                return true
            }
            val statuses = OriginAmbientScenesModule.status().filter {
                (args.size < 2 || it.sceneId.equals(args[1], ignoreCase = true)) &&
                    (args.size < 3 || it.cycleId.equals(args[2], ignoreCase = true))
            }
            if (statuses.isEmpty()) sender.sendMessage(TextUtils.mm("<yellow>Нет доступных сцен по этому запросу.", true))
            statuses.forEach { sender.sendMessage(it.debugLine()) }
            return true
        }
        if (args.isEmpty() || args.singleOrNull()?.equals("list", ignoreCase = true) == true) {
            if (keys.isEmpty()) {
                sender.sendMessage(TextUtils.mm("<red>Движок NPC-сцен сейчас не готов.", true))
            } else {
                sender.sendMessage(TextUtils.mm("<gold>Циклы NPC:<gray> " + keys.joinToString(", ") { "${it.sceneId}/${it.cycleId}" }, true))
            }
            return true
        }

        val key = resolveOriginSceneCycle(keys, args)
        if (key == null) {
            sender.sendMessage(TextUtils.mm("<red>Цикл не найден или имя неоднозначно.", true))
            sendOriginUsage(sender, USAGE)
            return true
        }

        when (val result = OriginAmbientScenesModule.startCycle(key.sceneId, key.cycleId)) {
            is OriginSceneStartResult.Started -> sender.sendMessage(
                TextUtils.mm("<green>Запущен цикл <white>${result.key.sceneId}/${result.key.cycleId}<green>.", true),
            )
            is OriginSceneStartResult.Busy -> sender.sendMessage(
                TextUtils.mm("<yellow>Цикл занят: <white>${result.reason}<yellow>.", true),
            )
            is OriginSceneStartResult.Unknown -> sender.sendMessage(
                TextUtils.mm("<red>Неизвестный цикл <white>${result.sceneId}/${result.cycleId}<red>.", true),
            )
            is OriginSceneStartResult.Unavailable -> sender.sendMessage(
                TextUtils.mm("<red>Цикл недоступен: <white>${result.reason}<red>.", true),
            )
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> {
        val keys = OriginAmbientScenesModule.cycleKeys()
        if (args.firstOrNull()?.equals("status", ignoreCase = true) == true && args.size > 1) {
            return when (args.size) {
                2 -> keys.map(OriginSceneCycleKey::sceneId).distinct().originTabComplete(args[1])
                3 -> keys.filter { it.sceneId.equals(args[1], ignoreCase = true) }
                    .map(OriginSceneCycleKey::cycleId).originTabComplete(args[2])
                else -> emptyList()
            }
        }
        return when (args.size) {
            1 -> (listOf("list", "status") + legacyAliases.keys + keys.map(OriginSceneCycleKey::sceneId) + keys.map(OriginSceneCycleKey::cycleId))
                .distinct()
                .originTabComplete(args[0])
            2 -> keys.filter { it.sceneId.equals(args[0], ignoreCase = true) }
                .map(OriginSceneCycleKey::cycleId)
                .originTabComplete(args[1])
            else -> emptyList()
        }
    }
}

internal val legacyAliases = mapOf(
    "forge-master" to OriginSceneCycleKey("forge", "master-anvil"),
    "forge-mila" to OriginSceneCycleKey("forge", "ledger-orders"),
    "forge-luka" to OriginSceneCycleKey("forge", "apprentice-engraving"),
    "forge-savva" to OriginSceneCycleKey("forge", "ore-inspection"),
    "forge-bran" to OriginSceneCycleKey("forge", "blade-practice"),
    "forge-doran" to OriginSceneCycleKey("forge", "hammer-rhythm"),
    "forge-yar" to OriginSceneCycleKey("forge", "furnace-check"),
)

internal fun resolveOriginSceneCycle(keys: List<OriginSceneCycleKey>, args: Array<String>): OriginSceneCycleKey? {
    if (args.size == 1) {
        legacyAliases[args[0].lowercase()]?.let { alias -> return alias.takeIf(keys::contains) }
        return keys.singleOrNull { it.cycleId.equals(args[0], ignoreCase = true) }
    }
    if (args.size == 2) {
        return keys.singleOrNull {
            it.sceneId.equals(args[0], ignoreCase = true) && it.cycleId.equals(args[1], ignoreCase = true)
        }
    }
    return null
}
