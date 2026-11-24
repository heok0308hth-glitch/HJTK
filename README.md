<p align="center"><img src="docs/hero.png" alt="HJTK — 1~3시간 일하는 AI 회사" width="100%"></p>

<p align="center">
  <a href="https://github.com/heok0308hth-glitch/HJTK/actions/workflows/android-ci.yml"><img src="https://github.com/heok0308hth-glitch/HJTK/actions/workflows/android-ci.yml/badge.svg" alt="Android CI"></a>
  <img src="https://img.shields.io/badge/version-3.6.0-E44D2E?style=flat-square" alt="version">
  <img src="https://img.shields.io/badge/Android-9%2B-0E0E0E?style=flat-square&logo=android" alt="android">
  <img src="https://img.shields.io/badge/Kotlin-2.1-0E0E0E?style=flat-square&logo=kotlin" alt="kotlin">
  <img src="https://img.shields.io/badge/Jetpack_Compose-BOM_2025.05-0E0E0E?style=flat-square" alt="compose">
  <img src="https://img.shields.io/badge/tests-131_passing-0E0E0E?style=flat-square" alt="tests">
  <img src="https://img.shields.io/badge/license-MIT-0E0E0E?style=flat-square" alt="license">
</p>

> **Don't make tool, make innovation.**

**HJTK** is a personal Android assistant that ships **no AI model of its own**. It conducts the AIs you already use — Claude, ChatGPT, Gemini, Grok and DeepSeek — by signing into their web apps inside the app (no API key required). In **Project mode** they run like a small company for 30 minutes to 3 hours. A PM writes a charter with a project-specific rubric. The team works in sprints of tickets, where one ticket is one file. Every ticket is scored by a *different company's* AI the way a code review would score it, and anything below 8/10 goes back for revision. Stand-ups re-plan with the time that remains. A final QA and a project report close it out. The result is then handed to you in a usable form. If the team built an app, HJTK packages it **on the phone into an installable, signed APK**. If it wrote documents, you get one bound, printable edition. Progress is checkpointed, so it survives app restarts. Anything that leaves the phone waits for one tap on **실행**.

HJTK는 **생성형 모델을 한 바이트도 넣지 않은** 개인용 AI 비서입니다. 이미 쓰고 있는 Claude·ChatGPT·Gemini·Grok·DeepSeek에 앱 안에서 로그인해 **키 없이** 지휘합니다.

---

## 한눈에

| | |
|---|---|
| **문제** | AI마다 잘하는 게 다른데, 한 AI에게 다 맡기거나 사람이 창을 오가며 복사·붙여넣기를 한다. API 키는 돈이 들고, 화면을 찍어 누르는 에이전트는 느리고 비싸다. |
| **해결** | 로그인한 웹 AI 다섯을 하나의 팀으로 묶었습니다. 앱이 흐름(계획·배정·검수·승인)을 쥐고, AI에게서는 글만 받습니다. |
| **결과** | 프로젝트 모드에서는 AI 다섯이 30분~3시간 동안 회사처럼 일합니다. PM이 스프린트를 짜고, 다른 회사 AI가 채점해 기준 미달이면 다시 쓰게 하고, 남은 시간은 품질을 끌어올리는 데 씁니다. 결과는 여러 파일로 된 프로젝트 폴더와 보고서로 남습니다. 메일 발송, GitHub 업로드처럼 밖으로 나가는 일은 '실행' 한 번으로 승인합니다. |

<p align="center">
  <img src="docs/screens/project-time.png" width="24%" alt="프로젝트 시간 고르기">
  <img src="docs/screens/project.png" width="24%" alt="스프린트 진행">
  <img src="docs/screens/project-job.png" width="24%" alt="티켓 보드 · 산출물">
  <img src="docs/screens/deliver.png" width="24%" alt="결과물 받기 — 설치·미리보기·완성본">
</p>
<p align="center">
  <img src="docs/screens/chat.png" width="19%" alt="첫 화면">
  <img src="docs/screens/ensemble.png" width="19%" alt="작업 무대">
  <img src="docs/screens/job.png" width="19%" alt="작업 화면 · 칠판">
  <img src="docs/screens/models.png" width="19%" alt="모델 고르기">
  <img src="docs/screens/ai-call.png" width="19%" alt="AI 전화">
</p>

---

## 핵심 설계

### 1. 회사 모드 — AI가 1~3시간 동안 진짜로 일하게

한 번 묻고 한 번 받는 방식으로는 깊은 결과물이 나오지 않습니다. 그래서 회사가 일하는 방식을 그대로 옮겼습니다.

```mermaid
flowchart TB
    K[킥오프 · PM<br/>헌장: 결과물·독자·채점 기준·파일 계획·마일스톤] --> S
    subgraph S[스프린트 — 티켓 하나 = 파일 하나]
      direction LR
      W[담당 AI가 작성] --> R{다른 회사 AI가<br/>기준별로 채점}
      R -->|8점 미만 · 지적| W
      R -->|통과| M[(프로젝트 폴더)]
    end
    S --> U{스탠드업 · PM<br/>파일 현황·점수·칠판·남은 시간}
    U -->|새 티켓 · 개선 티켓| S
    U -->|시간 종료 또는 9점 이상| Q[최종 QA<br/>다른 회사 AI 둘]
    Q -->|미달 · 시간 있음| S
    Q --> P[[00_프로젝트_보고서.md<br/>+ 실제 작업 기록]]
```

- **헌장과 루브릭**: PM이 이 프로젝트만의 채점 기준 4~5개를 정합니다(예: 정확성 = 수치마다 출처, 실행성 = 바로 실행할 수 있는 계획). 모든 리뷰와 최종 QA는 이 기준으로 채점합니다.
- **티켓 = 파일**: 큰 문서는 장(章)별 파일로, 코드는 실제 경로로 나눕니다. 뒤 티켓은 `inputs`로 앞 파일을 읽고 이어 씁니다. 같은 파일을 한 스프린트에서 둘이 맡으면 자동으로 순서를 세웁니다.
- **코드 리뷰처럼 채점**: 작성자와 다른 회사의 AI가 기준별 점수와 '반드시 고칠 점'을 JSON으로 냅니다. 8점 미만이면 지적을 반영해 다시 쓰고(최대 2번), 점수가 가장 높은 판만 폴더에 합칩니다.
- **스탠드업**: PM이 파일 현황(글자 수·점수·리뷰 지적), 칠판, 결정 기록, 남은 시간을 보고 다음 스프린트를 짭니다. 순서는 ① 없는 파일 ② 점수 낮은 파일 개선 ③ 깊이 더하기입니다. 모든 파일이 9점을 넘고 더 할 가치가 없을 때만 일찍 끝내고, 아니면 시간이 다 될 때까지 다듬습니다.
- **시간 예산**: 30분·1시간·2시간·3시간 중에서 고릅니다. 마무리(최종 QA·보고서)에 쓸 시간은 미리 떼어 둡니다.
- **오래 버티기**: 티켓이 끝날 때마다 상태를 저장하므로, 앱이 꺼져도 끝난 일은 다시 하지 않고 이어 갑니다. 화면이 꺼져도 CPU가 잠들지 않게 하고, 폰이 뜨거워지면 티켓 사이에 쉬면서 동시 작업 수를 줄입니다.
- **보고서**: 보고서 본문은 AI가 쓰지만, 작업 기록(스프린트별 평균 점수, 티켓·담당·리뷰어·점수 표, 진행 시간, 기여, 토큰)은 앱이 실제 기록으로 붙입니다.

### 2. 결과물 전달 — 폰 안에서 APK까지

만들기만 하고 끝나면 의미가 없습니다. 작업이 끝나면 사용자는 **바로 쓸 수 있는 것**을 받습니다.

```mermaid
flowchart LR
    A[app/index.html<br/>팀이 만든 웹 앱] --> S{실행 검사<br/>보이지 않는 창에서 열어<br/>콘솔 오류 수집}
    S -->|오류| F[고치기 티켓<br/>오류 난 파일 담당에게] --> S
    S -->|오류 0| K[셸 APK 6KB<br/>+ 웹 앱 파일]
    K --> M[바이너리 매니페스트 패치<br/>패키지·앱 이름]
    M --> I[아이콘 새로 그림] --> G[apksig 서명<br/>기기 Keystore 키] --> R[[설치 가능한 APK]]
    D[장별 문서 .md] --> B[[목차 있는 완성본 HTML<br/>+ 합본 MD]]
    R & B --> Z[[프로젝트 ZIP · 다운로드/HJTK · 대화 카드]]
```

- **기기 안 APK 포장기 `ApkForge`**: 셸 앱은 WebView 하나뿐인 6KB짜리 앱입니다(`:shell` 모듈). 빌드할 때 HJTK 안에 서명 없이 들어갑니다. 폰에서는 다음 순서로 포장합니다. ① `assets/www/`에 웹 앱 파일을 넣고 ② 컴파일된 바이너리 매니페스트의 문자열 풀에서 같은 길이로 잡아 둔 패키지·앱 이름 자리표시를 그 자리에 덮어쓰고 ③ 런처 아이콘을 앱 이름 첫 글자로 다시 그린 뒤 ④ `apksig`로 v2·v3 서명합니다. 서명 키는 기기 Keystore에서 만들고 밖으로 나가지 않습니다. 컴퓨터도 서버도 필요 없습니다.
- **실행 검사**: APK로 만들기 전에 앱을 보이지 않는 창에서 실제로 열어 콘솔 오류와 빈 화면을 잡습니다. 오류가 있으면 오류 난 파일을 고치는 티켓이 자동으로 생깁니다(최대 2번).
- **완성본**: 장별 마크다운을 목차가 있는 HTML 한 권으로 묶습니다. 인쇄하면 그대로 PDF가 됩니다.
- **받는 곳**: 대화의 '결과물 카드'에 설치·바로 실행·열기·공유 버튼이 있고, 같은 파일이 `다운로드/HJTK/프로젝트 이름/`에도 저장됩니다.
- 검증: JVM 테스트가 실제 셸 APK를 포장·서명한 뒤 `apksig` 검증, `aapt2`로 패키지와 한글 앱 이름 파싱, `zipalign -c 4`까지 통과하는지 확인합니다.

### 3. AI 상태 점검 — 일을 안 하는 건지, 못 하는 건지

<p align="center"><img src="docs/screens/ai-health.png" width="32%" alt="AI 상태 점검"></p>

- 작업을 시작할 때 대화에 **누가 팀에 들어왔고 누가 왜 빠졌는지**를 먼저 보여 줍니다(예: `빠짐 Grok(로그인 안 됨)`).
- 허브 → AI 상태: 다섯 AI 각각의 연결 경로, 최근 성공·실패, 마지막 실패 이유(로그인 풀림 · 보안 확인 · 입력창 못 찾음 · 답을 못 읽음 · 사용량 한도), 쉬는 중인지를 보여 줍니다.
- '모두 점검'을 누르면 각 AI에 실제 경로로 `1+1`을 묻습니다. 실패하면 화면 구조 진단(입력창·보내기·답 영역을 찾았는지, 로그인·보안 확인 문구가 보이는지)을 남기고, '진단 복사'로 보낼 수 있습니다.
- 프로젝트 보고서에는 AI별로 끝낸 일과 못 한 일, 그 이유를 표로 남깁니다.

### 4. 합주(Ensemble) — AI끼리 실제로 협업하게

가장 잘하는 AI 하나에게 일이 몰리지 않도록, 배정을 **입찰**로 바꿨습니다.

```mermaid
flowchart LR
    U([사용자의 목표]) --> C{{센터<br/>설계·검수만}}
    C -->|할 일 분해| M[배정 시장<br/>능력·실적 − 지금 맡은 일]
    M --> A1[Gemini<br/>최신 조사]
    M --> A2[ChatGPT<br/>분석]
    M --> A3[DeepSeek<br/>표 정리]
    M --> A4[Grok<br/>반응 조사]
    A1 & A2 & A3 & A4 <-->|사실·결정·주의·질문| B[(칠판)]
    A1 -.교차 확인.-> A2
    B --> G{센터: 빠진 일?}
    G -->|보강·재배정| M
    G -->|충분| W[통합<br/>센터 아닌 AI] --> R[센터 검수] --> F[[결과물 · GitHub]]
```

- **배정 시장 `Dispatch`**: 할 일마다 `입찰가 = 능력(실제 성공률 반영)/10 − 0.22 × 지금 맡은 일 + 지휘자 지명 가산`. 팀이 3명 이상이면 센터는 할 일을 받지 않습니다.
- **칠판 `Board`**: 팀원은 결과 끝에 `[칠판] 사실/결정/주의/질문`을 남깁니다. 뒤에 시작하는 모든 팀원은 이걸 읽고 일하므로, 의존 관계로 이어지지 않은 AI끼리도 서로 압니다.
- **교차 확인**: 결과가 나오면 쉬고 있는 *다른 회사* AI가 병렬로 짧게 확인합니다. 본 흐름은 기다리지 않습니다.
- **자율 보강**: 센터가 결과와 칠판을 대조해 빠진 일을 직접 추가하고, 실패한 일은 다른 AI에게 다시 맡깁니다.
- **현실적인 성적표 `AiProfiles` + `Scoreboard`**: 회사별 장단점(웹 사용량 한도, 형식 준수, 한국어 뉘앙스)과 실제 성공률·속도·한도를 합칩니다. "사용량 한도" 문구를 감지하면 안내된 시간만큼 그 AI를 쉬게 하고 즉시 교체합니다.
- **멈춤의 정확성**: 일시정지하면 새 요청이 0건이고, 중지하면 진행 중인 요청까지 취소하고 사이트의 '중지' 버튼도 누릅니다. 둘 다 단위 테스트로 검증했습니다.

### 5. 키 없는 웹 AI

- 각 AI 사이트를 앱 안 WebView로 열고, 얇은 어댑터(`assets/webai.js`)가 입력창·보내기·답 영역을 찾습니다.
- **보내기 직전에 화면의 기존 답에 표시**해 두고, 표시 없는 답만 이번 답으로 읽습니다. 새 대화에서 이전 답을 잘못 읽는 일을 구조적으로 막습니다.
- 구글 계정 하나로 Claude·ChatGPT·Grok의 'Google로 계속'을 대신 눌러 한 번에 연결합니다.
- DeepSeek은 페이지 자신의 스트림을 지나가며 읽는 방식(P0), 화면(P1), 서버 기록(P2)의 세 경로를 대조해 받습니다.

### 6. 기기 안 AI 전화

- Chatterbox 다국어 ONNX를 기기에서 돌려 **사용자 본인의 목소리**로 통화합니다. 핵심 엔진은 순수 JVM 모듈 `voicecore`로 분리해, 파이썬 기준 구현과 같은 소리 토큰이 나오는지 실제 모델로 시험합니다.
- 통화 첫마디를 늦추지 않으려고 맞장구('네.')와 짧은 구절 단위 합성을 겹칩니다.
- 안전장치: 모든 AI 통화는 첫마디에 'AI 비서'임을 밝히고, 긴급번호는 차단합니다. 하루 5통, 같은 번호는 10분 간격으로 제한합니다.

### 7. 승인 게이트

- 밖으로 나가는 명령(메일, 공유, 삭제, GitHub 업로드)은 정규화한 인자의 **SHA-256 다이제스트에 묶인 1회용 승인 토큰**으로만 실행됩니다. 승인한 뒤 인자가 바뀌면 실행되지 않습니다.
- 승인 문장은 모델이 아니라 템플릿이 씁니다.

### 8. 발열 예보 조율기

- Android의 열 여유 예보와 배터리 온도로 **뜨거워지기 전에** 감속합니다. 동시 작업 수를 줄이고, 화면 뒤에 숨은 웹 페이지의 애니메이션을 얼리고, 무거운 일은 미룹니다.

### 9. GitHub 업로드

- "깃허브에 올려줘"라고 하면 결과물을 **커밋 한 번**으로 올립니다(Git Data API: blob → tree → commit → ref).
- 저장소가 없으면 비공개로 새로 만듭니다.
- 키스토어, `.env`, 키 파일, `build/`는 자동으로 제외합니다.
- 결과 글 속 코드 블록은 실제 파일로 풀어서 올립니다.

---

## 아키텍처

```mermaid
flowchart TB
    subgraph UI[ui — Jetpack Compose · 디자인 시스템 '먹과 인장']
      Chat[대화·작업 무대] --- Hub[허브·연결] --- Call[전화]
    end
    subgraph Agent[agent]
      JR[JobRunner<br/>사건 → 대화·알림]
    end
    subgraph Core[core — 안드로이드 의존 없음, JVM 테스트]
      Comp[Company · 회사 모드] --- Cond[Conductor] --- Ens[Board · Dispatch · CrewDesk]
      Cmd[명령어 사전 150+] --- Aegis[승인 게이트]
      AEP[AEP-1 봉투·스트림 파서] --- GH[GitHubPush · CodeFiles]
    end
    subgraph Engine[engine]
      Web[WebAi ×4 · DeepSeekWeb] --- Api[API 경로·선택]
      Th[Thermal] --- Score[Scoreboard]
    end
    VC[(voicecore<br/>Chatterbox ONNX)]
    Ear[(ear — 통화 귀<br/>별도 앱)]
    UI --> Agent --> Core
    Core --> Engine
    Call --> VC
    Call -. 서명 권한 방송 .- Ear
```

```
app/src/main/java/com/ht/hjtk/
  core/forge/  기기 안 APK 포장기(바이너리 매니페스트 패치 · apksig 서명) · 완성본 묶기(마크다운 → HTML)
  core/        순수 Kotlin — Company(회사 모드), Conductor(지휘자), CrewDesk(팀 호출·교체·멈춤), Ensemble(칠판·배정), 명령어 사전, 승인 게이트, GitHubPush
  engine/      웹 AI(WebView) · DeepSeek · 계정 하나로 · 발열 조율 · 성적표 · Gmail 웹 발송 · 숨은 브라우저 검색
  agent/       JobRunner — 작업 수명, 일시정지·중지, 대화 라우팅
  tools/       명령 → 안드로이드 동작(웹·메일·캘린더·파일·GitHub…)
  call/        기본 전화 앱 · AI 통화 루프
  ui/          Compose 화면 · StageView(작업 무대)
voicecore/     음성 엔진(순수 JVM) — 토크나이저, 생성 루프, 한국어 읽기
shell/         APK 포장용 셸(WebView 하나, 6KB) — 빌드 때 HJTK 자산으로 들어간다
ear/           통화 귀(접근성) — 본체 설치가 Play 프로텍트에 막히지 않게 분리
```

Kotlin 약 15,000줄 · 모듈 3개 · 명령어 150개 이상.

---

## 검증

| 종류 | 내용 |
|---|---|
| APK 포장기 | 실제 셸 APK로 포장 → 서명 → `apksig` 검증(v2) · `aapt2`로 패키지·한글 앱 이름 확인 · `zipalign -c 4` · 자리표시를 넘는 이름은 거부. |
| 회사 모드 시뮬레이션 | 가짜 시계로 '2시간 프로젝트'를 몇 초 만에 끝까지 돌림. 스프린트 반복, 반려 → 수정, 예산 대부분 사용 후 마무리, 품질 9점 이상이면 일찍 끝남, 중지 → 앱 재시작 → 끝난 티켓을 다시 하지 않고 이어감. 이 시험이 실제 버그 3개(티켓 중복으로 인한 교착, 스프린트 1 이중 실행, 의존 작업이 앞 작업을 기다리지 않던 경쟁 상태)를 잡아냈습니다. |
| 단위 테스트 | 지휘자 전체 흐름을 가짜 AI 팀으로 끝까지 돌림(병렬 실행, 한도 감지 → 교체, 막힌 검색 → AI 직접 조사). 일을 한 AI에게 몰아줘도 고르게 나뉘는지, 일시정지 중 새 요청 0건, 중지 시 진행 중 요청 취소, GitHub 단일 커밋·비밀 파일 제외, 칠판 파싱. |
| 화면 렌더 테스트 | Robolectric + Roborazzi로 44개 화면을 실제 크기(393dp)로 렌더해 눈으로 검수. |
| 음성 엔진 | 실제 ONNX 모델로 파이썬 기준 구현과 소리 토큰이 정확히 같은지 대조(탐욕 선택). |
| 회귀 방지 | Android ICU 정규식이 거부하는 패턴을 소스 전체에서 찾아내는 검사. |

```bash
./gradlew :app:testDebugUnitTest :voicecore:test     # 단위·화면 테스트
./gradlew :app:assembleRelease                       # 릴리스 APK
```

GitHub Actions가 푸시마다 같은 테스트를 돌리고 디버그 APK를 만들어 둡니다(Actions → 실행 → Artifacts).

릴리스 서명: `keystore/hjtk-release.jks`를 두고 `HJTK_KS_PASS` 환경 변수 또는 `keystore/keystore.properties`(`password=…`)를 설정하세요. 둘 다 저장소에 올리지 않습니다. 없으면 서명 없이 빌드됩니다.

> 개인 생체 정보인 '내 목소리' 지문(`assets/voice/me.htvp`)은 공개 저장소에서 뺐습니다. 이 파일 없이 빌드하면 앱에서 목소리를 직접 등록해 쓰면 됩니다.

---

## 버전 기록

| 버전 | 핵심 |
|---|---|
| **3.6** | 결과물 전달 — 만든 웹 앱을 폰에서 서명된 APK로 포장(실행 검사 → 오류 수정 → 설치), 문서는 목차 있는 완성본 HTML, 프로젝트 ZIP, 다운로드 폴더·결과물 카드 · AI 상태 점검(실패 이유·실제 점검·화면 진단) |
| 3.5 | 회사 모드 — PM·헌장·루브릭, 스프린트·티켓, 다른 회사 AI의 리뷰 채점과 반려·수정, 스탠드업, 최종 QA, 보고서, 30분~3시간, 체크포인트 이어하기 |
| 3.4 | 합주 — 배정 시장, 칠판, 교차 확인, 자율 보강 · 일시정지·중지 즉시 반영 · 새 대화의 이전 답 버그 수정 · GitHub 업로드 |
| 3.3 | 지휘자(센터) · 작업 무대 애니메이션 · + 옆 모델 칩 · 사용량 한도 시 자동 교체 · Gmail 웹 실제 발송 |
| 3.2 | 계정 하나로(구글·네이버) · 모델·노력·창의성 고르기 · 통화 귀를 별도 앱으로 분리 |
| 3.1 | 키 없는 AI 로그인(Claude·ChatGPT·Gemini·Grok) · 발열 예보 조율기 · 다크 모드 기본 |
| 3.0 | 기기 안 AI 전화(내 목소리 · Chatterbox ONNX) · 화면 글자 지도로 토큰 70~85% 절감 |
| 2.x | AI 다섯 회사 · 루틴 · 백업 · R8과 폰트 서브셋으로 APK 26MB → 5.4MB |
| 1.x | AEP-1 봉투 · DeepSeek 스트림 읽기 · 승인 게이트 · 해시 체인 작업 기록 |

---

## 정직한 한계

- AI 웹 사이트가 화면 구조를 바꾸면 어댑터를 고쳐야 할 수 있습니다. 바뀐 경우를 대비해 일반 규칙으로 입력창·답을 찾는 예비 경로를 두었습니다.
- 웹 자동화는 각 서비스 약관과 충돌할 수 있습니다. 본인 계정과 본인 세션을 쓰는 **개인 도구**로 설계했고, 요청 빈도를 낮게 유지합니다.
- 1~3시간 동안 일하면 웹 AI의 사용량 한도에 걸릴 수 있습니다. 한도에 걸린 AI는 안내된 시간만큼 쉬게 하고 다른 AI로 넘깁니다. 모두 한도면 진행이 느려집니다.
- 폰에서 만든 APK는 기기 키로 서명되므로, 처음 설치할 때 'HJTK에서 앱 설치 허용'을 켜야 합니다. Play 프로텍트가 검사 화면을 띄울 수 있습니다.
- 만든 앱은 웹 기술(HTML·CSS·JS)로 동작합니다. 카메라·알림 같은 기기 기능이 깊이 필요한 앱은 아직 만들 수 없습니다.
- 실기기 검증은 사용자 기기에서 버전마다 진행하고 있습니다. 빌드 환경에는 기기가 없어서, 단위·화면 테스트로 대신 검증합니다.

## 사용한 것

- [Chatterbox Multilingual](https://huggingface.co/onnx-community) (MIT) · [ONNX Runtime](https://onnxruntime.ai) (MIT) · ML Kit 텍스트 인식(Google 약관)
- [Pretendard](https://github.com/orioncactus/pretendard) (SIL OFL 1.1) · Jetpack Compose · Robolectric · Roborazzi

## 만든 사람

**허태혁 (Heo Taehyeok)**

[MIT License](LICENSE)
