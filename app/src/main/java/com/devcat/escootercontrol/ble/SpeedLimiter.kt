package com.devcat.escootercontrol.ble

import androidx.compose.runtime.Immutable

/**
 * State of the dashboard's speed limiter shortcut.
 *
 * The scooter only ever stores ONE max speed (code 60), so the speed to restore when the limiter
 * is switched off has to be remembered on the phone. [fullKmh] is that remembered value,
 * [activeKmh] is what the scooter is set to right now, and [limitKmh] is the user-configurable
 * limit the shortcut switches to (null = Unlimited, which hides the shortcut entirely).
 */
@Immutable
data class SpeedLimiterUi(
    /** Speed to restore when the limiter is switched off, or null if it was never captured. */
    val fullKmh: Int? = null,
    /** Max speed the connected scooter is currently set to, or null while it hasn't replied. */
    val activeKmh: Int? = null,
    /** Limit the shortcut applies, or null when the user chose "Unlimited". */
    val limitKmh: Int? = null,
    /** A write we just sent that the scooter hasn't confirmed yet. */
    val pendingKmh: Int? = null
) {
    /**
     * True while the limiter is on. The pending value counts so the shortcut keeps its state
     * between the slider/write and the scooter's confirmation frame.
     */
    val limiterActive: Boolean
        get() = limitKmh != null && (pendingKmh == limitKmh || activeKmh == limitKmh)

    /**
     * True when the shortcut has something useful to do: a usable limit is configured and the
     * scooter has told us where it currently stands.
     */
    val available: Boolean
        get() {
            val limit = limitKmh ?: return false
            if (limit !in SpeedLimiterRules.LIMIT_RANGE) return false
            val full = fullKmh
            // A limiter may only ever lower the speed. Without a remembered full value we can't
            // prove that, so the shortcut stays hidden until Speed & performance sets one.
            if (full != null && limit >= full) return false
            return limiterActive || activeKmh != null
        }

    /** What a tap would set, or null when the shortcut should not react yet. */
    val toggleTargetKmh: Int?
        get() = when {
            !available || pendingKmh != null -> null
            // With nothing remembered, "off" falls back to the scooter's top of range. A data wipe
            // drops the remembered value too, and the limiter being reset to Unlimited is a state
            // worth being able to leave.
            limiterActive -> fullKmh ?: SpeedLimiterRules.UNLIMITED
            else -> limitKmh
        }
}

/**
 * Pure decision logic for [SpeedLimiterUi]. Kept separate from the ViewModel so the reconciliation
 * rules can be unit tested on the JVM (the ViewModel pulls in Android classes).
 */
object SpeedLimiterRules {
    /** Wire value meaning "no limit configured" (the top of the max-speed range). */
    const val UNLIMITED = 31

    /** Values the scooter accepts for its max-speed setting (code 60). */
    val WIRE_RANGE = 1..UNLIMITED

    /** Selectable limits for the limiter: everything below Unlimited. */
    val LIMIT_RANGE = 1 until UNLIMITED

    /** Ready-to-show state, or null when the shortcut should stay hidden. */
    fun ui(
        fullKmh: Int?,
        activeKmh: Int?,
        limitKmh: Int?,
        pendingKmh: Int? = null
    ) = SpeedLimiterUi(fullKmh, activeKmh, limitKmh, pendingKmh).takeIf { it.available }

    /**
     * Folds a fresh max-speed reading into the remembered full speed.
     *
     * - While one of our own writes is in flight the reading is not authoritative: it may still be
     *   the value we are replacing, which must not overwrite the remembered full speed.
     * - A reading equal to the chosen limit means the limiter set it, so the full value survives.
     * - Anything else inside the wire range is the truth (changed here or in the stock app).
     * - Values outside it (0, 99, ...) carry no information, so keep what we had.
     */
    fun reconcile(
        fullKmh: Int?,
        reportedKmh: Int,
        limitKmh: Int?,
        pendingKmh: Int?
    ): Int? = when {
        pendingKmh != null -> fullKmh
        limitKmh != null && reportedKmh == limitKmh -> fullKmh
        reportedKmh in WIRE_RANGE -> reportedKmh
        else -> fullKmh
    }
}
