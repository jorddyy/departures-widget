# kotlinx.serialization: keep generated serializers for the cache and API models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.departures.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.departures.core.**$$serializer { *; }
