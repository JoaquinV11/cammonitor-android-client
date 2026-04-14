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


############  IJKPlayer / FFmpeg JNI  ############
# Keep all IJK classes that are referenced from native code.
-keep class tv.danmaku.ijk.** { *; }
-dontwarn tv.danmaku.ijk.**

# (These are the most commonly reflected/linked classes)
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep class tv.danmaku.ijk.media.player.misc.** { *; }
-keep class tv.danmaku.ijk.media.player.IjkTimedText { *; }
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer$OnNativeInvokeListener { *; }
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer$OnControlMessageListener { *; }

############  GSYVideoPlayer Java layer  ##########
-keep class com.shuyu.gsyvideoplayer.** { *; }
-dontwarn com.shuyu.gsyvideoplayer.**

######## Moshi / Kotlin reflection (keep metadata & annotations) ########
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Moshi + Okio classes
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**
-keep class okio.** { *; }
-dontwarn okio.**

# Moshi DTOs added for the Cookie tab
-keep class com.example.camara.recordings.api.CookieDetection { *; }
-keep class com.example.camara.recordings.api.CookieListResponse { *; }

# @FromJson/@ToJson adapters
-keepclasseswithmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# DTOs used by Retrofit/Moshi (safe, minimal)
-keep class com.example.camara.recordings.api.Recording { *; }
-keep class com.example.camara.recordings.api.ListResponse { *; }

######## Retrofit comfort rules ########
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }