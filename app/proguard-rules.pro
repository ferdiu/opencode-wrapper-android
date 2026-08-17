# Keep kotlinx.serialization models used for OpenCode API payloads.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.opencode.wrapper.**$$serializer { *; }
-keepclassmembers class com.opencode.wrapper.** {
    *** Companion;
}
-keepclasseswithmembers class com.opencode.wrapper.** {
    kotlinx.serialization.KSerializer serializer(...);
}
