# UICapture ProGuard / R8 rules

# Kotlinx Serialization
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Compose internal rules
-keep class androidx.compose.** { *; }
