# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class net.daniellehmann.localchat.**$$serializer { *; }
-keepclassmembers class net.daniellehmann.localchat.** { *** Companion; }
-keepclasseswithmembers class net.daniellehmann.localchat.** { kotlinx.serialization.KSerializer serializer(...); }
# okhttp
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
