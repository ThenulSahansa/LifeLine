from flask import Flask, jsonify, render_template, request
import threading
import time
import re
import json
import os
from datetime import datetime, timezone

try:
    from Lora import LoRa
except ImportError:
    from lora import LoRa

app = Flask(__name__)

COMMAND_CENTER_NODE_ID = os.environ.get("LIFELINE_NODE_ID", "000")

# Active radio reference
active_radio = None

# LoRa Receiver Connectivity & Hardware Status
lora_status = {
    "connected": False,
    "status": "INITIALIZING",
    "details": "Starting receiver...",
    "chip_version": None,
    "frequency_mhz": 433.0,
    "mode": "STARTING",
    "stats": {
        "rx_total": 0,
        "tx_total": 0,
        "crc_errors": 0,
        "duplicates_suppressed": 0,
        "last_rx_time": None,
        "last_tx_time": None,
    },
}
status_lock = threading.Lock()

# node_id -> latest node information
nodes = {}
nodes_lock = threading.Lock()

# message_id -> {"first_seen": iso, "node_id": id, "node_chain": str, "chains": [str], "ack_sent": bool}
seen_message_ids = {}
seen_lock = threading.Lock()

# Persistent incident history. Each node keeps its previous SOS messages.
HISTORY_FILE = os.path.join(os.path.dirname(__file__), "lifeline_history.json")
MAX_HISTORY_PER_NODE = 100

GPS_RE = re.compile(
    r"GPS:\s*(?P<lat>[+-]?\d+(?:\.\d+)?)\s*,\s*(?P<lon>[+-]?\d+(?:\.\d+)?)",
    re.IGNORECASE,
)
RSSI_RE = re.compile(r"RSSI:\s*(-?\d+(?:\.\d+)?)\s*dBm", re.IGNORECASE)
SNR_RE = re.compile(r"SNR:\s*(-?\d+(?:\.\d+)?)\s*dB", re.IGNORECASE)


def load_history():
    """Load saved incident history after a Flask restart."""
    if not os.path.exists(HISTORY_FILE):
        return

    try:
        with open(HISTORY_FILE, "r", encoding="utf-8") as f:
            saved = json.load(f)

        if not isinstance(saved, dict):
            return

        for node_id, history in saved.items():
            if not isinstance(history, list) or not history:
                continue

            cleaned = [item for item in history if isinstance(item, dict)]
            if not cleaned:
                continue

            latest = dict(cleaned[-1])
            latest["node_id"] = str(node_id)
            latest["node_name"] = f"LifeLine_Node_{node_id}"
            latest["history"] = cleaned[-MAX_HISTORY_PER_NODE:]
            # Marked state is intentionally not persisted as active SOS state.
            latest["marked"] = False
            nodes[str(node_id)] = latest

        print(f"[LifeLine] Loaded history for {len(nodes)} node(s).")
    except Exception as exc:
        print(f"[LifeLine] Could not load history: {exc}")


def save_history():
    """Persist the incident history atomically."""
    data = {
        node_id: node.get("history", [])[-MAX_HISTORY_PER_NODE:]
        for node_id, node in nodes.items()
    }

    temp_file = HISTORY_FILE + ".tmp"
    try:
        with open(temp_file, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(temp_file, HISTORY_FILE)
    except Exception as exc:
        print(f"[LifeLine] Could not save history: {exc}")
        try:
            if os.path.exists(temp_file):
                os.remove(temp_file)
        except OSError:
            pass


def parse_lora_message(raw_message: str):
    """Parse LifeLine SOS message supporting mesh chains and message IDs.

    Supported formats:
    - New:          <Node chain>: [SOS] : <message id> : <timestamp> | <alert> | GPS: <lat, lon>
    - Intermediate: <Node chain>: [SOS] : <timestamp> | <alert> | GPS: <lat, lon>
    - Legacy 1:     LifeLine_Node_<id>: [SOS] : <alert> | GPS: <lat, lon>
    - Legacy 2:     SOS : LifeLine_Node_<id>: <alert> | GPS: <lat, lon>
    """
    if not raw_message:
        return None

    # Ignore pure ACK messages from being processed as SOS incidents
    if "[ACK]" in raw_message and "[SOS]" not in raw_message and not re.search(r"\bSOS\b", raw_message):
        return None

    gps_match = GPS_RE.search(raw_message)
    if not gps_match:
        return None

    lat = float(gps_match.group("lat"))
    lon = float(gps_match.group("lon"))
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None

    # Locate [SOS] or SOS
    sos_match = re.search(r"\[SOS\]|(?<!\w)SOS(?!\w)", raw_message, re.IGNORECASE)
    if not sos_match:
        return None

    sos_start = sos_match.start()
    sos_end = sos_match.end()

    # Prefix contains the node chain (e.g. "002 003" or "LifeLine_Node_002")
    prefix = raw_message[:sos_start].strip().rstrip(":").strip()
    node_tokens = re.findall(r"\d+", prefix)

    # In legacy formats, node_id might be located after SOS: e.g. "SOS : LifeLine_Node_002 : ..."
    if not node_tokens:
        legacy_node_match = re.search(r"LifeLine_Node_(\d+)", raw_message, re.IGNORECASE)
        if legacy_node_match:
            origin_node_id = legacy_node_match.group(1)
            node_chain = f"LifeLine_Node_{origin_node_id}"
        else:
            origin_node_id = "001"
            node_chain = prefix or origin_node_id
    else:
        origin_node_id = node_tokens[0]
        node_chain = prefix or origin_node_id

    # Everything between SOS and GPS:
    gps_start = gps_match.start()
    payload = raw_message[sos_end:gps_start].strip().strip(":").strip().strip("|").strip()

    # If legacy format had "LifeLine_Node_xxx :" in payload, strip it
    payload = re.sub(r"^LifeLine_Node_\d+\s*:\s*", "", payload, flags=re.IGNORECASE).strip()

    # Extract message_id if present (Base85 string 16-80 chars without spaces followed by ':')
    m_id_match = re.match(r"^(?P<mid>[^\s:]{16,80})\s*:\s*(?P<rest>.*)$", payload, re.DOTALL)
    if m_id_match:
        message_id = m_id_match.group("mid").strip()
        rest = m_id_match.group("rest").strip()
    else:
        message_id = None
        rest = payload

    # Extract timestamp and alert
    if " | " in rest:
        part1, part2 = rest.split(" | ", 1)
        # Check if part1 looks like a timestamp (e.g. "23:10:26 18:14:15")
        if re.search(r"\d{2}:\d{2}:\d{2}", part1):
            timestamp = part1.strip()
            alert = part2.strip()
        else:
            timestamp = None
            alert = rest.strip()
    else:
        timestamp = None
        alert = rest.strip()

    # Clean up alert string
    alert = " ".join(alert.split()).strip(" |")
    alert = re.sub(r"^SOS\s*:\s*", "", alert, flags=re.IGNORECASE).strip()
    alert = alert or "SOS"

    rssi_match = RSSI_RE.search(raw_message)
    snr_match = SNR_RE.search(raw_message)

    return {
        "node_id": origin_node_id,
        "node_name": f"LifeLine_Node_{origin_node_id}",
        "node_chain": node_chain,
        "message_id": message_id,
        "timestamp": timestamp,
        "alert": alert,
        "latitude": lat,
        "longitude": lon,
        "rssi": float(rssi_match.group(1)) if rssi_match else None,
        "snr": float(snr_match.group(1)) if snr_match else None,
        "raw_message": raw_message.strip(),
        "received_at": datetime.now(timezone.utc).isoformat(),
    }


def handle_lora_message(raw_message: str, radio: LoRa = None):
    global active_radio
    if radio is None:
        radio = active_radio

    # Check for ACK packets traveling through mesh
    if "[ACK]" in raw_message and "[SOS]" not in raw_message and not re.search(r"\bSOS\b", raw_message):
        print(f"[LifeLine Mesh] Observed in-transit ACK packet: {raw_message.strip()}")
        return None

    parsed = parse_lora_message(raw_message)
    if parsed is None:
        print(f"[LifeLine] Ignored unrecognised/non-SOS message: {raw_message!r}")
        return None

    message_id = parsed.get("message_id")
    node_chain = parsed.get("node_chain", "")
    is_duplicate = False

    if message_id:
        with seen_lock:
            if message_id in seen_message_ids:
                is_duplicate = True
                entry = seen_message_ids[message_id]
                if node_chain and node_chain not in entry["chains"]:
                    entry["chains"].append(node_chain)
                print(
                    f"\n[LifeLine Mesh] DUPLICATE SOS DETECTED!\n"
                    f"    Message ID : {message_id}\n"
                    f"    Origin Node: {parsed['node_id']}\n"
                    f"    New Chain  : {node_chain}\n"
                    f"    First Chain: {entry['node_chain']}\n"
                    f"    Action     : Suppressing duplicate ACK transmission."
                )
                with status_lock:
                    lora_status["stats"]["duplicates_suppressed"] += 1
            else:
                seen_message_ids[message_id] = {
                    "first_seen": datetime.now(timezone.utc).isoformat(),
                    "node_id": parsed["node_id"],
                    "node_chain": node_chain,
                    "chains": [node_chain] if node_chain else [],
                    "ack_sent": False,
                }

    if is_duplicate:
        # Update node's alternate chains and latest RF stats, but do NOT send ACK or duplicate history
        with nodes_lock:
            node = nodes.get(parsed["node_id"])
            if node:
                alt = node.setdefault("alternate_chains", [])
                if node_chain and node_chain not in alt and node_chain != node.get("node_chain"):
                    alt.append(node_chain)
                if parsed.get("rssi") is not None:
                    node["rssi"] = parsed["rssi"]
                if parsed.get("snr") is not None:
                    node["snr"] = parsed["snr"]
                save_history()
        return parsed

    # New message / incident
    with nodes_lock:
        old = nodes.get(parsed["node_id"])

        # Every new valid SOS is an active incident needing attention.
        parsed["marked"] = False
        parsed["alternate_chains"] = []

        # Preserve and extend the previous SOS history for this node.
        history = list(old.get("history", [])) if old else []
        history.append({
            "alert": parsed["alert"],
            "latitude": parsed["latitude"],
            "longitude": parsed["longitude"],
            "rssi": parsed["rssi"],
            "snr": parsed["snr"],
            "raw_message": parsed["raw_message"],
            "received_at": parsed["received_at"],
            "message_id": message_id,
            "node_chain": node_chain,
            "timestamp": parsed.get("timestamp"),
        })
        parsed["history"] = history[-MAX_HISTORY_PER_NODE:]

        nodes[parsed["node_id"]] = parsed
        save_history()

    print(
        f"\n[LifeLine Incident] NEW SOS REGISTERED!\n"
        f"    Node ID    : {parsed['node_id']}\n"
        f"    Node Chain : {node_chain}\n"
        f"    Message ID : {message_id or 'None (Legacy)'}\n"
        f"    GPS        : {parsed['latitude']}, {parsed['longitude']}\n"
        f"    Alert Text : {parsed['alert']}\n"
        f"    Signal     : RSSI {parsed['rssi']} dBm | SNR {parsed['snr']} dB"
    )

    # Send ACK back over LoRa mesh if message_id is present
    if message_id:
        if radio is not None:
            # Brief delay so airwaves clear
            time.sleep(0.12)
            ack_msg = f"{COMMAND_CENTER_NODE_ID}: [ACK] : {message_id}"
            try:
                print(f"[LifeLine Mesh] Transmitting ACK packet: {ack_msg!r}")
                success = radio.send(ack_msg)
                if success:
                    with seen_lock:
                        if message_id in seen_message_ids:
                            seen_message_ids[message_id]["ack_sent"] = True
                    with status_lock:
                        lora_status["stats"]["tx_total"] += 1
                        lora_status["stats"]["last_tx_time"] = datetime.now(timezone.utc).isoformat()
                    print(f"[LifeLine Mesh] ACK successfully dispatched to mesh network: '{ack_msg}'")
                else:
                    print(f"[LifeLine Mesh] Failed to transmit ACK: '{ack_msg}'")
            except Exception as e:
                print(f"[LifeLine Mesh] Error transmitting ACK: {e}")
        else:
            print(f"[LifeLine Mesh] Radio not available; simulated ACK record for message_id '{message_id}'")

    return parsed


def lora_listener():
    """Background listener with self-healing reconnect and verbose terminal logs."""
    global active_radio
    last_heartbeat = time.time()

    while True:
        # Reconnection loop if radio is not active
        if active_radio is None:
            try:
                print("\n" + "=" * 65)
                print("[LifeLine LoRa] Initializing SX1278 transceiver on Raspberry Pi...")
                print(f"[LifeLine LoRa] Command Center Node ID: {COMMAND_CENTER_NODE_ID}")
                radio = LoRa()
                conn = radio.check_connectivity()

                with status_lock:
                    lora_status["connected"] = conn["connected"]
                    lora_status["status"] = conn["status"]
                    lora_status["details"] = conn["details"]
                    lora_status["chip_version"] = conn.get("chip_version")
                    lora_status["frequency_mhz"] = conn.get("frequency_mhz", 433.0)
                    lora_status["mode"] = conn.get("mode", "UNKNOWN")

                if conn["connected"]:
                    active_radio = radio
                    radio.start_receive()
                    print("[LifeLine LoRa] SX1278 is ONLINE and actively listening in continuous RX mode!")
                    print("=" * 65 + "\n")
                elif radio.is_mock:
                    active_radio = radio
                    print("[LifeLine LoRa] Running in SIMULATION mode (mock radio active).")
                    print("=" * 65 + "\n")
                else:
                    print(f"[LifeLine LoRa] [WARNING] Connection check failed: {conn['details']}")
                    print("[LifeLine LoRa] Retrying hardware detection in 3 seconds...")
                    time.sleep(3)
                    continue

            except Exception as exc:
                with status_lock:
                    lora_status["connected"] = False
                    lora_status["status"] = "OFFLINE"
                    lora_status["details"] = f"Error: {exc}"

                print(f"[LifeLine LoRa] [CRITICAL] Could not initialize SX1278: {exc}")
                print("[LifeLine LoRa] Please verify the Ra-02 wiring to the Raspberry Pi GPIO headers:")
                print("    Ra-02 VCC  -> Pi Pin 1  (3.3V)")
                print("    Ra-02 GND  -> Pi Pin 6  (GND)")
                print("    Ra-02 SCK  -> Pi Pin 23 (GPIO11 / SPI0 SCLK)")
                print("    Ra-02 MISO -> Pi Pin 21 (GPIO9  / SPI0 MISO)")
                print("    Ra-02 MOSI -> Pi Pin 19 (GPIO10 / SPI0 MOSI)")
                print("    Ra-02 NSS  -> Pi Pin 24 (GPIO8  / SPI0 CE0)")
                print("    Ra-02 RST  -> Pi Pin 22 (GPIO25)")
                print("    Ra-02 DIO0 -> Pi Pin 18 (GPIO24)")
                print("[LifeLine LoRa] Retrying in 3 seconds...\n")
                time.sleep(3)
                continue

        # Normal polling loop
        try:
            packet = active_radio.receive()

            if packet == ("CRC_ERROR", 0, 0):
                with status_lock:
                    lora_status["stats"]["crc_errors"] += 1
            elif packet is not None:
                message, rssi, snr = packet
                iso_now = datetime.now(timezone.utc).isoformat()
                with status_lock:
                    lora_status["stats"]["rx_total"] += 1
                    lora_status["stats"]["last_rx_time"] = iso_now

                full_message = message
                if "RSSI:" not in full_message.upper():
                    full_message += f"\nRSSI: {rssi:.1f} dBm"
                if "SNR:" not in full_message.upper():
                    full_message += f"\nSNR: {snr:.2f} dB"

                handle_lora_message(full_message, radio=active_radio)

            # Periodic heartbeat in terminal every 15 seconds
            now = time.time()
            if now - last_heartbeat > 15.0:
                last_heartbeat = now
                conn = active_radio.check_connectivity()
                with status_lock:
                    lora_status["connected"] = conn["connected"]
                    lora_status["status"] = conn["status"]
                    lora_status["details"] = conn["details"]
                    lora_status["mode"] = conn.get("mode", "UNKNOWN")
                    rx_cnt = lora_status["stats"]["rx_total"]
                    tx_cnt = lora_status["stats"]["tx_total"]
                    crc_cnt = lora_status["stats"]["crc_errors"]
                    dup_cnt = lora_status["stats"]["duplicates_suppressed"]

                print(
                    f"[LoRa RX Heartbeat] Status: {conn['status']} | "
                    f"Mode: {conn.get('mode', 'N/A')} | "
                    f"Freq: {conn.get('frequency_mhz', 433):.2f}MHz | "
                    f"RX: {rx_cnt} | TX(ACK): {tx_cnt} | Duplicates: {dup_cnt} | CRC Errors: {crc_cnt}"
                )

                if not conn["connected"] and not active_radio.is_mock:
                    print("[LifeLine LoRa] Lost SPI connection to SX1278! Entering reconnect sequence...")
                    active_radio = None

        except Exception as loop_exc:
            print(f"[LifeLine LoRa] Error in listener loop: {loop_exc}")
            active_radio = None
            time.sleep(2)

        time.sleep(0.02)


@app.route("/")
def index():
    return render_template("index.html")


@app.get("/api/status")
def api_status():
    """Return real-time LoRa module connectivity and stats."""
    with status_lock:
        return jsonify(lora_status)


@app.get("/api/nodes")
def api_nodes():
    with nodes_lock:
        return jsonify(list(nodes.values()))


@app.get("/api/acks")
def api_acks():
    with seen_lock:
        return jsonify(seen_message_ids)


@app.post("/api/test/incoming")
def api_test_incoming():
    data = request.get_json(silent=True) or {}
    message = data.get("message")
    if not message:
        return jsonify({"error": "Missing 'message' in request body"}), 400
    parsed = handle_lora_message(message)
    if parsed is None:
        return jsonify({"status": "ignored", "message": message}), 200
    return jsonify({"status": "processed", "data": parsed}), 200


@app.post("/api/nodes/<node_id>/mark")
def mark_node(node_id):
    with nodes_lock:
        node = nodes.get(node_id)
        if node is None:
            return jsonify({"error": "Node not found"}), 404

        node["marked"] = True
        return jsonify(node)


@app.delete("/api/nodes/<node_id>")
def delete_node(node_id):
    """Remove the current pin/node, but keep its saved SOS history."""
    with nodes_lock:
        node = nodes.pop(node_id, None)
        if node is None:
            return jsonify({"error": "Node not found"}), 404

        save_history()
        return jsonify({"success": True, "node_id": node_id})


@app.delete("/api/nodes/<node_id>/history")
def delete_node_history(node_id):
    """Clear previous SOS history while keeping the current SOS/pin."""
    with nodes_lock:
        node = nodes.get(node_id)
        if node is None:
            return jsonify({"error": "Node not found"}), 404

        node["history"] = []
        save_history()
        return jsonify(node)


@app.post("/api/nodes/<node_id>/unmark")
def unmark_node(node_id):
    with nodes_lock:
        node = nodes.get(node_id)
        if node is None:
            return jsonify({"error": "Node not found"}), 404

        node["marked"] = False
        return jsonify(node)


load_history()


if __name__ == "__main__":
    threading.Thread(target=lora_listener, daemon=True).start()
    app.run(host="0.0.0.0", port=5000, debug=False, threaded=True)
