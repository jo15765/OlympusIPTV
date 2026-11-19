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

android {
    namespace = "com.olympus.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.olympus.iptv"
        minSdk = 24
        targetSdk = 35
        versionCode = 39
        versionName = "0.2.38"
        buildConfigField("String", "WATCHMODE_API_KEY", "\"$watchmodeKey\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$opensubtitlesKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil)
    implementation(libs.smbj)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}
