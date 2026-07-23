# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

VOX Picking — Android app (Kotlin) that voice-guides a warehouse picker (separador) through order-picking tasks in a distribution center (CD). It speaks the address/product to pick (TTS) and listens for confirmation or divergence (STT), persisting every executed pick locally. Package: `tech.voicer.voipicking`.

## Build / run

```bash
git submodule update --init --recursive   # fetch third_party/whisper.cpp (required to build)
./gradlew assembleDebug                    # full build incl. native whisper.cpp (whisperlib module)
./gradlew :app:compileDebugKotlin          # fast Kotlin-only compile check
./gradlew :app:installDebug                # install on connected device/emulator
```

There is no test suite yet (no test files exist under `app/src/test` or `app/src/androidTest` beyond the default scaffolding) — the README's "Status do MVP" lists unit tests for the state machine as a TODO. When adding tests, standard Gradle test tasks apply: `./gradlew :app:test`, `./gradlew :app:testDebugUnitTest --tests "tech.voicer.voipicking.state.SomeTest"`.

Requires: Android SDK (`local.properties` points at it, gitignored — regenerate with `sdk.dir=<path>` if missing), NDK + CMake 3.22.1, the `third_party/whisper.cpp` submodule initialized. First native build compiles whisper.cpp for `arm64-v8a`/`armeabi-v7a`/`x86_64` and is slow; incremental builds are fast.

Toolchain (root `build.gradle.kts` / `gradle/wrapper/gradle-wrapper.properties`): Gradle 9.5.0, AGP 9.3.0, Kotlin 2.2.10, KSP 2.3.2. Compose is enabled via the `org.jetbrains.kotlin.plugin.compose` plugin. Room is pinned to 2.8.4 — earlier 2.6.x fails `kspDebugKotlin` with `unexpected jvm signature V` under this Kotlin/KSP combo; don't downgrade it.

## Modules

- **`app/`** — the application: state machine, ViewModel, Room DB, UI, and the thin `voice/` adapters that consume the two library modules below.
- **`whisperlib/`** and **`numberunderstanding/`** — vendored Android library modules, originally developed and tested standalone in the sibling `../mvp-whisper` project (see its README for the source-of-truth rationale/history) and copied here as-is except for the JNI prompt tweak noted below. Treat upstream changes there as the place to sync bugfixes from, not this repo.
  - `whisperlib` (`com.whispercpp.whisper`) wraps the `third_party/whisper.cpp` submodule via JNI (`whisperlib/src/main/jni/whisper/jni.c` + `CMakeLists.txt`) and exposes `WhisperContext.createContextFromFile(path)` / `context.transcribe(floatArray, language)` in Kotlin (`LibWhisper.kt`). It builds ARM dotprod/fp16 variants selected at runtime by CPU flags (`WhisperCpuConfig.kt`), plus a generic fallback.
  - `numberunderstanding` (`com.voicer.numberunderstanding`) is `PortugueseNumberParser` — parses spoken pt-BR numbers 0-100 (word or digit form) from any transcription text, no dependency on Whisper.
- **`third_party/whisper.cpp`** — git submodule, upstream C/C++ engine. Don't edit; if it needs patching, do it in the JNI glue instead.

## Architecture

The whole app is built around one idea: **a pure state machine decides what the picker is allowed to say/do at each moment**, and a thin ViewModel layer wires that machine to IO (voice, DB).

- **`state/PickingStateMachine.kt`** — the core. `PickingState` is a sealed class covering the picking flow: `Ocioso → TarefaCarregada → AnunciandoEndereco → AguardandoConfirmacaoEndereco → AnunciandoProduto → AguardandoConfirmacaoColeta → ItemConcluido → (next item or TarefaConcluida)`, plus `DivergenciaReportada` and `Erro` side branches. Transitions (`calcularProximoEstado`) are pure functions of `(state, event)` — no IO — which is what makes them easy to reason about/test in isolation. `comandosPermitidos(estado)` returns the `PickingCommand` set valid for the current state; this is the gate that stops out-of-context voice commands (e.g. "divergência" only makes sense while `AguardandoConfirmacaoColeta`). After `MAX_TENTATIVAS` (3) unrecognized attempts in a confirmation state, it transitions to `Erro`.
  - Beyond keyword commands, the machine also calls `PortugueseNumberParser` directly: in `AguardandoConfirmacaoEndereco` a spoken digit sequence matching `item.endereco.digitoVerificacao` counts as confirmation (picker reads the check digits, e.g. "três um sete" for "317"); in `AguardandoConfirmacaoColeta` a spoken number is compared to `item.quantidadeSolicitada` — match confirms the item, mismatch auto-files a `DivergenciaReportada` with that quantity. This means the picker can just state numbers instead of saying "confirmado" every time.
- **`state/PickingCommand.kt`** — maps free-form transcription text to a `PickingCommand` enum (`CONFIRMAR`/`REPETIR`/`DIVERGENCIA`/`CANCELAR`) via keyword-set matching (`reconhecer()`). This is where you'd extend the Portuguese vocabulary the picker can use — and it must stay in sync with the `DOMAIN_PROMPT_PT` decoder prompt in `whisperlib/src/main/jni/whisper/jni.c` (see below).
- **`state/PickingEvent.kt`** — events fed into the machine, sourced from either STT output or UI taps.
- **`ui/PickingViewModel.kt`** — orchestrates: drives the state machine, triggers TTS on state entry (`falarEstadoAtual()`), persists each completed/diverged item via `PedidoRepository`, and exposes `estado: StateFlow<PickingState>` for Compose to observe. It also owns the STT lifecycle independent of the picking flow — `SttFase` (`NAO_INICIALIZADO → BAIXANDO_MODELO → CARREGANDO_MOTOR → PRONTO → GRAVANDO/TRANSCREVENDO`) tracked in `sttFase: StateFlow<SttFase>` — via `prepararStt()` (downloads the model + loads the engine), `iniciarGravacao()`/`pararGravacaoEAvaliar()` (records, transcribes, then routes the text into whichever confirmation event the current `PickingState` expects). UI stays a dumb observer of both flows.
- **`voice/TtsManager.kt`** — wraps `android.speech.tts.TextToSpeech` (native Android engine, offline, pt-BR). Deliberate choice for the MVP: no external TTS dependency/cost.
- **`voice/SttEngine.kt`** — the app-level STT contract (`carregarModelo`/`transcrever`/`liberar`, all suspend, audio as mono float32 @ 16kHz). **`voice/WhisperSttEngine.kt`** implements it as a thin adapter over `whisperlib`'s `WhisperContext`. **`voice/ModelManager.kt`** downloads the multilingual `ggml-base-q8_0.bin` model (~140MB, q8_0 quantized) to app-private storage on first run — not bundled in the APK/repo, needs `INTERNET` permission (declared in the manifest). **`voice/AudioRecorder.kt`** captures mono 16kHz PCM via `AudioRecord` (`VOICE_RECOGNITION` source) and converts to the float32 buffer the engine expects — no resampling, whisper.cpp requires exactly this format.
  - The JNI layer primes whisper's decoder with `DOMAIN_PROMPT_PT` (`jni.c`) — a mix of the pt-BR number vocabulary and this domain's confirmation keywords — as `initial_prompt`. Without this, whisper's language-model prior favors common words over a short, out-of-context utterance (a clipped "dez" gets misheard as "desce"). If you add new `PickingCommand` vocabulary, add the matching words to this prompt too.
- **`data/model/Pedido.kt`** — kotlinx.serialization data classes mirroring the picking-task JSON contract (see `docs/EP_ 2026_000142.json` for the canonical shape: `tarefa.centroDistribuicao`, `tarefa.pedido`, `tarefa.enderecos[]` each with `endereco` (setor/rua/box/altura/caixa + `enderecoFormatado` + `digitoVerificacao`) and `produto` (codigoInterno/nome/descricao/codigoBarras)). `PickingStateMachine` decodes this JSON directly when handling `PickingEvent.CarregarTarefa`.
- **`data/db/`** — Room. Four tables: `pedidos`, `endereco_itens`, `separacoes_executadas` (execution history — quantity picked, whether voice-confirmed, transcript, timestamp, status), `config`. `AppDatabase` is a manual singleton (`AppDatabase.obter(context)`), no DI framework in use.
- **`repository/PedidoRepository.kt`** — the only class that touches the DAOs; translates between `Tarefa`/`EnderecoItem` domain models and Room entities.

### Data flow for one pick

`MainActivity` builds `AppDatabase`, `PedidoRepository`, `TtsManager`, `WhisperSttEngine` manually (no DI framework — a `ViewModelProvider.Factory` closure wires them into `PickingViewModel`, which extends `AndroidViewModel` for `Application` access) → a `LaunchedEffect` calls `viewModel.prepararStt()` once to download/load the Whisper model → `PickingViewModel.carregarTarefa(json)` feeds the JSON into the state machine → on each state transition the ViewModel speaks the relevant prompt via TTS; while waiting for a voice confirmation, the mic button drives `iniciarGravacao()`/`pararGravacaoEAvaliar()`, whose transcription result feeds back into the state machine → once an item reaches `ItemConcluido`, a `SeparacaoExecutadaEntity` is persisted through the repository before advancing to the next address or `TarefaConcluida`.

### Conventions

- Domain/business code (state machine, events, commands, DB entities, UI strings) is written in Portuguese, matching the domain (CD/separador/pedido terminology) — keep new code consistent with this rather than mixing English domain terms in. `whisperlib`/`numberunderstanding` keep their original English-ish package/API naming since they're vendored from a separate project.
- No DI framework (Hilt/Koin) — dependencies are constructed and passed by hand in `MainActivity`.
