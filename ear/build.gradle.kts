plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

/** 서명 비밀번호: 환경 변수 HJTK_KS_PASS 또는 keystore/keystore.properties(저장소에 올리지 않음) */
fun ksPass(): String = System.getenv("HJTK_KS_PASS") ?: rootProject.file("keystore/keystore.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("password=") }?.substringAfter("=")?.trim() ?: ""

// HJTK 통화 귀 — 따로 설치하는 작은 앱(선택).
// Android는 통화 중 소리를 접근성 서비스에만 허락하고, Play 프로텍트는 접근성을 쓰는 앱 설치를 막는다.
// 그래서 그 권한만 이 작은 앱으로 떼어 냈다: 막혀도 HJTK 본체는 그대로 설치된다.
android {
    namespace = "com.ht.hjtk.ear"
    compileSdk = 35
    defaultConfig { applicationId = "com.ht.hjtk.ear"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/hjtk-release.jks")
            if (ks.exists() && ksPass().isNotEmpty()) { storeFile = ks; storePassword = ksPass(); keyAlias = "hjtk"; keyPassword = ksPass() }
        }
    }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")); if ((rootProject.file("keystore/hjtk-release.jks").exists() && ksPass().isNotEmpty())) signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { }
