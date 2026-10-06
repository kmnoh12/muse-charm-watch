# muse-charm-watch

Goal: Muse Charm clone on Galaxy Watch8, for a personal demo (tag Alexandr Wang). Plan note: Obsidian `projects/갤럭시워치 뮤즈 참 클론 개발 계획.md`.

## SDK findings (2026-10-06, read from reference/muse-gadget-sdk, shallow clone)
- Repo is real. Apache-2.0, except the Jollybot avatar (esp32/avatar), which the README excludes.
- Every gadget needs an SDK token (gadgets.muse.ai) and the Gadget SDK Terms apply. NOT yet read by us: https://gadgets.muse.ai/sdk-terms (check the personal/non-commercial claim there).
- Linux SDK (python `musegadget`) flow: BLE pairing with the Muse phone app (P-256 ECDH, HKDF, AES-GCM) -> WebSocket /v1/noise -> Noise XX -> long-lived POST /link-control (JSON, u32-LE length prefix) -> device sends chat with POST /chat/stream.
- `LinkSession.send_chat(message, session_id)` posts a user message as the device; the POST response body is returned (collected in one go by the SDK, but the transport is frame-streamed, so chunked reading looks feasible; unverified).
- So a gadget can talk to Muse AND read the reply, text modality (`output_modality: "text"`). The watch does not strictly need a phone app if something speaks this protocol.
- Pairing is BLE-only through the Muse app. Linux SDK needs BlueZ (no macOS support).

## Open decision
How the watch reaches Muse: (A) bridge = musegadget on a Linux box + watch talks to the bridge; (B) port the protocol to Kotlin on the phone/watch.


## Voice protocol (found 2026-10-06 in the ESP32 reference, not in the Linux SDK)
Source: `esp32/components/muse/muse_chat.h`, `muse_chat_session.cpp`, `muse_chat_link.c`, `muse_chat_priv.h`.
The ESP32 voice gadgets run push-to-talk turns over the same Noise session the Link uses:
- **STT is done by the VM.** The device uploads a voice note: `POST /chat/stream`, JSON body
  `{"message":"","output_modality":"text","items":[{"type":"file","mime_type":"audio/wav","filename":"voice_note.wav","data_base64":"<WAV>"}]}`,
  16 kHz mono PCM16, streamed while recording (WAV header with unknown size 0xFFFFFFFF). Muse stops notes at 15 s.
  (`POST /api/voice/dictation` exists but "has no ASR behind it right now".)
- **Replies come on a separate stream:** one long-lived `POST /chat/subscribe` (body `{}`, `accept: application/x-ndjson`)
  emits `{"type":"event","seq":N,"event":...,"payload":{...}}`: `delta.message_start`, `delta.text_append` (`payload.text`),
  `delta.message_done` (`payload.display_text`), `message.assistant`, `agent.status` / `task.status` (busy).
  The `/chat/stream` response is only an ack with the user message id; replies carry `reply_to_message_id`.
  A turn has no end event: it settles when every message is done and nothing arrived for ~3 s.
- **TTS is the device's job:** "Muse doesn't speak gadget replies" (`start_tts` is where a TTS API of your own plugs in).
- Voice turns on the Link session send `x-app-id: hatch-web` (the Python SDK's text chat uses `musegadget`).
- Needs `muse_link_hatch_linked()`: the Link must be paired with the account through the Muse app.
Ported in `protocol/.../chat/` (VoiceNote, ChatEvents) and `LinkSession.subscribe()` / `openStreamRequest()`. Unverified against the real server.
