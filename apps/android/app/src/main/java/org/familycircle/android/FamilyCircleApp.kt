package org.familycircle.android

import android.app.Application

class FamilyCircleApp : Application() {
    /** Created lazily so receivers/services/workers and the UI share one instance per process. */
    val container: AppContainer by lazy { AppContainer(this) }
}
