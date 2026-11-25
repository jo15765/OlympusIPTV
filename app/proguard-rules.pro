# Olympus-IPTV release shrink keeps.
# Skip slow multi-pass R8 optimization & identifier obfuscation over large libs (bcprov-jdk18on ~6,500 classes)
# while still shrinking unused code & resources and keeping crash stack traces readable.
-dontoptimize
-dontobfuscate

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

# OkHttp / Okio / Media3 / Coil / Cast ship their own official consumer ProGuard rules.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn androidx.media3.**
-dontwarn coil.**
-dontwarn com.google.android.gms.**

# BouncyCastle / SMB (keep only provider & cipher classes used by smbj so R8 strips unused crypto)
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.digests.** { *; }
-keep class org.bouncycastle.crypto.engines.** { *; }
-keep class org.bouncycastle.crypto.modes.** { *; }
-keep class org.bouncycastle.crypto.macs.** { *; }
-keep class org.bouncycastle.crypto.params.** { *; }
-dontwarn com.hierynomus.**
-keep class com.hierynomus.security.bc.** { *; }
-keep class com.hierynomus.smbj.auth.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }

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

