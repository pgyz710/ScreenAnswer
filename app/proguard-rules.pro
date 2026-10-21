# Keep OkHttp / Okio platform integration points.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit text recognition is loaded reflectively by the SDK.
-keep class com.google.mlkit.** { *; }
