-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ---------------- kotlinx.serialization ----------------
# The plugin generates $$serializer classes that are only reached reflectively.
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.landpoint.app.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.landpoint.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------- Room ----------------
# Entities are instantiated by generated code that resolves them by name.
-keep class com.landpoint.app.data.model.** { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---------------- osmdroid ----------------
# Tile sources and overlays are looked up reflectively; its own config is minimal.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ---------------- mapsforge (vector offline maps) ----------------
# The renderer resolves theme element handlers by class name out of the render
# theme XML, so R8 cannot see those uses and would strip them.
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**
# Desktop-only AWT backend referenced by shared mapsforge code. Never loaded on
# Android, but R8 still wants the symbols resolved.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.stream.**
-dontwarn org.xmlpull.**

# ---------------- Misc ----------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
