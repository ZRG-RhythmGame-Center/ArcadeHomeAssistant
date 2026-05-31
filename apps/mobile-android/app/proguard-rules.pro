# Wave 5 task 39: tightened ProGuard rules.
#
# The Kotlin serialization Gradle plugin (org.jetbrains.kotlin.plugin.serialization)
# automatically generates @Keep annotations and serializer lookup tables for every
# @Serializable class, so we no longer need broad blanket keeps for the entire
# kotlinx.serialization runtime or for every data model class.
#
# What we keep here:
#   1. The serialization runtime itself (needed at runtime for reflection-free
#      serialization; the plugin does NOT keep the runtime classes).
#   2. The generated $$serializer companion objects (the plugin keeps the class
#      but not the companion, which is accessed by name at runtime).
#   3. The @SerialName field annotation (used by the runtime to map JSON keys).
#   4. The standard -if/@Serializable conditional keep that the plugin recommends
#      for shrinking + optimisation passes.
#
# Removed (were too broad):
#   -keep class com.maimai.home.data.models.** { *; }
#   -keepclassmembers class com.maimai.home.data.models.** { *; }
#   -keep class kotlinx.serialization.json.** { *; }
#   -keep class kotlinx.serialization.** { *; }

# Keep the serialization runtime (needed for descriptor lookup at runtime).
-keep class kotlinx.serialization.KSerializer { *; }
-keep class kotlinx.serialization.SerializationStrategy { *; }
-keep class kotlinx.serialization.DeserializationStrategy { *; }
-keep class kotlinx.serialization.descriptors.** { *; }
-keep class kotlinx.serialization.encoding.** { *; }
-keep class kotlinx.serialization.json.Json { *; }
-keep class kotlinx.serialization.json.JsonElement { *; }
-keep class kotlinx.serialization.json.JsonObject { *; }
-keep class kotlinx.serialization.json.JsonArray { *; }
-keep class kotlinx.serialization.json.JsonPrimitive { *; }
-keep class kotlinx.serialization.json.JsonNull { *; }
-keep class kotlinx.serialization.json.internal.** { *; }
-keep class kotlinx.serialization.modules.** { *; }

# Keep generated $$serializer companion objects (accessed by name at runtime).
-keepclassmembers class **$$serializer { *; }

# Keep @SerialName field annotations (used by the runtime for JSON key mapping).
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Conditional keep: if a class is @Serializable, keep it with shrinking/optimisation
# allowed so R8 can still remove unused members.
-if @kotlinx.serialization.Serializable class **
-keep,allowshrinking,allowoptimization class <1>

# Suppress warnings for internal kotlinx.serialization APIs that are not part
# of the public ABI but are referenced by generated code.
-dontwarn kotlinx.serialization.**
