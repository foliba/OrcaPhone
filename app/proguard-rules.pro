# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.orcaphone.**$$serializer { *; }
-keepclassmembers class com.orcaphone.** { *** Companion; }
-keepclasseswithmembers class com.orcaphone.** { kotlinx.serialization.KSerializer serializer(...); }

# JTS uses reflection-free code, but keep it intact for safety
-keep class org.locationtech.jts.** { *; }
-dontwarn org.locationtech.jts.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JTS has optional AWT/Swing helpers that do not exist on Android
-dontwarn java.awt.**
-dontwarn javax.swing.**
