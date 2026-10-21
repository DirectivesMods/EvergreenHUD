package org.polyfrost.evergreenhud.client.hud.potion

//? if > 1.8.9 {
import net.minecraft.core.registries.BuiltInRegistries
//? if < 1.21.11
//import net.minecraft.resources.ResourceLocation
//? if >= 1.21.11
import net.minecraft.resources.Identifier as ResourceLocation
import net.minecraft.server.packs.resources.ReloadableResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.world.effect.MobEffectInstance
import org.polyfrost.oneconfig.api.event.v1.eventHandler
import org.polyfrost.oneconfig.api.event.v1.events.InitializationEvent
//?} else {
/*import net.minecraft.entity.living.effect.StatusEffect
import net.minecraft.entity.living.effect.StatusEffectInstance as MobEffectInstance
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers
import net.ornithemc.osl.resource.loader.api.client.ClientResourceLoaderEvents
import net.ornithemc.osl.resource.loader.api.resource.manager.ResourceManager
import org.jetbrains.skia.Bitmap
import java.util.function.BiConsumer
import kotlin.math.roundToInt
*///?}
import org.jetbrains.skia.Image
import org.polyfrost.compose.render.ImageLoader
import org.polyfrost.oneconfig.utils.v1.dsl.mc
import org.slf4j.LoggerFactory

/** Potion effect icons from the active resource packs, shared by every HUD that draws them */
object EffectIcons {
    private val LOGGER = LoggerFactory.getLogger("EvergreenHUD/Potion Effects")

    //? if = 1.8.9
    //private const val ICON = 18f

    private class Example(val id: String, val ticks: Int, val amplifier: Int, val ambient: Boolean = false)

    private val EXAMPLES = listOf(
        Example("speed", 1200, 1),
        Example("strength", 140, 0),
        Example("slowness", 840, 0),
        Example("glowing", 500, 0),
        Example("regeneration", 60, 1, ambient = true),
    )

    /** Effects to show in the HUD editor when the player has none */
    val EXAMPLE_EFFECTS: List<MobEffectInstance> by lazy {
        EXAMPLES.mapNotNull { example ->
            //? if > 1.8.9 {
            val registry = BuiltInRegistries.MOB_EFFECT
            val effect = registry.firstOrNull { registry.getKey(it)?.path == example.id } ?: return@mapNotNull null
            MobEffectInstance(registry.wrapAsHolder(effect), example.ticks, example.amplifier, example.ambient, true)
            //?} else {
            /*val effect = StatusEffect.get(example.id) ?: return@mapNotNull null
            MobEffectInstance(effect.id, example.ticks, example.amplifier, example.ambient, true)
            *///?}
        }
    }

    //~ if = 1.8.9 'ResourceLocation' -> 'Int'
    private val iconCache = HashMap<ResourceLocation, Image?>()

    fun initialize() {
        //? if > 1.8.9 {
        eventHandler { _: InitializationEvent ->
            val manager = mc.resourceManager as? ReloadableResourceManager
            if (manager != null) manager.registerReloadListener(ResourceManagerReloadListener { clearIcons() })
            else LOGGER.warn("Resource manager is not reloadable, potion effect icons will not follow resource pack changes")
        }
        //?} else
        //ClientResourceLoaderEvents.END_RESOURCE_RELOAD.register(BiConsumer { _, _ -> clearIcons() })
    }

    private fun clearIcons() {
        iconCache.clear()
        //? if = 1.8.9
        //iconsLoaded = false
    }

    //? if > 1.8.9 {
    fun iconFor(id: ResourceLocation): Image? {
        if (iconCache.containsKey(id)) return iconCache[id]
        val path = ResourceLocation.fromNamespaceAndPath(id.namespace, "textures/mob_effect/${id.path}.png")
        val icon = try {
            mc.resourceManager.getResource(path).orElse(null)
                ?.open()?.use { ImageLoader.fromBytes(it.readAllBytes()) }
        } catch (e: Exception) {
            LOGGER.warn("Failed to load the icon for effect {}", id, e)
            null
        }
        iconCache[id] = icon
        return icon
    }
    //?} else {
    /*private var iconsLoaded = false

    private fun loadIcons() {
        val path = NamespacedIdentifiers.from("minecraft", "textures/gui/container/inventory.png")
        val atlas = ResourceManager.client().getResource(path).orElse(null)?.open()?.use { ImageLoader.fromBytes(it.readBytes()) }
        if (atlas == null) {
            LOGGER.warn("Failed to read the potion effect icon atlas {}", path)
            return
        }
        atlas.use {
            val scale = atlas.width / 256f
            val size = (ICON * scale).roundToInt()
            Bitmap().use { bitmap ->
                bitmap.allocN32Pixels(size, size)
                for (effect in StatusEffect.BY_ID) {
                    if (effect == null || !effect.hasIcon()) continue
                    val x = ((effect.iconIndex % 8 * ICON) * scale).roundToInt()
                    val y = ((198 + effect.iconIndex / 8 * ICON) * scale).roundToInt()
                    bitmap.erase(0)
                    if (atlas.readPixels(bitmap, x, y)) iconCache[effect.id] = Image.makeFromBitmap(bitmap)
                }
            }
        }
    }

    fun iconFor(effect: StatusEffect): Image? {
        if (!iconsLoaded) {
            iconsLoaded = true
            try {
                loadIcons()
            } catch (e: Exception) {
                LOGGER.warn("Failed to load potion effect icons", e)
            }
        }
        return iconCache[effect.id]
    }
    *///?}
}
