# R8 configuration.
#
# The release build minifies, which means the release APK is the one that can
# break in ways the debug build never will. Everything below exists because
# some part of the app reaches for a class by name at runtime, where R8 cannot
# see the reference.

# ---- kotlinx.serialization -------------------------------------------------
# The Xtream client reads untyped JsonElement trees, but Credentials and the
# backup file format are @Serializable and resolve their serializers
# reflectively through the generated Companion.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.iptv.player.**$$serializer { *; }
-keepclasseswithmembers class com.iptv.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.iptv.player.**
-keep class com.iptv.player.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Room ------------------------------------------------------------------
# Entities are constructed by generated code that R8 keeps, but the @Query
# projections (GuideChannel) are instantiated by name from the generated DAO.
-keep class com.iptv.player.data.db.** { *; }

# ---- WorkManager -----------------------------------------------------------
# RefreshWorker is instantiated by class name from the WorkManager database,
# including across app updates, so its constructor must survive.
-keep class com.iptv.player.work.RefreshWorker { public <init>(...); }

# ---- Media3 ----------------------------------------------------------------
# Renderers, extractors and data sources are looked up reflectively by
# DefaultRenderersFactory / DefaultMediaSourceFactory. Without this, HLS and
# DASH playback fails only in release builds — the classic "works in debug"
# report from a store tester.
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.dash.DashMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory { *; }
-dontwarn androidx.media3.**

# ---- OkHttp ----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Pawns bandwidth-sharing SDK -------------------------------------------
# The SDK's service is named as a string in AndroidManifest.xml and its DTOs
# cross a serialization boundary, neither of which R8 can see as a reference.
# Without these the feature fails only in release builds — and it fails as a
# service that will not start, which is easy to miss because the app itself
# keeps working perfectly.
-keep class com.pawns.sdk.** { *; }
-keep interface com.pawns.sdk.** { *; }
-dontwarn com.pawns.sdk.**

# ---- Coroutines ------------------------------------------------------------
-dontwarn kotlinx.coroutines.**

# Keep the line numbers in any crash the user reports back, while still
# obfuscating names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
