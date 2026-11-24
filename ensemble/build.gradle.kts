// HJTK Ensemble — Kotlin 경량 멀티 에이전트 오케스트레이션 SDK(안드로이드 의존 없음).
// 지휘자(Conductor) · 회사 모드(Company) · 팀 창구(CrewDesk) · 칠판·배정 시장 · 승인 게이트 · HTTP 포트.
plugins { id("org.jetbrains.kotlin.jvm"); `java-library`; `maven-publish` }
group = "com.github.heok0308hth-glitch"
version = "0.1.0"
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; withSourcesJar() }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }; explicitApi = null }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // 안드로이드는 플랫폼에 org.json이 있다 → 앱 쪽에서 제외하고 쓴다
    api("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
publishing {
    publications { create<MavenPublication>("ensemble") { artifactId = "ensemble"; from(components["java"])
        pom { name.set("HJTK Ensemble"); description.set("Lightweight multi-agent orchestration SDK for Kotlin — conductor, company mode, shared board, load-aware dispatch, approval gate.")
            url.set("https://github.com/heok0308hth-glitch/HJTK"); licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } } } } }
}
