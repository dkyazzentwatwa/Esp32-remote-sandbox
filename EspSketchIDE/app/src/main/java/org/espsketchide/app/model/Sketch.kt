package org.espsketchide.app.model

/**
 * A sketch is a folder whose name matches its primary .ino file, per Arduino convention.
 * [folderId] is an opaque storage id (a SAF document URI string in the app).
 */
data class Sketch(
    val name: String,
    val folderId: String
) {
    val primaryFileName: String get() = "$name.ino"
}

data class SketchFile(
    val name: String,
    val id: String
)
