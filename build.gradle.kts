plugins {
    alias(libs.plugins.android).apply(false)
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.detekt).apply(false)
}

// Dropbox treats app/build files as cloud placeholders; Gradle then fails
// ("not a regular file"). Keep compiler output on the local disk.
val localBuildRoot = File(System.getProperty("user.home"), "AppData/Local/phonly-phone-build")
allprojects {
    layout.buildDirectory.set(File(localBuildRoot, name))
}
