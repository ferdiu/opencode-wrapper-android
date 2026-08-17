# Keep kotlinx.serialization models used for OpenCode API payloads.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class it.ferdiu.opencodewrapper.**$serializer { *; }
-keepclassmembers class it.ferdiu.opencodewrapper.** {
    *** Companion;
}
-keepclasseswithmembers class it.ferdiu.opencodewrapper.** {
    kotlinx.serialization.KSerializer serializer(...);
}
