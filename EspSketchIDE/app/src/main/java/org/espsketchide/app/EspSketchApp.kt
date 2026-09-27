package org.espsketchide.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.espsketchide.app.data.DocumentFileStorage
import org.espsketchide.app.data.SketchRepository

class EspSketchApp : Application() {

    /** For work that must finish even after the screen that started it is gone (e.g. autosave). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val storage by lazy { DocumentFileStorage(this) }

    val repository by lazy { SketchRepository(storage) }
}
