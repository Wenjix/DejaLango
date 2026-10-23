// :engine — pure Kotlin. No android.* imports, no UI, no model runtimes.
// Audio frames in, HudState out. Everything device-specific lives behind the interfaces defined here.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No JVM toolchain pin: Android Studio's bundled JDK runs Gradle; we only fix the bytecode target.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Only the JsonElement tree API is used (no @Serializable code generation), so no compiler plugin.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnit()
    testLogging { events("failed"); showStandardStreams = false }
}
