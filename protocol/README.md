# muse-gadget-protocol

Kotlin/JVM port of the Muse Gadget Linux SDK protocol layer, so a Wear OS app can act as a Muse gadget.
Pure JVM (no Android classes); `org.json` is `compileOnly` because Android provides it.

| File | What it does | Python original |
|---|---|---|
| `noise/Proto.kt`, `Envelope.kt`, `Framing.kt` | protobuf wire helpers, service envelopes, chunked Noise frames | `noise/_proto.py`, `envelope.py`, `framing.py` |
| `noise/NoiseXX.kt`, `NoiseTransport.kt` | `Noise_XX_25519_AESGCM_SHA256`, encrypted request/response streams | `noise/noise_xx.py`, `transport.py` |
| `Pairing.kt` | BLE pairing v5 (P-256 ECDH, HKDF, AES-GCM records), community mode | `pairing.py` |
| `BleFraming.kt`, `BleSetup.kt` | BLE chunking and the setup command flow (hello, confirm, provision) | `ble_framing.py`, `ble_setup.py` |
| `Identity.kt` | `homelink-xxxxxx` / `MuseGadgetXXXXXX` naming | `identity.py` |
| `MuseApi.kt` | `fetch_vms`, device token refresh | `muse_api.py` |
| `LinkClient.kt` | Noise session, `link.register`, invokes, chat (also as a stream) | `link_client.py` |

Not ported: the BlueZ GATT server, Wi-Fi handling, the shell/file commands, and the service loop's token scheduling.

## Tests

```
export JAVA_HOME=.../jdk17/Contents/Home
gradle test
```

84 tests. Byte-exact checks against the reference: the Noise handshake and transport frames
(`tools/gen_noise_vectors.py` runs the Python SDK with pinned keys), and the pairing transcript,
key schedule and encrypted records (Meta's `link_pairing_v5.json`). The link session and BLE setup
tests run against an in-process fake Muse VM / fake BLE transport, ported from the Python tests.

## Not verified

- Against the real Muse service: nothing here has talked to a real VM or paired with the real Muse app.
- Android: X25519 uses the JDK's `XDH` provider. Its availability and behavior on Wear OS (API 34+) is untested.
- Whether the real VM streams chat responses in pieces (the transport supports it; tests simulate a server that does).

Apache-2.0, derived from Meta's code; see NOTICE.
