import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hjtk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hjtk.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/INDEX.LIST"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.android.mail)
    implementation(libs.android.activation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Robolectric needs a writable home for its android-all cache; the CI and the
// sandbox both run with a missing HOME, so both are pinned into build/.
val robolectricHome = project.layout.buildDirectory.dir("robolectric-home")
// Robolectric 4.14 downloads its android-all jar from Maven Central at test
// time. Some build environments rate-limit that host, so the jar is fetched
// once from a mirror into Robolectric's own local repository when missing.
val androidAllVersions = listOf("14-robolectric-10818077-i7", "15-robolectric-12650502-i7")

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    doFirst {
        robolectricHome.get().asFile.mkdirs()
        for (version in androidAllVersions) {
            val dir = robolectricHome.get().asFile.resolve(
                ".m2/repository/org/robolectric/android-all-instrumented/$version"
            )
            dir.mkdirs()
            val jar = dir.resolve("android-all-instrumented-$version.jar")
            if (!jar.exists() || jar.length() < 1024) {
                val tmp = dir.resolve("download.tmp")
                val url = URI.create(
                    "https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/" +
                        "android-all-instrumented/$version/android-all-instrumented-$version.jar"
                ).toURL()
                val input = url.openStream()
                try {
                    val output = tmp.outputStream()
                    try { input.copyTo(output) } finally { output.close() }
                } finally { input.close() }
                tmp.renameTo(jar)
            }
        }
    }
    systemProperty("user.home", robolectricHome.get().asFile.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}
