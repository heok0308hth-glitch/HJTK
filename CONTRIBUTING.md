# Contributing

Thanks for your interest in HJTK and the **Ensemble** SDK. Contributions of any size are welcome.

## Where things live

| Path | What |
|---|---|
| `ensemble/` | Kotlin multi-agent orchestration SDK (no Android dependency) — most contributions land here |
| `samples/cli/` | Terminal example (`--demo` runs without API keys) |
| `app/` | HJTK Android app (Jetpack Compose) built on top of the SDK |
| `voicecore/`, `ear/`, `shell/` | On-device voice engine, call companion app, APK shell for packaging generated web apps |

## Before you open a pull request

```bash
./gradlew :ensemble:test            # SDK — fast, pure JVM
./gradlew :app:testDebugUnitTest    # app + rendered screens (Robolectric)
./gradlew :samples:cli:run --args="--demo"
```

- Keep `ensemble/` free of Android imports.
- New engine behaviour needs a test. Use fake ports (`Ports.fn`) and a fake `clock`, so hours of work run in seconds and the result is deterministic.
- Ports must not throw. Return an empty `LlmReply` with a reason in `via`.
- Anything that leaves the device or server must go through `ApprovalGate` (`Effect.EXTERNAL` or above).

## Good first issues

- English prompt set (prompts are Korean today)
- More ports (Bedrock, Vertex AI, Azure OpenAI)
- A streaming variant of `LlmPort`
- Docs and examples in other languages

## Reporting bugs

Please include the version, which ports and models you used, and the event log (`onEvent`). For the Android app, paste the output of **Hub → AI 상태 점검 → 진단 복사** (AI status check → copy diagnostics).
