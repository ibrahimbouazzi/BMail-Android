# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class tr.trtmail.android.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
