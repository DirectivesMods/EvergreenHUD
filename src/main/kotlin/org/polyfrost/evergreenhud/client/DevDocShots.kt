package org.polyfrost.evergreenhud.client

//? if = 1.8.9 {
/*import net.minecraft.client.Screenshot
import net.minecraft.world.WorldSettings
import net.minecraft.world.gen.WorldGeneratorType
import org.polyfrost.evergreenhud.client.hud.ScriptHud
import org.polyfrost.evergreenhud.client.script.ScriptPresets
import org.polyfrost.oneconfig.api.event.v1.eventHandler
import org.polyfrost.oneconfig.api.event.v1.events.KeyInputEvent
import org.polyfrost.oneconfig.api.event.v1.events.TickEvent
import org.polyfrost.oneconfig.api.hud.v1.Font
import org.polyfrost.oneconfig.api.hud.v1.Hud
import org.polyfrost.oneconfig.api.hud.v1.HudManager
import org.polyfrost.oneconfig.utils.v1.dsl.mc
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

// TEMPORARY: F7 saves every HUD on screen as its own transparent PNG in screenshots/huds, for the docs.
// The HUDs are captured over a black and then a white backdrop, and the two are compared to recover transparency.
object DevHudScreenshots {
    private val LOGGER = LoggerFactory.getLogger("EvergreenHUD/HudScreenshots")
    private const val KEY_F7 = 296
    private const val MARGIN = 2f
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    @JvmStatic
    var backdrop: Int? = null
        private set

    var busy = false
        private set

    private var step = 0
    private var names: Map<Hud, String> = emptyMap()
    private var onDone: (() -> Unit)? = null

    fun initialize() {
        eventHandler { (key, _, state): KeyInputEvent ->
            if (state == 1 && key == KEY_F7 && mc.screen == null) capture(null)
        }
        eventHandler { _: TickEvent.End -> tick() }
    }

    /** Captures [named] HUDs under those names, or every HUD on screen when null */
    fun capture(named: Map<Hud, String>?, done: (() -> Unit)? = null) {
        if (busy) return
        busy = true
        names = named ?: visibleHuds()
        onDone = done
        step = 0
        backdrop = BLACK
    }

    private fun visibleHuds(): Map<Hud, String> {
        val used = HashMap<String, Int>()
        return HudManager.activeInstances.filter { !it.hidden && it.isVisible.value }.associateWith { hud ->
            val base = hud.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val n = used.merge(base, 1, Int::plus)!!
            if (n == 1) base else "$base-$n"
        }
    }

    private fun take(name: String) = Screenshot.take(mc.gameDir, ".hud-$name.png", mc.width, mc.height, mc.renderTarget)

    private fun tick() {
        if (!busy) return
        when (step++) {
            0 -> {}
            1 -> {
                take("black")
                backdrop = WHITE
            }
            2 -> {
                take("white")
                backdrop = BLACK
            }
            3 -> {
                take("black2")
                backdrop = null
                try {
                    save()
                } catch (e: Exception) {
                    LOGGER.error("Couldn't save HUD screenshots", e)
                }
                busy = false
                onDone?.invoke()
            }
        }
    }

    private fun save() {
        val dir = File(mc.gameDir, "screenshots")
        val files = listOf("black", "white", "black2").map { File(dir, ".hud-$it.png") }
        val (black, white, black2) = files.map { ImageIO.read(it) }
        files.forEach { it.delete() }

        val out = File(dir, "huds").apply { mkdirs() }
        val scale = mc.width / HudManager.guiScreenWidth
        for ((hud, name) in names) {
            val x0 = ((hud.x - MARGIN) * scale).roundToInt().coerceIn(0, black.width)
            val y0 = ((hud.y - MARGIN) * scale).roundToInt().coerceIn(0, black.height)
            val x1 = ((hud.x + hud.scaledWidth + MARGIN) * scale).roundToInt().coerceIn(x0, black.width)
            val y1 = ((hud.y + hud.scaledHeight + MARGIN) * scale).roundToInt().coerceIn(y0, black.height)
            val w = x1 - x0
            val h = y1 - y0
            if (w <= 0 || h <= 0) continue

            val b = black.getRGB(x0, y0, w, h, null, 0, w)
            val b2 = black2.getRGB(x0, y0, w, h, null, 0, w)
            val wh = white.getRGB(x0, y0, w, h, null, 0, w)
            if (!b.contentEquals(b2)) LOGGER.warn("{} changed while it was being captured, so it may have faint edges", name)

            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            for (i in b.indices) {
                val diff = (0..2).sumOf { c -> maxOf(0, (wh[i] shr (c * 8) and 0xFF) - (b[i] shr (c * 8) and 0xFF)) } / 3.0
                val alpha = (1.0 - diff / 255.0).coerceIn(0.0, 1.0)
                image.setRGB(i % w, i / w, if (alpha <= 0.004) 0 else {
                    var argb = (alpha * 255).roundToInt() shl 24
                    for (c in 0..2) argb = argb or ((((b[i] shr (c * 8)) and 0xFF) / alpha).roundToInt().coerceAtMost(255) shl (c * 8))
                    argb
                })
            }
            ImageIO.write(image, "png", File(out, "$name.png"))
        }
        LOGGER.info("Saved {} HUD screenshots to {}", names.size, out)
    }
}

// TEMPORARY: places each documented example and captures it, only when EVERGREENHUD_DOC_SHOTS points at the examples
object DevDocShots {
    private val LOGGER = LoggerFactory.getLogger("EvergreenHUD/DocShots")

    private class Example(val name: String, val options: Map<String, String>, val script: String)

    private val examples = ArrayList<Example>()
    private var ticks = 0
    private var stage = 0
    private var wait = 0
    private var index = 0
    private val added = ArrayList<Hud>()

    fun initialize() {
        val path = System.getenv("EVERGREENHUD_DOC_SHOTS") ?: return
        var name: String? = null
        var options = emptyMap<String, String>()
        val body = StringBuilder()
        fun flush() {
            val n = name ?: return
            val text = body.toString().trimEnd('\n')
            val script = if (text.startsWith("@preset ")) ScriptPresets.ALL[text.removePrefix("@preset ").trim().toInt()].source else text
            examples.add(Example(n, options, script))
        }
        for (line in File(path).readLines()) {
            if (line.startsWith("=== ")) {
                flush()
                val header = line.removePrefix("=== ").split("|").map { it.trim() }
                name = header[0]
                options = header.drop(1).flatMap { it.split(",") }.filter { "=" in it }
                    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
                body.setLength(0)
            } else body.append(line).append('\n')
        }
        flush()
        LOGGER.info("loaded {} doc examples", examples.size)

        eventHandler { _: TickEvent.End ->
            try {
                tick()
            } catch (e: Throwable) {
                LOGGER.error("doc shots failed", e)
                stage = 99
            }
        }
    }

    private fun tick() {
        ticks++
        if (wait > 0) {
            wait--
            return
        }
        when (stage) {
            0 -> if (ticks > 60 && mc.level == null) {
                mc.startGame("docs_shots", "docs_shots", WorldSettings(0L, WorldSettings.GameMode.SURVIVAL, false, false, WorldGeneratorType.FLAT).enableCommands())
                stage = 1
            }
            1 -> if (mc.player != null) {
                stage = 2
                wait = 60
            }
            2 -> {
                for (c in listOf(
                    "/gamerule sendCommandFeedback false", "/gamerule doDaylightCycle false", "/difficulty 1", "/time set 6000",
                    "/weather clear 99999", "/achievement give achievement.openInventory", "/tp @p -38.4712 4 0.5238 -90 0",
                    "/effect @p 1 600 1 true", "/effect @p 5 25 0 true", "/effect @p 8 90 1 true", "/effect @p 18 12 0 true",
                    "/replaceitem entity @p slot.armor.head diamond_helmet", "/replaceitem entity @p slot.armor.chest iron_chestplate 1 150",
                    "/replaceitem entity @p slot.armor.legs golden_leggings 1 50", "/replaceitem entity @p slot.armor.feet diamond_boots",
                    "/replaceitem entity @p slot.hotbar.0 diamond_sword", "/give @p emerald 12", "/give @p iron_ingot 40",
                    "/give @p gold_ingot 9", "/give @p diamond 3", "/effect @p 7 1 0",
                )) mc.player.sendChat(c)
                stage = 3
                wait = 120
            }
            3 -> {
                mc.openScreen(null)
                for (hud in HudManager.activeInstances.filterIsInstance<ScriptHud>()) HudManager.removeHud(hud, delete = true)
                stage = 4
            }
            4 -> {
                clear()
                if (index >= examples.size) {
                    stage = 6
                    return
                }
                val example = examples[index]
                val hud = add(example.script, 6f, 40f)
                if (example.options["font"] == "poppins") hud.font = Font.Poppins
                if (example.options["background"] == "off") hud.showBackground = false
                stage = 5
                wait = 12
            }
            5 -> {
                stage = 50
                DevHudScreenshots.capture(mapOf(added[0] to examples[index].name)) {
                    index++
                    stage = 4
                }
            }
            6 -> {
                add(examples.first { it.name == "your-coords" }.script, 6f, 6f).showBackground = false
                add(ScriptPresets.ALL[1].source, 6f, 52f)
                add(ScriptPresets.ALL[3].source, 300f, 6f)
                add(ScriptPresets.ALL[4].source, 300f, 70f)
                add(ScriptPresets.ALL[6].source, 120f, 6f)
                stage = 7
                wait = 10
            }
            7 -> {
                val width = HudManager.guiScreenWidth
                added[2].setAbsolutePosition(width - added[2].scaledWidth - 6f, 6f)
                added[3].setAbsolutePosition(width - added[3].scaledWidth - 6f, 12f + added[2].scaledHeight)
                added[4].setAbsolutePosition((width - added[4].scaledWidth) / 2f, 6f)
                stage = 8
                wait = 10
            }
            8 -> {
                Screenshot.take(mc.gameDir, "hero.png", mc.width, mc.height, mc.renderTarget)
                clear()
                LOGGER.info("doc shots done")
                stage = 99
            }
        }
    }

    private fun clear() {
        for (hud in added) HudManager.removeHud(hud, delete = true)
        added.clear()
    }

    private fun add(source: String, x: Float, y: Float): ScriptHud {
        val instance = HudManager.getProvider(ScriptHud::class.java)!!.make() as ScriptHud
        HudManager.markProviderKnown(instance)
        instance.script = source
        instance.setAbsolutePosition(x, y)
        HudManager.activeInstances.add(instance)
        HudManager.instancesRevision.intValue++
        instance.setup()
        added.add(instance)
        return instance
    }
}
*///?}
