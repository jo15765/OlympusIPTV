import java.io.FileOutputStream
import java.util.Properties
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun localProperty(name: String): String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty(name).orEmpty().trim()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "")
    .replace("\r", "")

val watchmodeKey = localProperty("watchmode.apiKey")
val opensubtitlesKey = localProperty("opensubtitles.apiKey")
val mailgunKey = localProperty("mailgun.apiKey")
val mailgunDomain = localProperty("mailgun.domain")
val mailgunFrom = localProperty("mailgun.fromEmail")
val tmdbKey = localProperty("tmdb.apiKey")
val portalCloudUrl = localProperty("portal.cloudUrl")

fun signingValue(key: String): String {
    val file = File(System.getProperty("user.home"), "olympus-iptv-signing.txt")
    if (!file.exists()) return ""
    return file.readLines()
        .firstOrNull { it.substringBefore("=").trim() == key }
        ?.substringAfter("=", "")
        ?.trim()
        .orEmpty()
}

android {
    namespace = "com.olympus.iptv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.olympus.iptv"
        minSdk = 24
        targetSdk = 35
        versionCode = 93
        versionName = "0.2.92"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "WATCHMODE_API_KEY", "\"$watchmodeKey\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$opensubtitlesKey\"")
        buildConfigField("String", "MAILGUN_API_KEY", "\"$mailgunKey\"")
        buildConfigField("String", "MAILGUN_DOMAIN", "\"$mailgunDomain\"")
        buildConfigField("String", "MAILGUN_FROM_EMAIL", "\"$mailgunFrom\"")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
        buildConfigField("String", "PORTAL_CLOUD_URL", "\"$portalCloudUrl\"")
    }

    // Release stays a single APK. TV sticks only need ARM. Debug keeps every ABI so the emulator still runs.
    // Passwords stay in the home-folder signing file. They are not written into the project.

    signingConfigs {
        if (signingValue("keystore").isNotBlank()) {
            create("release") {
                storeFile = file(signingValue("keystore"))
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("alias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
    }

    // Never ship portal/data (e.g. store.json ~48MB) or Node portal trees inside the APK.
    packaging {
        resources {
            excludes += setOf(
                "**/portal/data/**",
                "**/store.json",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    androidResources {
        localeFilters += listOf("en")
        ignoreAssetsPattern = "!.svn:!.git:.*:!CVS:!thumbs.db:!picasa.ini:!*.scc:*~:!store.json"
    }

    sourceSets.getByName("main").assets.srcDir(
        project.layout.buildDirectory.get().asFile.resolve("generated/dashboardAssets")
    )
}

tasks.register("assertNoPortalDataInAssets") {
    dependsOn("syncPortalToAssets")
    group = "verification"
    description = "Fails if portal/data or store.json appears under app assets."
    val assetsDir = layout.projectDirectory.dir("src/main/assets")
    inputs.dir(assetsDir)
    doLast {
        val assets = assetsDir.asFile
        if (!assets.exists()) return@doLast
        val hits = assets.walkTopDown()
            .filter { it.isFile }
            .filter {
                it.name.equals("store.json", ignoreCase = true) ||
                    it.invariantSeparatorsPath.contains("/portal/data/")
            }
            .map { it.path }
            .toList()
        if (hits.isNotEmpty()) {
            error("Do not package portal data into assets: ${hits.joinToString()}")
        }
    }
}

val syncPortalToAssets = tasks.register("syncPortalToAssets") {
    group = "build"
    description = "Sync portal/public/index.html into app assets so standalone and embedded dashboards never diverge."
    val srcHtml = rootProject.layout.projectDirectory.file("portal/public/index.html")
    val dstHtml = layout.projectDirectory.file("src/main/assets/dashboard/index.html")
    inputs.file(srcHtml)
    outputs.file(dstHtml)
    onlyIf { srcHtml.asFile.exists() }
    doLast {
        dstHtml.asFile.parentFile.mkdirs()
        srcHtml.asFile.copyTo(dstHtml.asFile, overwrite = true)
    }
}

val gzipDashboardHtml = tasks.register("gzipDashboardHtml") {
    dependsOn(syncPortalToAssets)
    group = "build"
    description = "Pre-compress the web portal so the TV does not gzip it on each request."
    val html = layout.projectDirectory.file("src/main/assets/dashboard/index.html")
    val outDir = layout.buildDirectory.dir("generated/dashboardAssets/dashboard")
    inputs.file(html)
    outputs.dir(outDir)
    doLast {
        val destDir = outDir.get().asFile
        destDir.mkdirs()
        GZIPOutputStream(FileOutputStream(destDir.resolve("portal-page.pack"))).use { gzip ->
            html.asFile.inputStream().use { it.copyTo(gzip) }
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    val releaseName = android.defaultConfig.versionName
    val apkProvider = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val downloadsDir = File(System.getProperty("user.home"), "Downloads")
    doLast {
        val apk = apkProvider.get().asFile
        if (!apk.exists() || releaseName.isNullOrBlank()) return@doLast
        val dest = File(downloadsDir, "olympus-iptv-$releaseName.apk")
        apk.copyTo(dest, overwrite = true)
        println("Release APK: ${dest.absolutePath}")
    }
}

tasks.named("preBuild").configure {
    dependsOn("assertNoPortalDataInAssets")
    dependsOn(gzipDashboardHtml)
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(syncPortalToAssets)
        dependsOn(gzipDashboardHtml)
    }
    if (name.startsWith("lint") || name.startsWith("generate") && name.endsWith("Assets")) {
        mustRunAfter(syncPortalToAssets)
    }
}

val startPortal = tasks.register<Exec>("startPortal") {
    group = "application"
    description = "Start the home dashboard when port 8443 is not already open."
    workingDir = rootProject.layout.projectDirectory.dir("portal").asFile
    commandLine("cmd", "/c", "start-portal.cmd")
    isIgnoreExitValue = true
}

tasks.configureEach {
    if (name == "installDebug") {
        dependsOn(startPortal)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.smbj)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
