# Proguard rules for Qianji-Lite-Bridge
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep data classes for Gson
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.qianji.litebridge.model.** { *; }

# NanoHTTPD
-keep class org.nanohttpd.** { *; }
