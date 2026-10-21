package org.polyfrost.evergreenhud.client.script

//? if > 1.8.9 {
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EquipmentSlot
import kotlin.jvm.optionals.getOrNull
//?} else {
/*import net.minecraft.client.resource.language.I18n
import net.minecraft.entity.living.effect.StatusEffect
import net.minecraft.entity.living.effect.StatusEffectInstance as MobEffectInstance
import org.polyfrost.evergreenhud.client.hud.potion.EffectCatalog
*///?}
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.jetbrains.skia.Image
import org.polyfrost.evergreenhud.client.EntityCounterEvent
import org.polyfrost.evergreenhud.client.hud.PlayerHeadTexture
import org.polyfrost.evergreenhud.client.hud.item.itemId
import org.polyfrost.evergreenhud.client.hud.item.resolveItem
import org.polyfrost.evergreenhud.client.hud.potion.EffectIcons
import org.polyfrost.evergreenhud.client.utils.CombatTracker
import org.polyfrost.evergreenhud.client.utils.Facing
import org.polyfrost.evergreenhud.client.utils.FrameTimeHelper
import org.polyfrost.evergreenhud.client.utils.SaturationTracker
import org.polyfrost.oneconfig.utils.v1.dsl.mc
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TICKS_PER_SECOND = 20.0
private const val TICKS_PER_DAY = 24000L
private const val BYTES_PER_MB = 1024L * 1024L
private const val MB_PER_GB = 1024.0
private const val EXAMPLE_COUNT = 64
private const val NO_SERVER = "127.0.0.1"
private const val EXAMPLE_SERVER = "polyfrost.org"

// how often each value refreshes, the same as the default of the element that shows it
private const val FPS_SECONDS = 1.0
//? if > 1.8.9
private const val PING_SECONDS = 0.5
//? if = 1.8.9
//private const val PING_SECONDS = 1.0
private const val CPS_SECONDS = 0.1
private const val MEMORY_SECONDS = 0.5
private const val TIME_SECONDS = 0.5
private const val DAY_SECONDS = 1.0
private const val SPEED_NANOS = 50_000_000L
private const val SPEED_SMOOTHING = 0.2

/** The Position, Speed, Reach and Saturation elements show two decimals, keeping trailing zeros */
private fun twoDecimals(value: Double) = Value.Num(value, 2, fixed = true)

/** Where the Speed element's smoothing has got to */
private class SpeedState(var value: Double = 0.0, var updated: Long = Long.MIN_VALUE)

private val CARDINALS = arrayOf(Facing.NORTH, Facing.EAST, Facing.SOUTH, Facing.WEST)

/** A picture, such as a player head or a potion effect icon */
data class ImageIconSource(val image: Image) : IconSource

/** An item as it looks in the inventory, compared by what it shows so an unchanged item doesn't redraw */
class ItemIconSource private constructor(val stack: ItemStack?, val id: String, private val key: Any) : IconSource {
    override fun equals(other: Any?): Boolean = other is ItemIconSource && other.key == key

    override fun hashCode(): Int = key.hashCode()

    companion object {
        fun of(stack: ItemStack): ItemIconSource {
            val id = itemId(stack.item)
            return ItemIconSource(stack, id, listOf(id, stack.count, stack.damageValue))
        }

        fun of(id: String) = ItemIconSource(null, id, id)
    }
}

/** Everything a script can read from the game */
object ScriptBindings {
    private class Context(val player: LocalPlayer, val editing: Boolean, val memory: ScriptMemory) {
        val camera get() = mc.cameraEntity ?: player

        /** Helmet, chestplate, leggings, boots, main hand and off hand */
        val equipment: List<ItemStack?> by lazy {
            //? if > 1.8.9 {
            val slots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)
            val worn = slots.map { slot -> player.getItemBySlot(slot).takeUnless { it.isEmpty } }
            //?} else
            //val worn = listOf(player.getArmor(3), player.getArmor(2), player.getArmor(1), player.getArmor(0), player.mainHandItem, null)
            if (editing && worn.all { it == null }) exampleEquipment else worn
        }

        val effects: Value by lazy {
            //? if > 1.8.9 {
            val active = player.activeEffects.filter { it.showIcon() }
            //?} else
            //val active = player.activeEffects.filter { StatusEffect.BY_ID.getOrNull(it.id)?.hasIcon() == true }
            Value.Items(active.ifEmpty { if (editing) EffectIcons.EXAMPLE_EFFECTS else emptyList() }.map(::effect))
        }
    }

    private val live: Map<String, Context.() -> Value> = buildMap {
        fun v(name: String, get: Context.() -> Value) {
            put(name, get)
        }

        v("x") { twoDecimals(player.x) }
        v("y") { twoDecimals(player.y) }
        v("z") { twoDecimals(player.z) }
        v("block") { block(player.x, player.y, player.z) }
        v("chunk") { chunk(player.x, player.z) }
        v("yaw") { twoDecimals(Facing.wrapDegrees(camera.yRot).toDouble()) }
        v("pitch") { twoDecimals(camera.xRot.toDouble()) }
        v("facing") { facing(camera.yRot) }
        v("cardinal") { cardinal(camera.yRot) }
        v("dimension") { Value.Text(dimension(player)) }
        v("biome") { Value.Text(biome(player)) }
        v("speed") {
            val state = memory.remember("speed") { SpeedState() }
            val now = memory.clock()
            if (state.updated == Long.MIN_VALUE || now - state.updated >= SPEED_NANOS) {
                val dx = player.x - player.xo
                val dy = player.y - player.yo
                val dz = player.z - player.zo
                state.value += (sqrt(dx * dx + dy * dy + dz * dz) * TICKS_PER_SECOND - state.value) * SPEED_SMOOTHING
                state.updated = now
            }
            twoDecimals(state.value)
        }

        //? if > 1.8.9 {
        v("name") { Value.Text(player.name.string) }
        v("absorption") { Value.Num(player.absorptionAmount.toDouble()) }
        v("armor") { Value.Num(player.armorValue.toDouble()) }
        v("level") { Value.Num(player.experienceLevel.toDouble()) }
        v("xp") { Value.Num(player.experienceProgress.toDouble()) }
        v("sneaking") { Value.of(player.isShiftKeyDown) }
        v("on_ground") { Value.of(player.onGround()) }
        //?} else {
        /*v("name") { Value.Text(player.getName()) }
        v("absorption") { Value.Num(player.absorption.toDouble()) }
        v("armor") { Value.Num(player.armorProtection.toDouble()) }
        v("level") { Value.Num(player.xpLevel.toDouble()) }
        v("xp") { Value.Num(player.xpProgress.toDouble()) }
        v("sneaking") { Value.of(player.isSneaking) }
        v("on_ground") { Value.of(player.onGround) }
        *///?}
        v("health") { Value.Num(player.health.toDouble()) }
        v("max_health") { Value.Num(player.maxHealth.toDouble()) }
        v("food") { Value.Num(player.foodData.foodLevel.toDouble()) }
        v("saturation") { twoDecimals(SaturationTracker.saturation.toDouble()) }
        v("sprinting") { Value.of(player.isSprinting) }
        v("flying") { Value.of(player.abilities.flying) }
        v("in_water") { Value.of(player.isInWater) }
        v("burning") { Value.of(player.isOnFire) }
        //? if > 1.8.9 && < 1.21.5
        //v("slot") { Value.Num(player.inventory.selected + 1.0) }
        //? if <= 1.8.9 || >= 1.21.5
        v("slot") { Value.Num(player.inventory.selectedSlot + 1.0) }

        v("helmet") { item(equipment[0]) }
        v("chestplate") { item(equipment[1]) }
        v("leggings") { item(equipment[2]) }
        v("boots") { item(equipment[3]) }
        v("held") { item(equipment[4]) }
        v("offhand") { item(equipment[5]) }
        v("armor_items") { Value.Items(equipment.take(4).map(::item).filter { it != Value.Null }) }
        v("effects") { effects }
        v("head") { head() }

        v("time") { memory.hold("time", TIME_SECONDS) { Value.Text(clock(dayTime())) } }
        v("day") { memory.hold("day", DAY_SECONDS) { Value.Num((dayTime() / TICKS_PER_DAY).toDouble()) } }
        v("weather") { Value.Text(weather()) }

        v("fps") { fps(memory) }
        v("ping") { memory.hold("ping", PING_SECONDS) { ping(player)?.let { Value.Num(it.toDouble()) } ?: Value.Null } }
        v("server") { Value.Text(mc.currentServer?.ip ?: NO_SERVER) }
        v("singleplayer") { Value.of(mc.hasSingleplayerServer()) }
        v("memory") { memory.hold("memory", MEMORY_SECONDS, ::memoryUsage) }
        v("entities") { entities(EntityCounterEvent.rendered, EntityCounterEvent.total) }
        v("cps") { memory.hold("cps", CPS_SECONDS) { cps(CombatTracker.leftCps, CombatTracker.rightCps) } }
        v("combo") { Value.Num(CombatTracker.currentCombo.toDouble()) }
        v("reach") { reach(CombatTracker.lastReach) }
    }

    /** Stand-in values for when there is no world, such as in the HUD list on the title screen */
    private val preview: Map<String, (ScriptMemory) -> Value> by lazy {
        buildMap {
            fun v(name: String, get: (ScriptMemory) -> Value) {
                put(name, get)
            }

            val yaw = -90f
            v("x") { twoDecimals(-38.5) }
            v("y") { twoDecimals(72.0) }
            v("z") { twoDecimals(0.5) }
            v("block") { block(-38.5, 72.0, 0.5) }
            v("chunk") { chunk(-38.5, 0.5) }
            v("yaw") { twoDecimals(yaw.toDouble()) }
            v("pitch") { twoDecimals(0.0) }
            v("facing") { facing(yaw) }
            v("cardinal") { cardinal(yaw) }
            v("dimension") { Value.Text("overworld") }
            v("biome") { Value.Text("Plains") }
            v("speed") { twoDecimals(0.0) }
            v("name") { Value.Text("Player") }
            v("health") { Value.Num(20.0) }
            v("max_health") { Value.Num(20.0) }
            v("absorption") { Value.Num(0.0) }
            v("food") { Value.Num(20.0) }
            v("saturation") { twoDecimals(5.0) }
            v("armor") { Value.Num(20.0) }
            v("level") { Value.Num(30.0) }
            v("xp") { Value.Num(0.5) }
            v("on_ground") { Value.TRUE }
            v("slot") { Value.Num(1.0) }
            v("helmet") { item(exampleEquipment[0]) }
            v("chestplate") { item(exampleEquipment[1]) }
            v("leggings") { item(exampleEquipment[2]) }
            v("boots") { item(exampleEquipment[3]) }
            v("held") { item(exampleEquipment[4]) }
            v("armor_items") { Value.Items(exampleEquipment.take(4).map(::item)) }
            v("effects") { Value.Items(EffectIcons.EXAMPLE_EFFECTS.map(::effect)) }
            v("head") { head() }
            v("time") { Value.Text("12:00") }
            v("day") { Value.Num(1.0) }
            v("weather") { Value.Text("clear") }
            v("fps") { memory -> fps(memory) }
            v("ping") { Value.Num(42.0) }
            v("server") { Value.Text(EXAMPLE_SERVER) }
            v("memory") { memory -> memory.hold("memory", MEMORY_SECONDS, ::memoryUsage) }
            v("entities") { entities(42, 120) }
            v("cps") { cps(8, 3) }
            v("combo") { Value.Num(3.0) }
            v("reach") { reach(3.05) }
        }
    }

    private val exampleEquipment: List<ItemStack?> by lazy {
        listOf(
            ItemStack(Items.DIAMOND_HELMET),
            ItemStack(Items.DIAMOND_CHESTPLATE),
            ItemStack(Items.DIAMOND_LEGGINGS),
            ItemStack(Items.DIAMOND_BOOTS),
            ItemStack(Items.DIAMOND_SWORD),
            null,
        )
    }

    private val resolvedItems = HashMap<String, Item?>()

    private val functions = mapOf(
        "item" to ScriptFunction("item", 1, 1) { args -> itemType(args.text(0)) },
        "effect" to ScriptFunction("effect", 1, 1) { args ->
            val query = args.text(0).trim().lowercase().removePrefix("minecraft:")
            args.global("effects").asItems().firstOrNull { effect ->
                member(effect, "id").asText() == query || member(effect, "name").asText().equals(query, ignoreCase = true)
            } ?: Value.Null
        },
    )

    val library = ScriptLibrary(live.keys, functions)

    /**
     * Looks up script variables for this moment
     *
     * [editing] fills empty effect and equipment lists with examples, so a layout can be seen in the HUD editor
     *
     * [memory] should be the one the script runs with, since values like `fps` only refresh every so often
     */
    fun globals(editing: Boolean, memory: ScriptMemory): (String) -> Value? {
        val player = mc.player ?: return { name -> preview[name]?.invoke(memory) ?: Value.Null.takeIf { name in live } }
        val context = Context(player, editing, memory)
        return { name -> live[name]?.invoke(context) }
    }

    private fun block(x: Double, y: Double, z: Double): Value {
        val bx = floor(x).toInt()
        val by = floor(y).toInt()
        val bz = floor(z).toInt()
        return Value.Obj(
            "block",
            mapOf("x" to Value.Num(bx.toDouble()), "y" to Value.Num(by.toDouble()), "z" to Value.Num(bz.toDouble())),
            Value.Text("$bx, $by, $bz"),
        )
    }

    private fun chunk(x: Double, z: Double): Value {
        val cx = floor(x).toInt() shr 4
        val cz = floor(z).toInt() shr 4
        return Value.Obj("chunk", mapOf("x" to Value.Num(cx.toDouble()), "z" to Value.Num(cz.toDouble())), Value.Text("$cx, $cz"))
    }

    private fun direction(type: String, facing: Facing, bearing: Float): Value {
        val name = Value.Text(facing.full)
        return Value.Obj(
            type,
            mapOf(
                "name" to name,
                "short" to Value.Text(facing.abbreviated),
                "axis" to Value.Text(facing.axis),
                "bearing" to Value.Num((bearing.roundToInt() % 360).toDouble()),
            ),
            name,
        )
    }

    private fun facing(yaw: Float): Value = direction("facing", Facing.parse(yaw), Facing.bearingOf(yaw))

    private fun cardinal(yaw: Float): Value {
        val bearing = Facing.bearingOf(yaw)
        return direction("cardinal", CARDINALS[(bearing / 90f).roundToInt() % 4], bearing)
    }

    private fun dimension(player: LocalPlayer): String {
        //? if >= 1.21.11 {
        return player.level().dimension().identifier().path
        //?} elif > 1.8.9 {
        /*return player.level().dimension().location().path
        *///?} else {
        /*return when (player.dimension) {
            -1 -> "the_nether"
            1 -> "the_end"
            else -> "overworld"
        }
        *///?}
    }

    private fun biome(player: LocalPlayer): String {
        val level = mc.level ?: return ""
        val pos = player.blockPosition()
        //? if > 1.8.9 {
        //? if >= 1.21.11
        val id = level.getBiome(pos).unwrapKey().getOrNull()?.identifier() ?: return ""
        //? if < 1.21.11
        //val id = level.getBiome(pos).unwrapKey().getOrNull()?.location() ?: return ""
        val key = id.toLanguageKey("biome")
        return if (Language.getInstance().has(key)) Component.translatable(key).string else id.toString()
        //?} else
        //return level.getBiome(pos).name
    }

    private fun dayTime(): Long {
        val level = mc.level ?: return 0L
        //? if < 26
        //return level.dayTime
        //? if >= 26
        return level.overworldClockTime
    }

    private fun clock(dayTime: Long): String {
        val seconds = (((dayTime + 6000L) % TICKS_PER_DAY) * 3.6).toLong()
        return "%02d:%02d".format(seconds / 3600L % 24L, seconds % 3600L / 60L)
    }

    private fun weather(): String {
        val level = mc.level ?: return "clear"
        return when {
            level.isThundering -> "thunder"
            level.isRaining -> "rain"
            else -> "clear"
        }
    }

    private fun ping(player: LocalPlayer): Long? {
        //? if > 1.8.9 {
        val logger = mc.debugOverlay?.pingLogger
        if (logger != null && logger.size() > 0) return logger.get(logger.size() - 1)
        return mc.connection?.getPlayerInfo(player.uuid)?.latency?.toLong()
        //?} else
        //return mc.networkHandler?.getOnlinePlayer(player.getUuid())?.ping?.toLong()
    }

    /** Frames per second like the FPS element, refreshing once a second apart from `live` */
    private fun fps(memory: ScriptMemory): Value {
        val held = memory.hold("fps", FPS_SECONDS) {
            val data = FrameTimeHelper.latest
            val current = Value.Num(data.currentFps, 0)
            Value.Obj(
                "fps",
                mapOf(
                    "average" to Value.Num(data.averageFps, 0),
                    "median" to Value.Num(data.medianFps, 0),
                    "p95" to Value.Num(data.p95Millis, 0),
                    "p99" to Value.Num(data.p99Millis, 0),
                    "consistency" to Value.Num(data.consistencyPercent, 0),
                ),
                current,
            )
        } as Value.Obj
        return held.copy(fields = held.fields + ("live" to Value.Num(FrameTimeHelper.latest.currentFps, 0)))
    }

    /** Memory in GB like the Memory element */
    private fun memoryUsage(): Value {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
        val max = runtime.maxMemory() / BYTES_PER_MB
        val usedGb = Value.Num(used / MB_PER_GB, 1)
        return Value.Obj(
            "memory",
            mapOf(
                "used" to usedGb,
                "max" to Value.Num(max / MB_PER_GB, 1),
                "percent" to Value.Num(if (max > 0) used * 100.0 / max else 0.0, 1),
            ),
            usedGb,
        )
    }

    private fun entities(rendered: Int, total: Int): Value = Value.Obj(
        "entities",
        mapOf("rendered" to Value.Num(rendered.toDouble()), "total" to Value.Num(total.toDouble())),
        Value.Text("$rendered/$total"),
    )

    private fun cps(left: Int, right: Int): Value = Value.Obj(
        "cps",
        mapOf("left" to Value.Num(left.toDouble()), "right" to Value.Num(right.toDouble())),
        Value.Text("$left | $right"),
    )

    /** The Reach element shows 0 rather than 0.00 when there is no recent hit */
    private fun reach(distance: Double): Value = if (distance == 0.0) Value.Num(0.0) else twoDecimals(distance)

    private fun head(): Value = PlayerHeadTexture.current()?.let { Value.Icon(ImageIconSource(it)) } ?: Value.Null

    private fun item(stack: ItemStack?): Value {
        //~ if = 1.8.9 'stack.isEmpty' -> 'stack.count <= 0'
        if (stack == null || stack.isEmpty) return Value.Null
        val name = Value.Text(stack.hoverName.string)
        val damageable = stack.isDamageableItem
        return Value.Obj(
            "item",
            mapOf(
                "id" to Value.Text(itemId(stack.item)),
                "name" to name,
                "count" to Value.Num(stack.count.toDouble()),
                "durability" to if (damageable) Value.Num((stack.maxDamage - stack.damageValue).toDouble()) else Value.Null,
                "max_durability" to if (damageable) Value.Num(stack.maxDamage.toDouble()) else Value.Null,
                "damage" to Value.Num(stack.damageValue.toDouble()),
                "icon" to Value.Icon(ItemIconSource.of(stack)),
            ),
            name,
        )
    }

    private fun itemType(query: String): Value {
        val key = query.trim().lowercase()
        val item = resolvedItems[key] ?: resolveItem(key)?.also { resolvedItems[key] = it } ?: return Value.Null
        val player = mc.player
        val id = itemId(item)
        val name = Value.Text(ItemStack(item).hoverName.string)
        return Value.Obj(
            "item",
            mapOf(
                "id" to Value.Text(id),
                "name" to name,
                "count" to Value.Num((if (player == null) EXAMPLE_COUNT else inventoryCount(player, item)).toDouble()),
                "icon" to Value.Icon(ItemIconSource.of(id)),
            ),
            name,
        )
    }

    private fun inventoryCount(player: LocalPlayer, item: Item): Int {
        val inventory = player.inventory
        var count = 0
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            //? if > 1.8.9 {
            if (stack.item == item) count += stack.count
            //?} else
            //if (stack != null && stack.item == item) count += stack.count
        }
        return count
    }

    private fun effect(effect: MobEffectInstance): Value {
        //? if > 1.8.9 {
        val type = effect.effect.value()
        val key = BuiltInRegistries.MOB_EFFECT.getKey(type)
        val id = key?.path.orEmpty()
        val name = type.displayName.string
        val category = type.category.name.lowercase()
        val color = type.color
        val icon = key?.let(EffectIcons::iconFor)
        //?} else {
        /*val type = StatusEffect.BY_ID[effect.id]
        val id = EffectCatalog.pathOf(effect.id)
        val name = I18n.translate(type.translationKey)
        val category = if (type.isHarmful) "harmful" else "beneficial"
        val color = type.potionColor
        val icon = EffectIcons.iconFor(type)
        *///?}
        val infinite = effect.isInfiniteDuration
        val seconds = if (infinite) Double.POSITIVE_INFINITY else (effect.duration / 20).toDouble()
        val nameValue = Value.Text(name)
        return Value.Obj(
            "effect",
            mapOf(
                "name" to nameValue,
                "id" to Value.Text(id),
                "level" to Value.Num(effect.amplifier + 1.0),
                "amplifier" to Value.Num(effect.amplifier.toDouble()),
                "duration" to Value.Num(seconds),
                "ticks" to Value.Num(effect.duration.toDouble()),
                "time" to Value.Text(formatDuration(seconds)),
                "infinite" to Value.of(infinite),
                "ambient" to Value.of(effect.isAmbient),
                "category" to Value.Text(category),
                "beneficial" to Value.of(category == "beneficial"),
                "harmful" to Value.of(category == "harmful"),
                "color" to Value.Color(color or 0xFF000000.toInt()),
                "icon" to (icon?.let { Value.Icon(ImageIconSource(it)) } ?: Value.Null),
            ),
            nameValue,
        )
    }
}
