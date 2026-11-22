plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ht.hjtk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ht.hjtk"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/hjtk-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("HJTK_KS_PASS") ?: ""
                keyAlias = "hjtk"
                keyPassword = System.getenv("HJTK_KS_PASS") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ks = rootProject.file("keystore/hjtk-release.jks")
            if (ks.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    // standard: Play 프로텍트 EFP(접근성 등 민감 권한 사이드로드 차단)에 걸리지 않는 기본 빌드
    // full: 화면 읽기(접근성) 포함 — 설치 시 Play 프로텍트 검사를 잠시 꺼야 함
    flavorDimensions += "dist"
    productFlavors {
        create("standard") { dimension = "dist" }
        create("full") { dimension = "dist"; versionNameSuffix = "-full" }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.md")
            // mailcap·javamail.* 는 지우면 안 된다: 메일 본문 인코딩(DataContentHandler)이 이 파일로 등록된다
            pickFirsts += setOf("META-INF/mailcap", "META-INF/mailcap.default", "META-INF/javamail.providers", "META-INF/javamail.default.providers", "META-INF/javamail.address.map", "META-INF/javamail.charset.map", "META-INF/mimetypes.default")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
                it.systemProperty("robolectric.dependency.repo.id", "gcs-central")
                it.maxHeapSize = "3g"
            }
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
