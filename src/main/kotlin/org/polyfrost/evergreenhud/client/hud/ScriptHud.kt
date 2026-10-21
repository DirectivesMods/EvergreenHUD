package org.polyfrost.evergreenhud.client.hud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import org.polyfrost.evergreenhud.client.script.CompiledScript
import org.polyfrost.evergreenhud.client.script.ScriptBindings
import org.polyfrost.evergreenhud.client.script.ScriptContent
import org.polyfrost.evergreenhud.client.script.ScriptException
import org.polyfrost.evergreenhud.client.script.ScriptMemory
import org.polyfrost.evergreenhud.client.script.ScriptOutput
import org.polyfrost.evergreenhud.client.script.ScriptPresets
import org.polyfrost.evergreenhud.client.script.ScriptSegment
import org.polyfrost.evergreenhud.client.script.ScriptStyle
import org.polyfrost.oneconfig.api.config.v1.annotations.Button
import org.polyfrost.oneconfig.api.config.v1.annotations.Dropdown
import org.polyfrost.oneconfig.api.config.v1.annotations.Info
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider
import org.polyfrost.oneconfig.api.config.v1.annotations.Text
import org.polyfrost.oneconfig.api.event.v1.eventHandler
import org.polyfrost.oneconfig.api.event.v1.events.TickEvent
import org.polyfrost.oneconfig.api.event.v1.invoke.EventHandler
import org.polyfrost.oneconfig.api.hud.v1.Hud
import org.polyfrost.oneconfig.api.hud.v1.HudManager
import org.polyfrost.oneconfig.api.platform.v1.Platform
import org.polyfrost.oneconfig.utils.v1.MHUtils.setAccessible
import org.slf4j.LoggerFactory

private const val PLACEHOLDER_COLOR = 0xFFAAAAAA.toInt()

class ScriptHud : Hud(
    id = "script.json",
    title = "Custom Script",
    category = Category.INFO,
) {
    private companion object {
        private val LOGGER = LoggerFactory.getLogger("EvergreenHUD/Custom Script")

        private val PLACEHOLDER = ScriptOutput(
            listOf(listOf(ScriptSegment.Text("Nothing to show right now", ScriptStyle(color = PLACEHOLDER_COLOR, italic = true))))
        )

        /** OneConfig keeps the HUD settings panel until this changes, so bump it to show a newly loaded preset */
        private val HUD_UI_EPOCHS = runCatching {
            Class.forName("org.polyfrost.oneconfig.internal.ui.hud.HudSettingsResetKt")
                .getDeclaredField("hudUiEpochs").apply { setAccessible() }
        }.getOrNull()
    }

    override val searchTags: List<String>
        get() = listOf("script", "custom", "template", "placeholder", "variables", "format", "coords", "potions")

    @Text(
        title = "Script",
        description = "What the HUD shows. See Help below for everything you can write.",
        multiline = true,
    )
    var script = ScriptPresets.DEFAULT

    @Dropdown(
        title = "Preset",
        options = ["Coordinates", "Potion Effects", "Player Stats", "Armor", "Bed Wars Resources", "PvP", "Info"],
        subcategory = "Presets",
    )
    var preset = 0

    @Slider(title = "Line Spacing", min = 0F, max = 10F, step = 0.5F)
    var lineSpacing = 2f

    @Info(title = "Writing Scripts", description = ScriptPresets.HELP, subcategory = "Help")
    private var help = ""

    @Info(title = "Everything You Can Use", description = ScriptPresets.VARIABLES, subcategory = "Help")
    private var reference = ""

    private var compiled: CompiledScript? = null
    private var compiledSource: String? = null
    private var compileError: ScriptOutput? = null
    private var lastLoggedError: String? = null
    private var memory = ScriptMemory()

    private var outputState = mutableStateOf(ScriptOutput.EMPTY)
    private var spacingState = mutableFloatStateOf(lineSpacing)
    private var guiScaleState = mutableFloatStateOf(1f)

    private var tickHandler: EventHandler<*>? = null

    init {
        padLeft = 4f
        padRight = 4f
        padTop = 4f
        padBottom = 4f
        textAlign = 0
    }

    @Button(title = "Load Preset", text = "Load", description = "Replaces the script with the chosen preset.", subcategory = "Presets")
    fun loadPreset() {
        val source = ScriptPresets.ALL.getOrNull(preset)?.source ?: return
        getProperty("script").setAs(source)
        refreshSettingsPanel()
        save()
    }

    override fun defaultPosition(): Pair<Float, Float> = 10f to 10f

    override fun setup() {
        super.setup()
        if (isReal) {
            updateWhenChanged("script")
            updateWhenChanged("lineSpacing")
            tickHandler = eventHandler { _: TickEvent.End -> updateAndRecalculate() }
        }
        update()
        reseedStaticSizeIfNeeded()
        captureStaticSizeDefaults()
    }

    /** Updates once a tick, or every frame while smooth() is counting towards a new number */
    override fun updateFrequency(): Long = if (isReal && !memory.animating) Long.MAX_VALUE else -1L

    override fun remove() {
        tickHandler?.unregister()
        tickHandler = null
    }

    override fun canMergeBackground(): Boolean = true

    override fun minimumSize(): Pair<Float, Float> = (padLeft + padRight + 2f) to (padTop + padBottom + 2f)

    override val alwaysRedraw: Boolean
        get() = super.alwaysRedraw || outputState.value.usesChroma

    override fun shouldShow(): Boolean = outputState.value.lines.isNotEmpty()

    /** Whether the script refers to [name], such as `ping` */
    fun uses(name: String): Boolean = compiled?.names?.contains(name) == true

    override fun update(): Boolean {
        spacingState.floatValue = lineSpacing
        guiScaleState.floatValue = Platform.compatibility().options().guiScale
        val editing = HudManager.isEditing || !isReal
        val next = evaluate(editing).let { if (editing && it.lines.isEmpty()) PLACEHOLDER else it }
        if (next == outputState.value) return false
        outputState.value = next
        return true
    }

    private fun evaluate(editing: Boolean): ScriptOutput {
        val script = compile() ?: return compileError ?: ScriptOutput.EMPTY
        return try {
            ScriptBindings.library.run(script, memory, ScriptBindings.globals(editing, memory))
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if (message != lastLoggedError) {
                lastLoggedError = message
                LOGGER.warn("Custom script failed", e)
            }
            ScriptOutput.error("Script error: $message")
        }
    }

    private fun compile(): CompiledScript? {
        val source = script
        if (source == compiledSource) return compiled
        compiledSource = source
        compiled = null
        compileError = null
        try {
            compiled = ScriptBindings.library.compile(source)
        } catch (e: ScriptException) {
            val message = e.message ?: "Invalid script"
            compileError = ScriptOutput.error(if (e.line > 0) "Line ${e.line}: $message" else message)
        }
        return compiled
    }

    private fun refreshSettingsPanel() {
        try {
            @Suppress("UNCHECKED_CAST")
            val epochs = HUD_UI_EPOCHS?.get(null) as? MutableMap<Hud, Int> ?: return
            epochs[this] = (epochs[this] ?: 0) + 1
        } catch (e: Exception) {
            LOGGER.debug("Couldn't refresh the HUD settings panel", e)
        }
    }

    @Composable
    override fun Content() = ScriptContent(outputState.value, spacingState.floatValue, guiScaleState.floatValue)

    override fun clone(): Hud = (super.clone() as ScriptHud).also {
        it.compiled = null
        it.compiledSource = null
        it.compileError = null
        it.lastLoggedError = null
        it.memory = ScriptMemory()
        it.outputState = mutableStateOf(ScriptOutput.EMPTY)
        it.spacingState = mutableFloatStateOf(lineSpacing)
        it.guiScaleState = mutableFloatStateOf(guiScaleState.floatValue)
        it.tickHandler = null
    }
}
