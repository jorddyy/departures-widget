plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI stamps every build with its run number, so a newer build always has a higher versionCode
// and the app can tell whether an update is available. Local builds are build 1.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val gitSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"
val updateBaseUrl = System.getenv("GITHUB_REPOSITORY")
    ?.let { "https://github.com/$it/releases/latest/download/" }
    .orEmpty()

// Android only installs an update if it is signed with the same key as the installed app.
// CI decodes the key from the repository secrets into SIGNING_KEYSTORE_PATH (see README
// "Signing"); without it, builds fall back to the throwaway debug key.
val signingKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "app.departures"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.departures"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        // Updates come from the latest release of the repository CI builds in; local builds
        // have no update source.
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("stable") {
                storeFile = signingKeystore
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}
