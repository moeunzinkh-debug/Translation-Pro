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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Rules required now that R8 minification is enabled for release builds.
# Retrofit / Moshi / OkHttp are reflection-heavy; without these the release
# APK crashes on the first API call ("Unable to create converter" / NPEs).
# ---------------------------------------------------------------------------

# Keep useful stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# --- Retrofit ---
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <1>
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
-dontwarn retrofit2.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Moshi (codegen + reflection) ---
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonQualifier @interface *
-keepclassmembers @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
    **[] values();
}
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep,allowobfuscation,allowshrinking @com.squareup.moshi.JsonClass class *
-keep class **JsonAdapter { <init>(...); *; }
-dontwarn com.squareup.moshi.**

# Our API DTOs are serialized by name - keep their fields intact.
-keep class com.example.data.api.** { *; }
-keep class com.example.data.model.** { *; }

# --- Kotlin reflection used by Moshi's KotlinJsonAdapterFactory ---
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlin.reflect.**

# --- AndroidX Security (EncryptedSharedPreferences / Tink) ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
