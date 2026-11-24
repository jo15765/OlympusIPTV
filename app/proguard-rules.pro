# Olympus-IPTV release shrink/obfuscate keeps.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# App entry + ViewBinding
-keep class com.olympus.iptv.OlympusIptvApp { *; }
-keep class com.olympus.iptv.databinding.** { *; }
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater);
    public static *** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static *** bind(android.view.View);
}

# Activities / services / receivers referenced from the manifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep class com.olympus.iptv.ui.cast.CastOptionsProvider { *; }

# Gson models
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.olympus.iptv.data.** { *; }
-keep class com.olympus.iptv.remind.ShowReminder { *; }
-keep class com.olympus.iptv.playlist.TitleRatingsBundle { *; }
-keep class com.olympus.iptv.playlist.SourceRating { *; }
-keep class com.olympus.iptv.playlist.ReviewSnippet { *; }
-keep class com.olympus.iptv.OlympusIptvApp$FreshHold { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Media3
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Coil
-dontwarn coil.**
-keep class coil.** { *; }

# Cast / Play Services
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.common.** { *; }

# BouncyCastle / SMB (portal / shares)
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-keep class com.hierynomus.** { *; }

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class **$entries { <fields>; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Optional EL (pulled transitively by MBassador / smbj stack; unused on Android)
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper

