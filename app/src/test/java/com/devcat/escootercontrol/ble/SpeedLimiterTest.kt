package com.devcat.escootercontrol.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules behind the dashboard's speed limiter shortcut. The scooter stores only one max speed and
 * the limiter's limit is user-configurable (null = Unlimited), so these tests pin down when a
 * reading means "the limiter is on" versus "the scooter's real max speed".
 */
class SpeedLimiterTest {

    @Test
    fun readingInsideWireRangeIsAdoptedAsFullSpeed() {
        assertEquals(25, SpeedLimiterRules.reconcile(null, 25, limitKmh = null, pendingKmh = null))
        assertEquals(31, SpeedLimiterRules.reconcile(25, 31, limitKmh = null, pendingKmh = null))
        assertEquals(10, SpeedLimiterRules.reconcile(null, 10, limitKmh = null, pendingKmh = null))
        // A slow scooter's own max speed is a perfectly valid full speed.
        assertEquals(16, SpeedLimiterRules.reconcile(null, 16, limitKmh = null, pendingKmh = null))
    }

    @Test
    fun limitReadingKeepsRememberedValue() {
        // The scooter sitting on the chosen limit means the shortcut set it.
        assertEquals(25, SpeedLimiterRules.reconcile(25, 15, limitKmh = 15, pendingKmh = null))
        assertEquals(31, SpeedLimiterRules.reconcile(31, 20, limitKmh = 20, pendingKmh = null))
        // A limit inside the wire range is ambiguous with a real max, so the limit check has to
        // win; otherwise choosing 25 with 31 remembered would erase the 31.
        assertEquals(31, SpeedLimiterRules.reconcile(31, 25, limitKmh = 25, pendingKmh = null))
    }

    @Test
    fun limitReadingWithNothingRememberedLeavesFullUnknown() {
        // Data was wiped while the scooter stayed limited: there is no speed to restore.
        assertNull(SpeedLimiterRules.reconcile(null, 16, limitKmh = 16, pendingKmh = null))
    }

    @Test
    fun outOfRangeReadingKeepsRememberedValue() {
        // Unknown readings carry no information, so the remembered full speed must survive.
        assertEquals(25, SpeedLimiterRules.reconcile(25, 0, limitKmh = null, pendingKmh = null))
        assertEquals(25, SpeedLimiterRules.reconcile(25, 99, limitKmh = null, pendingKmh = null))
    }

    @Test
    fun readingIsIgnoredWhileOurWriteIsInFlight() {
        // Configured 31, limiting to 25, user drags the slider to 15. The scooter still reports 25
        // when the store already says 15, so that stale frame must not be adopted as the real max.
        assertEquals(
            31,
            SpeedLimiterRules.reconcile(31, 25, limitKmh = 15, pendingKmh = 15)
        )
        // Even a reading equal to the new limit is not authoritative until the write settles.
        assertEquals(
            31,
            SpeedLimiterRules.reconcile(31, 15, limitKmh = 15, pendingKmh = 15)
        )
    }

    @Test
    fun unlimitedLimitHidesTheButton() {
        // Nothing configured yet: the default is Unlimited, so there is no shortcut.
        assertNull(SpeedLimiterRules.ui(25, 25, limitKmh = null))
        assertNull(SpeedLimiterRules.ui(null, null, limitKmh = null))
    }

    @Test
    fun limitAtOrAboveFullHidesTheButton() {
        // A limiter may only ever lower the speed.
        assertNull(SpeedLimiterRules.ui(25, 25, limitKmh = 25))
        assertNull(SpeedLimiterRules.ui(25, 25, limitKmh = 30))
        assertNull(SpeedLimiterRules.ui(16, 16, limitKmh = 20))
    }

    @Test
    fun buttonTogglesBetweenLimitAndFull() {
        val normal = SpeedLimiterRules.ui(25, 25, limitKmh = 15)!!
        assertFalse(normal.limiterActive)
        assertEquals(15, normal.toggleTargetKmh)

        val limited = SpeedLimiterRules.ui(25, 15, limitKmh = 15)!!
        assertTrue(limited.limiterActive)
        assertEquals(25, limited.toggleTargetKmh)
    }

    @Test
    fun slowScooterCanBeLimited() {
        // A 20 km/h scooter with a 15 km/h limit used to be impossible to express.
        val limited = SpeedLimiterRules.ui(20, 15, limitKmh = 15)!!
        assertTrue(limited.limiterActive)
        assertEquals(20, limited.toggleTargetKmh)
    }

    @Test
    fun buttonIgnoresTapsWhileAWriteIsPending() {
        val pending = SpeedLimiterRules.ui(25, 25, limitKmh = 15, pendingKmh = 15)!!
        assertNull(pending.toggleTargetKmh)
        assertTrue(pending.available)
        // The pending value counts as "on" so the button doesn't flicker mid-write.
        assertTrue(pending.limiterActive)
    }

    @Test
    fun buttonStaysPutWhileRestoringFullSpeed() {
        // The scooter still reports the limit during the restore write, but the pending value is
        // the full speed. The button must stay visible, just be unclickable.
        val restoring = SpeedLimiterRules.ui(25, 15, limitKmh = 15, pendingKmh = 25)!!
        assertTrue(restoring.available)
        assertNull(restoring.toggleTargetKmh)
        // The dashboard labels anything pending that isn't the limit as "off".
        assertTrue(restoring.pendingKmh != null && restoring.pendingKmh != restoring.limitKmh)
    }

    @Test
    fun unlimitedIsTheFallbackWhenFullSpeedIsUnknown() {
        // Data wipe while the scooter is still limited: "off" has nothing to restore, so it falls
        // back to the top of the wire range instead of leaving the scooter stuck.
        val stuck = SpeedLimiterRules.ui(null, 16, limitKmh = 16)!!
        assertTrue(stuck.limiterActive)
        assertEquals(SpeedLimiterRules.UNLIMITED, stuck.toggleTargetKmh)
    }

    @Test
    fun unlimitedConstantIsTheSliderEnd() {
        // The slider's rightmost detent means Unlimited, and usable limits stop just below it.
        assertEquals(SpeedLimiterRules.UNLIMITED, SpeedLimiterRules.LIMIT_RANGE.last + 1)
        assertEquals(SpeedLimiterRules.WIRE_RANGE.last, SpeedLimiterRules.UNLIMITED)
    }
}
