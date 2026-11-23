#!/usr/bin/env bash
# 4대 병목 설계서 §3.9 리뷰 게이트를 HJTK에 맞게 옮김: 금지 패턴이 들어오면 빌드를 막는다.
set -u; DIR=${1:-app/src/main}; fail=0
say(){ printf '%-44s %s\n' "$1" "$2"; }
grep -rqn "defineProperty(navigator" "$DIR" && { say "webdriver 위조" FAIL; fail=1; } || say "webdriver 위조" OK
grep -rqE "api/v0/chat/completion|x-ds-pow-response" "$DIR" --include=*.kt && { say "DeepSeek 웹 API 재구현" FAIL; fail=1; } || say "DeepSeek 웹 API 재구현" OK
grep -rqE "nth-child|css-[0-9a-z]{5,}" "$DIR" && { say "취약 셀렉터" FAIL; fail=1; } || say "취약 셀렉터" OK
# 3.0부터: 음성 합성(ONNX)·글자 인식(ML Kit)은 허용. 기기 안 '생성형 언어 모델'만 금지(원칙: AI를 넣지 않은 AI 비서)
grep -rqE "llama|gguf|LlmInference|tasks-genai|tensorflow-lite|tflite" "$DIR" && { say "온디바이스 LLM(금지)" FAIL; fail=1; } || say "온디바이스 LLM(금지)" OK
grep -rqE "//\s*(TODO|FIXME)|구현 예정" "$DIR" && { say "미구현 표시" FAIL; fail=1; } || say "미구현 표시" OK
say RESULT $([ $fail -eq 0 ] && echo PASS || echo BLOCK); exit $fail
