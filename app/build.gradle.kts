import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versão de build própria: cada assemble/install incrementa o PATCH e grava a hora (America/Sao_Paulo).
val versionFile = rootProject.file("version.properties")
val ver = Properties().apply { versionFile.inputStream().use { load(it) } }
val isBuild = gradle.startParameter.taskNames.any { it.contains("assemble", true) || it.contains("install", true) }
if (isBuild) {
    ver["patch"] = (ver.getProperty("patch").toInt() + 1).toString()
    ver["built"] = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    versionFile.outputStream().use { ver.store(it, null) }
}
val patch = ver.getProperty("patch").toInt()

// Chave fixa de release: sem ela o Android recusa instalar uma versão nova por cima da antiga.
// CI: RM_KEYSTORE + RM_KEYSTORE_PASSWORD. Local: ~/.android/renanmusic-release.jks + .properties.
val home = File(System.getProperty("user.home"), ".android")
val keystoreFile = System.getenv("RM_KEYSTORE")?.let(::File) ?: File(home, "renanmusic-release.jks")
val keystorePassword = System.getenv("RM_KEYSTORE_PASSWORD")
    ?: File(home, "renanmusic-release.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("storePassword") }
val hasReleaseKey = keystoreFile.exists() && keystorePassword != null

android {
    namespace = "com.renan.renanmusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.renan.renanmusic"
        minSdk = 33
        targetSdk = 35
        versionCode = maxOf(patch, 1)
        versionName = "${ver.getProperty("major")}.${ver.getProperty("minor")}.$patch"
        buildConfigField("String", "BUILD_TIME", "\"${ver.getProperty("built")}\"")
        // Repositório público de onde o app baixa as próprias atualizações (GitHub Releases).
        buildConfigField("String", "UPDATE_REPO", "\"renanrmsantos14/renanmusic\"")
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = "renanmusic"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all { it.systemProperty("roborazzi.test.record", "true") }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Prints de exemplo renderizados na JVM (./gradlew testDebugUnitTest)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
