# Meo Mic ProGuard Rules

# Keep Compose runtime
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep Kotlin metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class * {
    @kotlin.Metadata *;
}

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep our app classes
-keep class com.wifmic.** { *; }

# Keep service classes
-keep class * extends android.app.Service

# Keep ViewModel
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Keep data classes
-keepclassmembers class * {
    public <init>(...);
}

# Don't warn about missing classes
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
