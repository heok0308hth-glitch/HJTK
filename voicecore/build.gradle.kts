// 음성 엔진 핵심(안드로이드 의존 없음): 토크나이저 · 한국어 정규화 · 목소리 지문 · 생성 루프.
// JVM에서 실제 ONNX 모델로 시험할 수 있게 분리했다. 앱은 onnxruntime-android, 시험은 데스크톱 onnxruntime을 쓴다.
plugins { id("org.jetbrains.kotlin.jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
tasks.withType<Test> { maxHeapSize = "4g"; systemProperty("cb.models", System.getProperty("cb.models") ?: "/home/claude/cb/onnx") }
