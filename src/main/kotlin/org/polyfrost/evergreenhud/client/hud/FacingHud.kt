package org.polyfrost.evergreenhud.client.hud

import org.polyfrost.evergreenhud.client.utils.CachedTextHud
import org.polyfrost.evergreenhud.client.utils.Facing
import org.polyfrost.evergreenhud.client.utils.cameraYaw
import org.polyfrost.oneconfig.api.config.v1.annotations.Dropdown
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private val CARDINALS = arrayOf(Facing.NORTH, Facing.EAST, Facing.SOUTH, Facing.WEST)

class FacingHud : CachedTextHud(
    title = "Facing",
    category = Category.INFO,
    defaultText = "North"
) {
    @Switch(title = "Show Direction", description = "Show the cardinal direction you are facing, such as North.")
    var showDirection = true

    @Dropdown(title = "Style", options = ["Full", "Abbreviated"])
    var style = 0

    @Switch(title = "Show Intercardinals", description = "Show North East, South East, South West and North West.")
    var showIntercardinals = true

    @Switch(title = "Show Bearing", description = "Append the exact bearing in degrees.")
    var showBearing = false

    @Switch(title = "Show Axis", description = "Append the axis you are travelling towards, such as +X.")
    var showAxis = false

    override fun setup() {
        super.setup()

        if (isReal) {
            updateWhenChanged("showDirection")
            updateWhenChanged("style")
            updateWhenChanged("showIntercardinals")
            updateWhenChanged("showBearing")
            updateWhenChanged("showAxis")
        }
    }

    override fun getText(): String {
        val yaw = cameraYaw ?: return defaultText
        val bearing = Facing.bearingOf(yaw)
        val facing = if (showIntercardinals) {
            Facing.parse(yaw)
        } else {
            CARDINALS[(bearing / 90f).roundToInt() % 4]
        }

        if (!showDirection) {
            return buildString {
                append(bearing.roundToInt() % 360).append('°')

                if (showAxis) {
                    append(' ').append(facing.axis)
                }
            }
        }

        return buildString {
            append(if (style == 1) facing.abbreviated else facing.full)

            if (showAxis) {
                append(' ').append(facing.axis)
            }

            if (showBearing) {
                append(" (").append(bearing.roundToInt() % 360).append("°)")
            }
        }
    }

    override fun updateFrequency(): Long = 50.milliseconds.inWholeNanoseconds
}
