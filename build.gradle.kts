plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), using the Kotlin Gradle plugin version below
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("app.cash.licensee") version "1.14.1" apply false
}
