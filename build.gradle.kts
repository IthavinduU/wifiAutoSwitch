// Versions pinned to a combination known to build together (AGP 8.8.1 + Gradle 8.11.1 + Kotlin 2.0.21).
// If Android Studio offers an AGP upgrade, use its Upgrade Assistant rather than editing by hand.
plugins {
    id("com.android.application") version "8.8.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
