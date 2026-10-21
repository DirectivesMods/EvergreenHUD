package org.polyfrost.evergreenhud.client.hud.item

//? if > 1.8.9 {
import net.minecraft.core.registries.BuiltInRegistries
//? if < 1.21.11
//import net.minecraft.resources.ResourceLocation
//? if >= 1.21.11
import net.minecraft.resources.Identifier as ResourceLocation
import net.minecraft.world.item.Items
//?} else
//import net.minecraft.resource.Identifier
import net.minecraft.world.item.Item

/** Finds an item from an id such as `diamond`, `minecraft:diamond` or `Diamond Sword` */
fun resolveItem(query: String): Item? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null

    //? if > 1.8.9 {
    val id = ResourceLocation.tryParse(
        if (trimmed.contains(':')) trimmed.lowercase() else "minecraft:${trimmed.lowercase().replace(' ', '_')}"
    ) ?: return null
    return BuiltInRegistries.ITEM.getOptional(id).orElse(null)?.takeIf { it != Items.AIR }
    //?} else {
    /*val id = Identifier(
        if (trimmed.contains(':')) trimmed.lowercase() else "minecraft:${trimmed.lowercase().replace(' ', '_')}"
    )
    return Item.REGISTRY[id]
    *///?}
}

/** The full id of [item], such as `minecraft:diamond` */
fun itemId(item: Item): String {
    //~ if = 1.8.9 'BuiltInRegistries.ITEM' -> 'Item.REGISTRY'
    return BuiltInRegistries.ITEM.getKey(item).toString()
}
