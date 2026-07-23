# VOX Picking — MVP

App Android (Kotlin) que guia um separador de pedidos em um CD por voz: fala
endereço e produto (TTS), ouve confirmação/divergência (STT), e registra cada
separação executada em banco local.

## Arquitetura

- **State machine** (`state/PickingStateMachine.kt`) — motor central. Define os
  estados do fluxo (anunciando endereço, aguardando confirmação, divergência,
  item concluído, etc.) e, pra cada estado, quais comandos de voz são aceitos
  (`comandosPermitidos`). Transições são puras (sem IO), fáceis de testar.
- **TTS** (`voice/TtsManager.kt`) — usa `android.speech.tts.TextToSpeech`
  nativo do Android (offline, pt-BR, sem custo/dependência extra). Suficiente
  pro MVP.
- **STT** (`voice/WhisperSttEngine.kt` + `cpp/whisper_jni.cpp`) — binding JNI
  pro [whisper.cpp](https://github.com/ggerganov/whisper.cpp), rodando o
  modelo localmente no device (sem enviar áudio pra nuvem). **Precisa do
  submódulo vendorizado** — ver abaixo. Sem ele, o build usa um stub que
  compila normalmente mas retorna transcrição vazia (permite testar o resto
  do app com os botões manuais da tela).
- **Room DB** (`data/db/`) — `pedidos`, `endereco_itens`,
  `separacoes_executadas` (histórico do que foi separado, com timestamp e se
  foi confirmado por voz) e `config` (configurações do app).
- **ViewModel** (`ui/PickingViewModel.kt`) — orquestra state machine + TTS +
  STT + persistência; a UI (Compose) só observa o estado atual.

## Setup do STT (whisper.cpp)

```bash
git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper.cpp
git submodule update --init --recursive
```

Baixe um modelo ggml (ex. `ggml-tiny.bin` ou `ggml-base.bin`) e coloque em
`app/src/main/assets/models/` (ignorado pelo git — baixar à parte em cada
máquina/CI). O CMake detecta o submódulo automaticamente e builda o `.so`
real; sem ele, cai no stub.

## Build

```bash
./gradlew assembleDebug
```

Requer Android SDK (`local.properties` já aponta pro SDK local) e NDK/CMake
instalados via SDK Manager (`ndk;<versão>` e `cmake;3.22.1`).

## Status do MVP

- [x] State machine com estados/transições/comandos permitidos
- [x] Parse do JSON de tarefa (mesmo formato de `docs/EP_2026_000142.json`)
- [x] Room DB (pedido, itens, separações, config)
- [x] TTS (Android nativo)
- [x] Skeleton JNI whisper.cpp (stub até vendorizar submódulo)
- [ ] Captura de áudio real (AudioRecord + VAD) alimentando o STT
- [ ] Tela de configurações (ler/escrever `ConfigEntity`)
- [ ] Testes unitários da state machine
