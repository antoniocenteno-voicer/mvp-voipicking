# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

VOX Picking — Android app (Kotlin) that voice-guides a warehouse picker (separador) through order-picking tasks in a distribution center (CD). It speaks the address/product to pick (TTS) and listens for confirmation or divergence (STT), persisting every executed pick locally. Package: `tech.voicer.voipicking`.

## Build / run

```bash
./gradlew assembleDebug          # full build incl. native (whisper.cpp JNI stub)
./gradlew :app:compileDebugKotlin # fast Kotlin-only compile check
./gradlew :app:installDebug       # install on connected device/emulator
```

There is no test suite yet (no test files exist under `app/src/test` or `app/src/androidTest` beyond the default scaffolding) — the README's "Status do MVP" lists unit tests for the state machine as a TODO. When adding tests, standard Gradle test tasks apply: `./gradlew :app:test`, `./gradlew :app:testDebugUnitTest --tests "tech.voicer.voipicking.state.SomeTest"`.

Requires: Android SDK (`local.properties` points at it, gitignored — regenerate with `sdk.dir=<path>` if missing), NDK + CMake 3.22.1 (for the native STT stub).

Toolchain (root `build.gradle.kts` / `gradle/wrapper/gradle-wrapper.properties`): Gradle 9.5.0, AGP 9.3.0, Kotlin 2.2.10, KSP 2.3.2. Compose is enabled via the `org.jetbrains.kotlin.plugin.compose` plugin (required since the Kotlin 2.x Compose compiler split from AGP).

## Architecture

The whole app is built around one idea: **a pure state machine decides what the picker is allowed to say/do at each moment**, and a thin ViewModel layer wires that machine to IO (voice, DB).

- **`state/PickingStateMachine.kt`** — the core. `PickingState` is a sealed class covering the picking flow: `Ocioso → TarefaCarregada → AnunciandoEndereco → AguardandoConfirmacaoEndereco → AnunciandoProduto → AguardandoConfirmacaoColeta → ItemConcluido → (next item or TarefaConcluida)`, plus `DivergenciaReportada` and `Erro` side branches. Transitions (`calcularProximoEstado`) are pure functions of `(state, event)` — no IO — which is what makes them easy to reason about/test in isolation. `comandosPermitidos(estado)` returns the `PickingCommand` set valid for the current state; this is the gate that stops out-of-context voice commands (e.g. "divergência" only makes sense while `AguardandoConfirmacaoColeta`). After `MAX_TENTATIVAS` (3) unrecognized attempts in a confirmation state, it transitions to `Erro`.
- **`state/PickingCommand.kt`** — maps free-form transcription text to a `PickingCommand` enum (`CONFIRMAR`/`REPETIR`/`DIVERGENCIA`/`CANCELAR`) via keyword-set matching (`reconhecer()`). This is where you'd extend the Portuguese vocabulary the picker can use.
- **`state/PickingEvent.kt`** — events fed into the machine, sourced from either STT output or UI taps.
- **`ui/PickingViewModel.kt`** — orchestrates: drives the state machine, triggers TTS on state entry (`falarEstadoAtual()`), persists each completed/diverged item via `PedidoRepository`, and exposes `estado: StateFlow<PickingState>` for Compose to observe. UI stays a dumb observer — all flow logic lives in the machine + ViewModel.
- **`voice/TtsManager.kt`** — wraps `android.speech.tts.TextToSpeech` (native Android engine, offline, pt-BR). Deliberate choice for the MVP: no external TTS dependency/cost.
- **`voice/SttEngine.kt` + `voice/WhisperSttEngine.kt` + `cpp/whisper_jni.cpp` + `cpp/CMakeLists.txt`** — STT runs on-device via whisper.cpp through JNI (audio never leaves the device). **The whisper.cpp submodule is not vendored yet** — `CMakeLists.txt` detects its absence and falls back to building a stub `.so` (`nativeCarregarModelo`/`nativeTranscrever` return empty/invalid results) so the rest of the app still compiles and is testable via the manual buttons in `MainActivity`. To wire up real STT: `git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper.cpp && git submodule update --init --recursive`, then drop a ggml model (e.g. `ggml-tiny.bin`) into `app/src/main/assets/models/` (gitignored, fetch per-machine/CI). Real audio capture (AudioRecord + VAD feeding `SttEngine.transcrever`) is not implemented yet — also a TODO.
- **`data/model/Pedido.kt`** — kotlinx.serialization data classes mirroring the picking-task JSON contract (see `docs/EP_ 2026_000142.json` for the canonical shape: `tarefa.centroDistribuicao`, `tarefa.pedido`, `tarefa.enderecos[]` each with `endereco` (setor/rua/box/altura/caixa + `enderecoFormatado` + `digitoVerificacao`) and `produto` (codigoInterno/nome/descricao/codigoBarras)). `PickingStateMachine` decodes this JSON directly when handling `PickingEvent.CarregarTarefa`.
- **`data/db/`** — Room. Four tables: `pedidos`, `endereco_itens`, `separacoes_executadas` (execution history — quantity picked, whether voice-confirmed, transcript, timestamp, status), `config`. `AppDatabase` is a manual singleton (`AppDatabase.obter(context)`), no DI framework in use.
- **`repository/PedidoRepository.kt`** — the only class that touches the DAOs; translates between `Tarefa`/`EnderecoItem` domain models and Room entities.

### Data flow for one pick

`MainActivity` builds `AppDatabase`, `PedidoRepository`, `TtsManager`, `WhisperSttEngine` manually (no DI framework — a `ViewModelProvider.Factory` closure wires them into `PickingViewModel`) → `PickingViewModel.carregarTarefa(json)` feeds the JSON into the state machine → on each state transition the ViewModel speaks the relevant prompt via TTS and, once an item reaches `ItemConcluido`, persists a `SeparacaoExecutadaEntity` through the repository before advancing to the next address or `TarefaConcluida`.

### Conventions

- Domain/business code (state machine, events, commands, DB entities, UI strings) is written in Portuguese, matching the domain (CD/separador/pedido terminology) — keep new code consistent with this rather than mixing English domain terms in.
- No DI framework (Hilt/Koin) — dependencies are constructed and passed by hand in `MainActivity`.
