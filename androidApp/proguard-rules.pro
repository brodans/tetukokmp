# KMP/Compose and AndroidX entry points
-keep class id.tetuko.** { *; }
-keep class androidx.compose.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class io.ktor.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }

# Native bridge callbacks and notification/file-provider resources
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn java.lang.management.**
