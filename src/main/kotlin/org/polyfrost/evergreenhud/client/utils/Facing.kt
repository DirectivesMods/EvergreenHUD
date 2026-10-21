package org.polyfrost.evergreenhud.client.utils

enum class Facing(
    val full: String,
    val abbreviated: String,
    val bearing: Float,
    /** The axes you are travelling towards, such as +X */
    val axis: String,
) {
    NORTH("North", "N", 0f, "-Z"),
    NORTH_EAST("North East", "NE", 45f, "+X -Z"),
    EAST("East", "E", 90f, "+X"),
    SOUTH_EAST("South East", "SE", 135f, "+X +Z"),
    SOUTH("South", "S", 180f, "+Z"),
    SOUTH_WEST("South West", "SW", 225f, "-X +Z"),
    WEST("West", "W", 270f, "-X"),
    NORTH_WEST("North West", "NW", 315f, "-X -Z");

    val isCardinal get() = bearing % 90f == 0f

    val isNorth get() = this == NORTH || this == NORTH_WEST || this == NORTH_EAST
    val isSouth get() = this == SOUTH || this == SOUTH_WEST || this == SOUTH_EAST
    val isWest get() = this == WEST || this == NORTH_WEST || this == SOUTH_WEST
    val isEast get() = this == EAST || this == NORTH_EAST || this == SOUTH_EAST

    companion object {

        fun parse(yaw: Float): Facing {
            val rotationYaw = wrapDegrees(yaw)
            return when {
                rotationYaw >= 165f || rotationYaw <= -165 -> NORTH
                rotationYaw in -165f..-105f -> NORTH_EAST
                rotationYaw in -105f..-75f -> EAST
                rotationYaw in -75f..-15f -> SOUTH_EAST
                rotationYaw in -15f..15f -> SOUTH
                rotationYaw in 15f..75f -> SOUTH_WEST
                rotationYaw in 75f..105f -> WEST
                rotationYaw in 105f..165f -> NORTH_WEST
                else -> NORTH
            }
        }

        fun parseExact(yaw: Float): Facing {
            val rotationYaw = wrapDegrees(yaw)
            return when {
                rotationYaw == -180f -> NORTH
                rotationYaw > -180f && rotationYaw < -90f -> NORTH_EAST
                rotationYaw == -90f -> EAST
                rotationYaw > -90f && rotationYaw < 0f -> SOUTH_EAST
                rotationYaw == 0f -> SOUTH
                rotationYaw > 0f && rotationYaw < 90f -> SOUTH_WEST
                rotationYaw == 90f -> WEST
                rotationYaw > 90f && rotationYaw < 180f -> NORTH_WEST
                else -> NORTH
            }
        }

        fun bearingOf(yaw: Float): Float {
            val bearing = (yaw + 180f) % 360f
            return if (bearing < 0f) bearing + 360f else bearing
        }

        fun wrapDegrees(value: Float): Float {
            var new = value
            new %= 360.0f
            if (new >= 180.0f) {
                new -= 360.0f
            }

            if (new < -180.0f) {
                new += 360.0f
            }

            return new
        }
    }
}