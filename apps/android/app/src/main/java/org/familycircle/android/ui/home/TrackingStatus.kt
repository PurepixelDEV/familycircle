package org.familycircle.android.ui.home

/** What the big status card shows. */
enum class TrackingStatus {
    /** The location service is running. */
    ON,

    /**
     * Not running, but the user wants it to (paused from the notification, or interrupted – e.g.
     * after a reboot without "Allow all the time"). The UI offers "Resume".
     */
    PAUSED,

    /** Switched off by the user (or stopped because of a problem that needs the user). */
    OFF;

    companion object {
        fun derive(running: Boolean, desired: Boolean, pausedByUser: Boolean): TrackingStatus = when {
            running -> ON
            pausedByUser || desired -> PAUSED
            else -> OFF
        }
    }
}
