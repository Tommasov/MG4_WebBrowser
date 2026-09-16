import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing credentials live in keystore.properties (git-ignored), so the same stable
// key signs every build and users can always update in place. A fresh clone without that file
// still builds: the release type is simply left unsigned rather than failing to configure.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.tommasov.mg4browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tommasov.mg4browser"
        // The head unit is AAOS 9. Nothing here is expected to run anywhere older.
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.appcompat)

    // Not dependencies, only a version floor. AppCompat 1.7 brings kotlin-stdlib 1.8.22, and
    // as of Kotlin 1.8 that artifact absorbed everything that used to live in
    // kotlin-stdlib-jdk7 and -jdk8. Something further down the graph still asks for those two
    // by name at 1.6.21, and because they are separate modules Gradle has no reason to
    // upgrade them — so the same classes arrive twice and checkDuplicateClasses stops the
    // build. Pinning them to the stdlib's own version turns them into the empty shims they
    // became. Revisit if AppCompat ever moves off Kotlin 1.8.
    constraints {
        implementation(libs.kotlin.stdlib.jdk7)
        implementation(libs.kotlin.stdlib.jdk8)
    }
}
