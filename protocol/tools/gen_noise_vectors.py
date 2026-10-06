"""Generate known-answer vectors from the reference Python SDK, for the Kotlin port.

Run:  uv run --with cryptography --with websockets --with ../../reference/muse-gadget-sdk/linux python tools/gen_noise_vectors.py > src/test/resources/noise_vectors.json
Deterministic: all keys and the chunk id are pinned below. Synthetic test data only.
"""
import json
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import x25519

from musegadget.noise import noise_xx, framing, NoiseXXInitiator, NoiseXXResponder, NoiseTransport, Header
from musegadget.noise import ApplicationResponse, BodyChunk, Reset, ResetCode, ServiceFrame
from musegadget.noise.transport import encode_response_envelope

def priv(n):  # 32 bytes, distinct per key
    return bytes([n] * 32)

KEYS = [priv(0x11), priv(0x22), priv(0x33), priv(0x44)]  # e_i, e_r, s_r, s_i
calls = iter(KEYS)

def fixed_keypair():
    raw = next(calls)
    k = x25519.X25519PrivateKey.from_private_bytes(raw)
    pub = k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return noise_xx._X25519KeyPair(private_key=k, public_key_bytes=pub)

noise_xx._generate_x25519_key_pair = fixed_keypair
CHUNK_ID = 0x0123456789ABCDEF
framing._random_int64 = lambda: CHUNK_ID

h = lambda b: bytes(b).hex()

init = NoiseXXInitiator(); init.initialize()
resp = NoiseXXResponder(payload=b"server-payload"); resp.initialize()
msg1 = init.write_message1()
msg2 = resp.read_message1_and_write_message2(msg1)
payload2 = init.read_message2(msg2)
msg3 = init.write_message3()
resp.read_message3(msg3)
hash_i, hash_r = init.handshake_hash(), resp.handshake_hash()
assert hash_i == hash_r
i_send, i_recv = init.split()
r_send, r_recv = resp.split()

tr = NoiseTransport(i_send, i_recv)
out = {"keys": [h(k) for k in KEYS], "chunk_id": CHUNK_ID,
       "msg1": h(msg1), "msg2": h(msg2), "msg3": h(msg3), "payload2": h(payload2),
       "handshake_hash": h(hash_i)}

out["client_frames"] = {}
f = tr.start_stream_request("POST", "/link-control")
out["client_frames"]["start_stream"] = {"stream_id": f.stream_id, "frames": [h(x) for x in f.frames]}
out["client_frames"]["body_chunk"] = [h(x) for x in tr.encrypt_body_chunk(f.stream_id, b"hello")]
g = tr.encrypt_http_request("POST", "/chat/stream", b'{"message":"hi"}', headers=[Header("x-app-id", "musegadget")])
out["client_frames"]["http_request"] = {"stream_id": g.stream_id, "frames": [h(x) for x in g.frames]}
out["client_frames"]["reset"] = [h(x) for x in tr.encrypt_reset(g.stream_id, reason="bye")]

def server_frames(frame):
    data = encode_response_envelope(frame)
    return [h(r_send.encrypt_with_ad(b"", x)) for x in framing.encode_noise_frames(data)]

big = bytes((i * 7) % 256 for i in range(70000))
out["server_frames"] = {
    "response": server_frames(ServiceFrame.response(1, ApplicationResponse(status=200, body=b"ok", end_body=True))),
    "body_chunk": server_frames(ServiceFrame.body_chunk(1, BodyChunk(data=b"chunk-data", end_body=False))),
    "reset": server_frames(ServiceFrame.reset(1, Reset(code=ResetCode.CANCELLED, reason="nope"))),
    "big_chunk": server_frames(ServiceFrame.body_chunk(1, BodyChunk(data=big, end_body=True))),
}
out["big_sha256"] = __import__("hashlib").sha256(big).hexdigest()
json.dump(out, sys.stdout, indent=1)
