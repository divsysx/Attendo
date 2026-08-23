# Minification is off for both build types (see app/build.gradle.kts), so this file
# is a placeholder that documents what a release build would need rather than a set
# of rules currently in force.
#
# If you turn `isMinifyEnabled` on:
#
#  - Room generates implementations by name; keep its runtime classes.
#  - The engine in :core is plain Kotlin with no reflection, so it needs nothing.
#  - Compose ships its own consumer rules via the AndroidX artifacts.

-keep class com.attendo.data.db.** { *; }
-dontwarn org.jetbrains.annotations.**
