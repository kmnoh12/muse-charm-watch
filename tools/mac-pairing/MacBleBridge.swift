// Acts as the Muse gadget's BLE peripheral on a Mac, so the Muse phone app can pair with it.
// Used when the real device (the watch) cannot be paired from this phone: see docs/CONNECT.md.
//
// Runs as its own .app (macOS only grants Bluetooth to apps with their own usage description) and talks to
// the Python side over two named pipes, one line per message:
//   out  A                  advertising started
//   out  C <maxUpdateLen>   a phone subscribed to notifications (max notification payload in bytes)
//   out  D                  the phone unsubscribed / left
//   out  W <hex>            the phone wrote these bytes to the command characteristic
//   out  S <state>          Bluetooth state when it is not powered on; E <text> for errors
//   in   N <hex>            send these bytes as one notification
//
// Arguments: <local name> <pipe the Python side writes to> <pipe the Python side reads from> [both|name-only]
//
// UUIDs and properties follow the Linux SDK (ble_server.py).
import AppKit
import CoreBluetooth
import Foundation

let serviceUUID = CBUUID(string: "7FDD3D1C-38EA-46CF-8B46-314ECF5F240C")
let rxUUID = CBUUID(string: "4D593029-28A2-4A6E-A1F0-3C2D5E8F9B01")
let txUUID = CBUUID(string: "D75DC4CA-7B2B-4E9C-8F0A-1D2E3F4A5B6C")

var outHandle: FileHandle? = nil

func emit(_ line: String) {
    outHandle?.write((line + "\n").data(using: .utf8)!)
}

func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

func unhex(_ s: String) -> Data? {
    var out = Data()
    var i = s.startIndex
    while i < s.endIndex {
        guard let j = s.index(i, offsetBy: 2, limitedBy: s.endIndex), let b = UInt8(s[i..<j], radix: 16) else { return nil }
        out.append(b)
        i = j
    }
    return out
}

var bridgeInstance: Bridge? = nil

final class Bridge: NSObject, CBPeripheralManagerDelegate {
    private let name: String
    private let advertiseUUID: Bool
    private var manager: CBPeripheralManager!
    private var tx: CBMutableCharacteristic!
    private var pending: [Data] = []

    init(name: String, advertiseUUID: Bool) {
        self.name = name
        self.advertiseUUID = advertiseUUID
        super.init()
        bridgeInstance = self
        manager = CBPeripheralManager(delegate: self, queue: nil)
    }

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        if p.state == .poweredOn {
            let rx = CBMutableCharacteristic(type: rxUUID, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
            tx = CBMutableCharacteristic(type: txUUID, properties: [.read, .notify], value: nil, permissions: [.readable])
            let service = CBMutableService(type: serviceUUID, primary: true)
            service.characteristics = [rx, tx]
            p.add(service)
        } else {
            emit("S \(p.state.rawValue)") // 3 = unauthorized, 4 = powered off
        }
    }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error { emit("E add service: \(error.localizedDescription)"); return }
        // macOS may drop the local name when a 128-bit service UUID is advertised too; the mode argument lets us choose.
        var data: [String: Any] = [CBAdvertisementDataLocalNameKey: name]
        if advertiseUUID { data[CBAdvertisementDataServiceUUIDsKey] = [serviceUUID] }
        p.startAdvertising(data)
    }

    func peripheralManagerDidStartAdvertising(_ p: CBPeripheralManager, error: Error?) {
        emit(error == nil ? "A" : "E advertise: \(error!.localizedDescription)")
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        emit("C \(central.maximumUpdateValueLength)")
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        emit("D")
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests {
            if let value = r.value { emit("W " + hex(value)) }
        }
        if let first = requests.first { p.respond(to: first, withResult: .success) }
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        request.value = Data()
        p.respond(to: request, withResult: .success)
    }

    func peripheralManagerIsReady(toUpdateSubscribers p: CBPeripheralManager) { flush() }

    func send(_ data: Data) {
        pending.append(data)
        flush()
    }

    private func flush() {
        while let next = pending.first {
            if manager.updateValue(next, for: tx, onSubscribedCentrals: nil) { pending.removeFirst() } else { break }
        }
    }
}

let args = CommandLine.arguments
guard args.count >= 4 else {
    NSLog("usage: muse-ble-bridge <local name> <in pipe> <out pipe>")
    exit(2)
}
let inPath = args[2]
let outPath = args[3]

// Opening a pipe blocks until the other end is open, so do it off the main thread.
Thread.detachNewThread {
    outHandle = FileHandle(forWritingAtPath: outPath)
    guard let input = FileHandle(forReadingAtPath: inPath) else { exit(3) }
    let mode = args.count >= 5 ? args[4] : "both"
    DispatchQueue.main.async { _ = Bridge(name: args[1], advertiseUUID: mode != "name-only") }
    var buffer = Data()
    while true {
        let chunk = input.availableData
        if chunk.isEmpty { exit(0) } // the Python side closed its end
        buffer.append(chunk)
        while let newline = buffer.firstIndex(of: 10) {
            let line = String(data: buffer[buffer.startIndex..<newline], encoding: .utf8) ?? ""
            buffer.removeSubrange(buffer.startIndex...newline)
            if line.hasPrefix("N "), let data = unhex(String(line.dropFirst(2))) {
                DispatchQueue.main.async { bridgeInstance?.send(data) }
            }
        }
    }
}

// macOS leaves the local name out of BLE advertising for apps that count as background (a windowless accessory
// app does), so run as a regular foreground app.
let application = NSApplication.shared
application.setActivationPolicy(.regular)
application.activate(ignoringOtherApps: true)
application.run()
