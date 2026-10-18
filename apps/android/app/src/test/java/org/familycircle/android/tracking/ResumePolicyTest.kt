package org.familycircle.android.tracking

import org.familycircle.android.tracking.ResumePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {
    private fun decide(
        desired: Boolean = true,
        loggedIn: Boolean = true,
        running: Boolean = false,
        foreground: Boolean = true,
        background: Boolean = true,
    ) = ResumePolicy.decide(desired, loggedIn, running, foreground, background)

    @Test
    fun `restarts when sharing is on, not running and allowed all the time`() {
        assertEquals(Action.RESTART, decide())
    }

    @Test
    fun `never resumes what the user stopped or paused`() {
        assertEquals(Action.NONE, decide(desired = false))
        assertEquals(Action.NONE, decide(desired = false, background = false))
    }

    @Test
    fun `never resumes after sign-out`() {
        assertEquals(Action.NONE, decide(loggedIn = false))
    }

    @Test
    fun `does nothing while the service is running`() {
        assertEquals(Action.NONE, decide(running = true))
        assertEquals(Action.NONE, decide(running = true, background = false))
    }

    @Test
    fun `asks the user when Android would refuse a background start`() {
        assertEquals(Action.ASK_USER, decide(background = false))
        assertEquals(Action.ASK_USER, decide(foreground = false, background = false))
        // "All the time" without the foreground grant cannot happen on Android, but must not restart.
        assertEquals(Action.ASK_USER, decide(foreground = false))
    }
}
