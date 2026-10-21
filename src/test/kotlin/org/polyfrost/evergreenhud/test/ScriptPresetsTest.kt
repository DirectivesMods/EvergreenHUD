package org.polyfrost.evergreenhud.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.polyfrost.evergreenhud.client.hud.ScriptHud
import org.polyfrost.evergreenhud.client.script.ScriptBindings
import org.polyfrost.evergreenhud.client.script.ScriptPresets
import org.polyfrost.oneconfig.api.config.v1.annotations.Dropdown

class ScriptPresetsTest {
    @Test
    fun presetsCompile() {
        ScriptBindings.library.compile(ScriptPresets.DEFAULT)
        for (preset in ScriptPresets.ALL) ScriptBindings.library.compile(preset.source)
    }

    @Test
    fun documentedExamplesCompile() {
        val examples = listOf(
            "&0&l[X] &r&f{block.x}\n&0&l[Y] &r&f{block.y}{tab}  {head} {cardinal.short}\n&0&l[Z] &r&f{block.z}",
            "&7X &f{block.x} &7Y &f{block.y} &7Z &f{block.z}\n&cHealth &f{health}&7/{max_health}",
            "{facing.short} {held.name} {memory.used} {facing} {held} {health / max_health * 100} {\"HP: \" + health}",
            "{round(x, 1)} {fixed(x, 2)} {floor(x)} {signed(y)} {name | upper} {x | round(1)} {dimension | replace(\"_\", \" \") | title}",
            "{sprinting ? \"&aRunning\" : \"&7Walking\"}\n{effects[0].duration <= 10 ? \"&c\" : \"&f\"}{effects[0].time}",
            "{if health < 6}\n&cLow health!\n{elif health < 12}\n&eGetting low\n{else}\n&aHealthy\n{end}",
            "{if held.id in [\"minecraft:bow\", \"minecraft:crossbow\"]}bow{end} {held.name or \"Nothing\"}",
            "{for e in effects}{e.name}{if not loop.last}, {end}{end}",
            "{for id in [\"iron_ingot\", \"gold_ingot\", \"diamond\", \"emerald\"]}\n{item(id).icon} {item(id).count}\n{end}",
            "{set hp = health + absorption}\n&cHP &f{round(hp, 1)}",
            "{color(\"gold\")}{color(\"#FF8800\")}{color(255, 136, 0)}{gradient(health, 0, 20, \"red\", \"yellow\", \"green\")}" +
                "{blend(\"red\", \"blue\", 0.5)}{rainbow()}{rainbow(2)}{size(1.5)}{size(0.75)}",
            "{head} {head(2)} {held.icon} {helmet.icon} {item(\"emerald\").icon} {effect(\"speed\").icon}",
            "&7X{tab} &f{block.x}\n&7Y{tab} &f{block.y}\n&7Z{tab} &f{block.z}\n{# a comment #}",
            "{reach | exact} {fixed(reach, 15)} {ping | every(2)} {fps | smooth} {speed | every(1) | smooth} {health | smooth} {memory | smooth} {cps.left | smooth}",
            "{for e in effects}{e.duration | every(5)}{end} {fps.live} {fps.average} {fps.median} {fps.p95} {fps.p99} {fps.consistency}",
            "{repeat(\"|\", health)} {pad_left(7, 3, \"0\")} {sort(effects, \"duration\")} {where(effects, \"harmful\")} {now(\"HH:mm:ss\")}",
        )
        for (example in examples) ScriptBindings.library.compile(example)
    }

    @Test
    fun dropdownMatchesPresets() {
        val options = ScriptHud::class.java.getDeclaredField("preset").getAnnotation(Dropdown::class.java).options
        assertEquals(ScriptPresets.ALL.map { it.name }, options.toList())
    }

    @Test
    fun helpMentionsEveryName() {
        val library = ScriptBindings.library
        val documented = ScriptPresets.VARIABLES
        val missing = (library.variables + library.functions.keys - setOf("tab", "scale"))
            .filter { !Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(documented) }
        assertEquals(emptyList<String>(), missing)
    }
}
