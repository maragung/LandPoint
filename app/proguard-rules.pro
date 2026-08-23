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

# ---------------- MapLibre and SQLCipher ----------------
# Nothing needed here on purpose. Both are native libraries whose C++ looks Java
# classes up by name, and both ship consumer rules inside their AAR that R8 reads
# automatically: MapLibre keeps the gson types, NativeMapOptions, RenderingStats
# and TileOperation, and every one of the other 70 classes its .so names carries
# @Keep; SQLCipher keeps net.zetetic.** native methods, constructors and the
# fields its JNI writes. Adding a blanket -keep for either would only stop R8
# shrinking code the libraries themselves declared removable.

# ---------------- Misc ----------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
