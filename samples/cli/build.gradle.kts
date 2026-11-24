// HJTK Ensemble 예제: 터미널에서 AI 팀 돌리기
//   ./gradlew :samples:cli:run --args="2026 디자인 공모전 비교표 만들어줘"
//   ./gradlew :samples:cli:run --args="--project 30 카페 창업 사업계획서"
//   ./gradlew :samples:cli:run --args="--demo"      (키 없이 가짜 AI로)
plugins { id("org.jetbrains.kotlin.jvm"); application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":ensemble")) }
application { mainClass.set("MainKt") }
tasks.named<JavaExec>("run") { jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8"); workingDir = rootProject.projectDir }
