# VOX Picking — MVP

App Android (Kotlin) que guia um separador de pedidos em um CD por voz: fala
endereço e produto (TTS), ouve confirmação/divergência (STT), e registra cada
separação executada em banco local.

## Arquitetura

- **State machine** (`state/PickingStateMachine.kt`) — motor central. Define os
  estados do fluxo (anunciando endereço, aguardando confirmação, divergência,
  item concluído, etc.) e, pra cada estado, quais comandos de voz são aceitos
  (`comandosPermitidos`). Transições são puras (sem IO), fáceis de testar.
  Além das palavras-comando (`state/PickingCommand.kt`), usa
  `PortugueseNumberParser` (módulo `numberunderstanding`) pra aceitar o
  separador dizendo o **dígito verificador do endereço** (ex.: "três um sete")
  ou a **quantidade separada** como confirmação — sem precisar dizer
  "confirmado" explicitamente; se o número falado não bater, já registra
  divergência automaticamente.
- **TTS** (`voice/TtsManager.kt`) — usa `android.speech.tts.TextToSpeech`
  nativo do Android (offline, pt-BR, sem custo/dependência extra).
- **STT** (`voice/WhisperSttEngine.kt`) — usa o motor real do módulo
  `whisperlib` (whisper.cpp via JNI, rodando local no device, sem enviar áudio
  pra nuvem). `voice/ModelManager.kt` baixa o modelo ggml no primeiro uso;
  `voice/AudioRecorder.kt` grava mono 16kHz PCM e entrega float32 pro motor.
- **`whisperlib/`** e **`numberunderstanding/`** (módulos Android library,
  vendorizados de [mvp-whisper](../mvp-whisper)):
  - `whisperlib` expõe `WhisperContext` (API Kotlin) sobre o JNI do
    whisper.cpp (submódulo em `third_party/whisper.cpp`). O `initial_prompt`
    do decoder (`whisperlib/src/main/jni/whisper/jni.c`) foi estendido além
    do vocabulário de números pra incluir as palavras-comando do domínio
    (confirmado/repete/divergência/cancelar) — ver `PickingCommand.kt`.
  - `numberunderstanding` interpreta números falados em pt-BR
    (`PortugueseNumberParser`), independente de STT.
- **Room DB** (`data/db/`) — `pedidos`, `endereco_itens`,
  `separacoes_executadas` (histórico do que foi separado, com timestamp e se
  foi confirmado por voz) e `config` (configurações do app).
- **ViewModel** (`ui/PickingViewModel.kt`) — orquestra state machine + TTS +
  STT + persistência; a UI (Compose) observa `estado` (fluxo de separação) e
  `sttFase` (download do modelo → carregando motor → pronto → gravando →
  transcrevendo) e aciona `prepararStt()` / `iniciarGravacao()` /
  `pararGravacaoEAvaliar()` a partir do botão de microfone.

## Setup do submódulo whisper.cpp

```bash
git submodule update --init --recursive   # baixa third_party/whisper.cpp
```

O modelo (`ggml-base-q8_0.bin`) **não** fica no repositório: o app baixa
sozinho pra armazenamento privado no primeiro launch (precisa de internet na
primeira vez — `voice/ModelManager.kt`). Pra pré-carregar manualmente:

```bash
curl -L -o ggml-base-q8_0.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin
adb push ggml-base-q8_0.bin /data/data/tech.voicer.voipicking/files/models/ggml-base-q8_0.bin
```

## Build

```bash
./gradlew assembleDebug
```

Requer Android SDK (`local.properties` já aponta pro SDK local), NDK/CMake
instalados via SDK Manager (`ndk;<versão>` e `cmake;3.22.1`), e o submódulo
whisper.cpp inicializado (acima) — a 1ª build compila o motor nativo pra
`arm64-v8a`, `armeabi-v7a` e `x86_64` (demora mais; builds seguintes são
incrementais).

## Status do MVP

- [x] State machine com estados/transições/comandos permitidos
- [x] Parse do JSON de tarefa (mesmo formato de `docs/EP_2026_000142.json`)
- [x] Room DB (pedido, itens, separações, config)
- [x] TTS (Android nativo)
- [x] STT real via whisperlib/whisper.cpp + download de modelo + gravação de áudio
- [x] Confirmação por número falado (dígito verificador / quantidade) via `numberunderstanding`
- [ ] Tela de configurações (ler/escrever `ConfigEntity`)
- [ ] Testes unitários da state machine
- [ ] Validar em device real (latência do motor, qualidade do reconhecimento em ambiente de CD)
