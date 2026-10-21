package org.polyfrost.evergreenhud.client.script

class ScriptPreset(val name: String, val source: String)

object ScriptPresets {
    const val DEFAULT = """{# Edit me! Values go in {braces}, colors use & codes #}
&aHello, &f{name}&a!
&7X &f{block.x} &7Y &f{block.y} &7Z &f{block.z}"""

    /** In the same order as the Preset dropdown */
    val ALL = listOf(
        ScriptPreset(
            "Coordinates",
            """{# Your block position, with your head and facing beside Y #}
&7&l[X] &r{block.x}
&7&l[Y] &r{block.y}{tab}  {head} {cardinal.short}
&7&l[Z] &r{block.z}""",
        ),
        ScriptPreset(
            "Potion Effects",
            """{# Each effect's icon, its name in the effect's own color, and the time left #}
{for e in effects}
{e.icon(2)} {e.color}&l{e.name}{if e.level > 1} {e.level | roman}{end}
{e.duration <= 10 ? "&c" : "&f"}{e.time}
{end}""",
        ),
        ScriptPreset(
            "Player Stats",
            """&cHealth {gradient(health, 0, max_health, "red", "yellow", "green")}{round(health, 1)}&7/{max_health}{if absorption > 0} &6+{round(absorption, 1)}{end}
&6Food &f{food} &7({round(saturation, 1)})
&bArmor &f{armor}
&aLevel &f{level} &7({round(xp * 100)}%)""",
        ),
        ScriptPreset(
            "Armor",
            """{for piece in armor_items}
{piece.icon} {if piece.max_durability}{gradient(piece.durability, 0, piece.max_durability, "red", "yellow", "green")}{piece.durability}{end}
{end}
{if held}
{held.icon} &f{held.name}{if held.count > 1} &7x{held.count}{end}
{end}""",
        ),
        ScriptPreset(
            "Bed Wars Resources",
            """{for id in ["iron_ingot", "gold_ingot", "diamond", "emerald"]}
{set resource = item(id)}
{resource.icon} {resource.count > 0 ? "&f" : "&8"}{resource.count}
{end}""",
        ),
        ScriptPreset(
            "PvP",
            """&bCPS &f{cps.left} &7| &f{cps.right}
&bCombo &f{combo}
&bReach &f{fixed(reach, 2)}
{if ping}&bPing {gradient(ping, 30, 200, "green", "yellow", "red")}{ping}ms{end}""",
        ),
        ScriptPreset(
            "Info",
            """&e{fps | smooth} &7FPS
&e{now("HH:mm")} &7(in game {time}, day {day})
&a{biome} &7in &a{dimension | replace("_", " ") | title}
{if not singleplayer}&b{server}{end}""",
        ),
    )

    const val HELP = """Text shows as you write it, and every line is a line in the HUD.
Colors: &0-&9 and &a-&f, or any hex color like &#FF8800. Styles: &l bold, &o italic, &n underline, &m strikethrough, &r reset. A color keeps bold and the like until &r.
Values: {x} {block.y} {health} {fps} {facing.short} {held.name}
Math and formatting: {round(x, 1)} {health / 2} {name | upper} {fixed(reach, 2)}, or every digit with {reach | exact}
Choices: {sprinting ? "&aRunning" : "&7Walking"} or {if health < 6}&cLow!{elif health < 12}&eOK{else}&aFull{end}
Loops: {for e in effects}...{end}, where e has name, level, time, duration, color, icon and more. Inside a loop, loop.index, loop.first and loop.last are there too.
Icons: {head} {e.icon} {held.icon} {item("emerald").icon}. Make them taller with {head(2)}, and text after a tall icon at the start of a line lines up beside it.
Size and color: {size(1.5)} {color("gold")} {gradient(health, 0, 20, "red", "green")} {rainbow()}
Values refresh as often as their own element does, so {fps} changes once a second like the FPS element. Slow any number down with {ping | every(2)}, and make it count up or down to new numbers instead of jumping with {fps | smooth}.
Columns: {tab} lines text up across lines.
Variables: {set hp = health + absorption} then {hp}
Comments: {# like this #}. Write \{ to show a brace. A line that ends up empty, such as an {if} that is false, is hidden."""

    const val VARIABLES = """Position: x, y, z, block (.x .y .z), chunk, yaw, pitch, facing (.name .short .axis .bearing), cardinal, dimension, biome, speed
Player: name, health, max_health, absorption, food, saturation, armor, level, xp, sprinting, sneaking, flying, on_ground, in_water, burning, slot, head
Items: held, offhand, helmet, chestplate, leggings, boots, armor_items, item("id"). Each has name, id, count, durability, max_durability, damage and icon.
Effects: effects, effect("speed"). Each has name, id, level, amplifier, time, duration, ticks, infinite, ambient, category, beneficial, harmful, color and icon.
World: time, day, weather
Game: fps (.average .median .p95 .p99 .consistency .live), ping, server, singleplayer, memory (.used .max .percent, in GB), entities (.rendered .total), cps (.left .right), combo, reach
Functions: round, fixed, exact, floor, ceil, abs, sqrt, pow, min, max, clamp, signed, number, text, roman, duration, every, smooth, len, upper, lower, title, trim, plain, replace, truncate, pad_left, pad_right, repeat, contains, join, first, last, reverse, take, sort, where, range, switch, now, color, rgb, hsv, blend, gradient, rainbow, size"""
}
