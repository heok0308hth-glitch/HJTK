// HT 3D 커널(안드로이드 의존 없음): HT-Scene(JSON) → 메시 → 모디파이어 → glTF(.glb)·OBJ, 소프트웨어 렌더러.
// AI가 쓰는 장면 글과 사람이 만지는 화면이 같은 데이터를 공유한다. JVM에서 시험하고 Khronos 검사기로 확인한다.
plugins { id("org.jetbrains.kotlin.jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    compileOnly("org.json:json:20240303")          // 안드로이드는 플랫폼에 있다
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.withType<Test> { systemProperty("mesh.out", rootProject.layout.buildDirectory.dir("mesh-out").get().asFile.absolutePath) }
