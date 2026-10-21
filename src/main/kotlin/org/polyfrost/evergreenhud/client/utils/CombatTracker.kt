package org.polyfrost.evergreenhud.client.utils

import org.polyfrost.evergreenhud.client.ClientDamageEntityEvent
import org.polyfrost.evergreenhud.client.ServerDamageEntityEvent
import org.polyfrost.oneconfig.api.event.v1.eventHandler
import org.polyfrost.oneconfig.api.event.v1.events.KeyInputEvent
import org.polyfrost.oneconfig.api.event.v1.events.MouseInputEvent
import org.polyfrost.oneconfig.utils.v1.dsl.mc

private const val CLICK_WINDOW_NANOS = 1_000_000_000L
private const val COMBO_DISCARD_MILLIS = 2_000L
private const val REACH_DISCARD_MILLIS = 3_000L

/** Clicks, combo and reach for script HUDs, tracked the same way as the CPS, Combo and Reach HUDs */
object CombatTracker {
    private val leftClicks = ArrayList<Long>(20)
    private val rightClicks = ArrayList<Long>(20)

    private var combo = 0
    private var lastHitTime = 0L
    private var lastAttackId = -1

    private var reach = 0.0
    private var lastReachTime = 0L

    val leftCps: Int get() = recent(leftClicks)

    val rightCps: Int get() = recent(rightClicks)

    val currentCombo: Int
        get() = if (System.currentTimeMillis() - lastHitTime >= COMBO_DISCARD_MILLIS) 0 else combo

    val lastReach: Double
        get() = if (System.currentTimeMillis() - lastReachTime > REACH_DISCARD_MILLIS) 0.0 else reach

    fun initialize() {
        eventHandler { (btn, state): MouseInputEvent ->
            if (state != 1) return@eventHandler
            val options = mc.options ?: return@eventHandler
            if (options.keyAttack.matchesMouseButton(btn)) leftClicks.add(System.nanoTime())
            if (options.keyUse.matchesMouseButton(btn)) rightClicks.add(System.nanoTime())
        }

        eventHandler { (key, _, state): KeyInputEvent ->
            if (state != 1 || key == 0) return@eventHandler
            val options = mc.options ?: return@eventHandler
            if (options.keyAttack.matchesKeyCode(key)) leftClicks.add(System.nanoTime())
            if (options.keyUse.matchesKeyCode(key)) rightClicks.add(System.nanoTime())
        }

        eventHandler { (attacker, target): ServerDamageEntityEvent ->
            if (target == mc.player) {
                combo = 0
                lastAttackId = -1
                return@eventHandler
            }
            if (attacker != mc.player) return@eventHandler

            combo = if (target.uniqueEntityId == lastAttackId && currentCombo > 0) combo + 1 else 1
            lastHitTime = System.currentTimeMillis()
            lastAttackId = target.uniqueEntityId
        }

        eventHandler { event: ClientDamageEntityEvent ->
            if (event.attacker != mc.player) return@eventHandler
            val distance = calculateReachDistanceToEntity(event.target)
            if (distance == 0.0) return@eventHandler
            reach = distance
            lastReachTime = System.currentTimeMillis()
        }
    }

    private fun recent(clicks: ArrayList<Long>): Int {
        val now = System.nanoTime()
        clicks.fastRemoveIfReversed { now - it > CLICK_WINDOW_NANOS }
        return clicks.size
    }
}
