# Preserve Compose desktop entry points and Kotlin serialization reflective metadata.
-keep public class id.tetuko.app.MainKt { public static void main(java.lang.String[]); }
-keep public class id.tetuko.app.desktop.** { *; }

# Keep application/domain/data classes (kotlinx.serialization uses direct serializers,
# but keeping our own classes avoids reflection/service-loader edge cases).
-keep class id.tetuko.** { *; }

# Libraries whose entry points are resolved reflectively or via ServiceLoader.
-keep class kotlinx.serialization.** { *; }
-keep class io.ktor.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }

# Compose/Material icons are referenced directly from Kotlin code; R8 can remove
# the unused ones from material-icons-extended via normal reachability analysis.
-dontwarn **
