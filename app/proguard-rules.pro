# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# =========================================================================
# FLUXDNS HARDENING: ProGuard / R8 Rules protecting JNI boundaries
# =========================================================================

# Keep all class names and member signatures that contain native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Explicitly preserve the entire native bridge class in your package structure
-keep class com.example.service.NativeEngine { *; }
-keep class com.example.service.FluxDnsEngine { *; }
-keep class com.example.fluxdns.NativeEngine { *; }

# Keep JVM static callbacks called from Rust/native side
-keepclassmembers class * {
    @kotlin.jvm.JvmStatic <methods>;
}

