# kotlinx.serialization keeps its own generated serializers; these rules cover
# the companion lookups R8 cannot see.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class cz.peelco.jolt.**$$serializer { *; }
-keepclassmembers class cz.peelco.jolt.** { *** Companion; }
-keepclasseswithmembers class cz.peelco.jolt.** { kotlinx.serialization.KSerializer serializer(...); }

# Ktor pulls in optional SLF4J; it is not shipped.
-dontwarn org.slf4j.**
