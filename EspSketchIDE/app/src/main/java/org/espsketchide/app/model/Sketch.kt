package org.espsketchide.app.model

import android.net.Uri

/** A sketch is a folder whose name matches its primary .ino file, per Arduino convention. */
data class Sketch(
    val name: String,
    val folderUri: Uri
)

data class SketchFile(
    val name: String,
    val uri: Uri
)
