// :app — Android application. Compose bench UI, foreground service, adapters, renderers, flavors.
// AGP 9.x has built-in Kotlin support, so org.jetbrains.kotlin.android is NOT applied here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// The LiteRT-LM adapter is compiled only when -PwithLiteRtLm is passed. Until the Day-2 spike has
// verified the adapter against the current litertlm-android API, the default build uses the fake
// model and still exercises every other layer. See docs/ARCHITECTURE.md §5.3.
val withLiteRtLm = project.hasProperty("withLiteRtLm")

android {
    namespace = "dev.dejalango.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.dejalango"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "WITH_LITERTLM", withLiteRtLm.toString())
    }

    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            isDefault = true
        }
        // Horizon OS (Quest / Meta VR Glasses). The Meta Horizon Android Studio plugin can regenerate this
        // flavor; the manifest overlay lives in src/metavr/AndroidManifest.xml.
        create("metavr") {
            dimension = "device"
            applicationIdSuffix = ".metavr"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // INTERNET supports the local HUD server in both build types (see manifest).
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Keep the Silero VAD and prompt assets uncompressed for fast mmap/reads.
    }

    androidResources {
        noCompress += listOf("onnx", "litertlm")
    }

    sourceSets {
        getByName("main") {
            // The adapter lives in its own source root so the default build never sees it.
            // AppGraph instantiates it by class name, guarded by BuildConfig.WITH_LITERTLM.
            if (withLiteRtLm) {
                kotlin.srcDir("src/litertlm/kotlin")
            }
        }
    }
}

// AGP 9 built-in Kotlin exposes the `kotlin` extension (KotlinAndroidProjectExtension) without the
// kotlin-android plugin. If this block fails to resolve on your AGP, delete it: compileOptions above
// already pins Java 17 and AGP derives the Kotlin jvmTarget from it.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.onnxruntime.android)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)
    if (withLiteRtLm) {
        implementation(libs.litertlm.android)
    }

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
