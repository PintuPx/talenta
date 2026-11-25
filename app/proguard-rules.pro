# kotlinx.serialization: keep generated serializers for backup DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.homebudget.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.homebudget.app.**$$serializer { *; }

# ML Kit text recognition: its components are found and created at run time (component registrars
# and class-keyed lookups). R8 full mode removed or merged classes they need, which made bill
# scanning crash in release builds only (NullPointerException in MlKitBillTextRecognizer).
# Keep ML Kit and its internal Play-services classes intact.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.firebase.components.** { *; }
