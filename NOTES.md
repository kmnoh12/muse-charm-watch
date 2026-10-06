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
