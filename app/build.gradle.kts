import java.util.Properties

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
val sendgridKey = localProperty("sendgrid.apiKey")
val sendgridFrom = localProperty("sendgrid.fromEmail")
val tmdbKey = localProperty("tmdb.apiKey")

android {
    namespace = "com.olympus.iptv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.olympus.iptv"
        minSdk = 24
        targetSdk = 35
        versionCode = 76
        versionName = "0.2.75"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "WATCHMODE_API_KEY", "\"$watchmodeKey\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$opensubtitlesKey\"")
        buildConfigField("String", "SENDGRID_API_KEY", "\"$sendgridKey\"")
        buildConfigField("String", "SENDGRID_FROM_EMAIL", "\"$sendgridFrom\"")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
        ignoreAssetsPattern = "!.svn:!.git:.*:!CVS:!thumbs.db:!picasa.ini:!*.scc:*~:!store.json"
    }
}

tasks.register("assertNoPortalDataInAssets") {
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

tasks.named("preBuild").configure { dependsOn("assertNoPortalDataInAssets") }

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
    implementation(libs.smbj)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
