plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    // Pinned to the Kotlin version above: the serialization plugin is a compiler
    // plugin and a mismatch fails at compile time rather than at runtime.
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20" apply false
}
