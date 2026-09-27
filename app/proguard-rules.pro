# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── Moshi ───────────────────────────────────────────────────────────────────────
# Every DTO is @JsonClass(generateAdapter = true), so the KSP-generated
# `XxxJsonAdapter` is resolved by name at runtime. R8 cannot see that edge, so the
# API + model packages are kept verbatim — a few KB of APK in exchange for making
# an R8-only "Cannot serialize Kotlin type" crash impossible.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keep class com.example.data.api.** { *; }
-keep class com.example.data.model.** { *; }
-keep class com.example.data.repository.** { *; }

-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# ── Retrofit / OkHttp ───────────────────────────────────────────────────────────
# Both ship consumer rules, but the suspend function signatures and generic
# return types Retrofit reflects over are worth pinning down explicitly.
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

# ── Coroutines ──────────────────────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**

# ── OkHttp platform shims ───────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
