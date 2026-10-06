#!/usr/bin/env python3
"""Pair the Muse phone app with this Mac, standing in for the watch, and keep the credentials it hands over.

Why: the phone (Android 14+) negotiates BLE MTU 517 and its Muse app then tries a write longer than Android's
512-byte limit, which fails against an Android peripheral such as the watch (see docs/CONNECT.md). A Mac
peripheral negotiates a smaller MTU. The pairing logic is Meta's own SetupController from the Linux SDK
(it is independent of the Bluetooth stack); only the BLE transport is new (tools/mac-pairing/MacBleBridge.swift).

Run (after tools/mac-pairing/build.sh):
    uv run --with cryptography python tools/mac_pairing.py
The pairing and the identity end up in local/mac-pairing-state/ (gitignored). tools/push_pairing_to_watch.py
moves them onto the watch. The SDK token is read from local.properties and never printed.
"""
import json
import logging
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "reference/muse-gadget-sdk/linux/src"))
STATE = ROOT / "local/mac-pairing-state"
STATE.mkdir(parents=True, exist_ok=True)
os.environ["MUSEGADGET_STATE_DIR"] = str(STATE)

from musegadget import config, identity as identity_mod, muse_api, network  # noqa: E402
from musegadget.ble_framing import CHUNK_STAGGER_S, MAX_PACKET_BYTES  # noqa: E402
from musegadget.ble_setup import Credentials, ProvisionFailed, SetupController  # noqa: E402
from musegadget.pairing import PairingSession  # noqa: E402

APP = ROOT / "local/MuseBridge.app"
TIMEOUT_S = 15 * 60


def sdk_token() -> str | None:
    props = ROOT / "local.properties"
    if props.exists():
        for line in props.read_text().splitlines():
            if line.startswith("muse.sdk.token=") and line.split("=", 1)[1].strip():
                return line.split("=", 1)[1].strip()
    return None


def say(text: str) -> None:
    print(time.strftime("%H:%M:%S"), text, flush=True)


class Bridge:
    """The Swift peripheral, over two named pipes."""

    def __init__(self, local_name: str, on_line) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="musebridge-"))
        self._to_app, self._from_app = tmp / "to-app", tmp / "from-app"
        os.mkfifo(self._to_app)
        os.mkfifo(self._from_app)
        self._lock = threading.Lock()
        self._on_line = on_line
        self._proc = subprocess.Popen(
            ["open", "-n", "-W", "-a", str(APP), "--args", local_name, str(self._to_app), str(self._from_app),
             os.environ.get("MUSE_ADV_MODE", "both")]
        )
        threading.Thread(target=self._read, daemon=True).start()
        self._writer = open(self._to_app, "w")  # blocks until the app opens its end

    def _read(self) -> None:
        with open(self._from_app) as f:
            for line in f:
                self._on_line(line.rstrip("\n"))

    def notify(self, data: bytes) -> None:
        with self._lock:
            self._writer.write("N " + data.hex() + "\n")
            self._writer.flush()

    def close(self) -> None:
        try:
            self._writer.close()
        except OSError:
            pass
        self._proc.terminate()


class Transport:
    def __init__(self) -> None:
        self.bridge: Bridge | None = None
        self._max_update = 20

    def set_max_update(self, n: int) -> None:
        self._max_update = n

    def mtu(self) -> int:
        return min(self._max_update, MAX_PACKET_BYTES) + 3

    def send_packets(self, packets: list[bytes]) -> None:
        for packet in packets:
            self.bridge.notify(packet)
            time.sleep(CHUNK_STAGGER_S)

    def disconnect(self, delay: float) -> None:
        say("(the app asked to disconnect; the phone leaves on its own)")


class Net:
    is_online = staticmethod(network.is_online)

    @staticmethod
    def current_connection_entry() -> dict:
        return {"ssid": network.CURRENT_CONNECTION_LABEL, "rssi": -40, "secure": False}


def verify_and_save(credentials: Credentials, commit) -> None:
    """Same checks and storage as the Linux SDK's `musegadget pair`."""
    api_url = credentials.api_url if credentials.api_url.startswith("https://") else ""
    api_url_v2 = credentials.api_url_v2 if credentials.api_url_v2.startswith("https://") else ""
    vms, status = muse_api.fetch_vms_with_status(credentials.access_token, muse_api.api_root(api_url_v2))
    say(f"verify: fetch_vms status={status}, {len(vms)} VM(s)")
    if not vms:
        raise ProvisionFailed("auth_failed")
    record = {
        "access_token": credentials.access_token,
        "refresh_token": credentials.refresh_token,
        "token_type": "device",
        "username": credentials.username,
        "api_url": api_url,
        "api_url_v2": api_url_v2,
        "noise_host": credentials.noise_host,
        "access_token_saved_at": int(time.time()),
    }

    def save() -> bool:
        config.save_json(config.PAIRING_FILE, record)
        return True

    if not commit(save):
        raise ProvisionFailed("error_storage")


def main() -> int:
    # The SDK's own progress lines (RX action, TX status, ...) never include credentials.
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s", datefmt="%H:%M:%S")
    if not APP.exists():
        print("Build the bridge first: tools/mac-pairing/build.sh")
        return 1
    token = sdk_token()
    if token is None:
        print("No muse.sdk.token in local.properties.")
        return 1
    ident = identity_mod.load_or_create(STATE)
    done = threading.Event()
    transport = Transport()
    pairing = PairingSession(
        node_id=ident.node_id, device_id=ident.device_id, mac=ident.mac, firmware_version="0.1.0", sdk_token=token,
    )
    controller = SetupController(
        pairing=pairing, identity=ident, version="0.1.0", transport=transport, network=Net,
        provision=verify_and_save, on_complete=done.set,
    )
    controller.start()

    def on_line(line: str) -> None:
        kind, _, rest = line.partition(" ")
        if kind == "W":
            data = bytes.fromhex(rest)
            say(f"phone wrote {len(data)} bytes")
            controller.on_write(data)
        elif kind == "C":
            transport.set_max_update(int(rest))
            say(f"phone connected; max notification payload {rest} bytes")
        elif kind == "D":
            say("phone left")
            controller.on_disconnect()
        elif kind == "A":
            say(f"advertising as {ident.ble_name}: add it in the Muse app (Settings > Devices > +)")
        elif kind in ("S", "E"):
            say(f"bridge {kind}: {rest}  (S 3 = Bluetooth permission not granted, S 4 = Bluetooth off)")

    transport.bridge = Bridge(ident.ble_name, on_line)
    say(f"name {ident.ble_name}, node {ident.node_id}; waiting for the phone (up to {TIMEOUT_S // 60} min)")
    ok = done.wait(TIMEOUT_S)
    time.sleep(2)  # let the final status notifications go out
    transport.bridge.close()
    if not ok:
        say("timed out without a completed pairing")
        return 2
    say("PAIRED. Saved to local/mac-pairing-state/ (pairing.json, identity.json)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
