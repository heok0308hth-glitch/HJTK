plugins { id("com.android.application") }

// HJTK가 만든 웹 앱을 기기 안에서 '설치 가능한 APK'로 포장할 때 쓰는 껍데기(셸).
// 서명하지 않은 채로 HJTK 자산에 들어가고, 폰에서 www/ 내용·패키지 이름·앱 이름·아이콘을 바꿔 끼운 뒤 서명한다.
// 패키지·이름은 같은 길이의 자리표시로 두어, 바이너리 매니페스트를 다시 짜지 않고 그 자리에 덮어쓸 수 있게 했다.
android {
    namespace = "com.ht.shell"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ht.made.app00000000"      // ApkForge.PKG_SLOT과 같아야 한다
        minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { }
