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
        print(f"[LifeLine] LoRa Mesh ACK packet observed: {raw_message.strip()}")
        return None

    parsed = parse_lora_message(raw_message)
    if parsed is None:
        print(f"[LifeLine] Ignored unrecognised message: {raw_message!r}")
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
                    f"[LifeLine] Duplicate SOS detected for message_id '{message_id}' "
                    f"via chain '{node_chain}' (first seen via '{entry['node_chain']}'). "
                    "Skipping ACK transmission."
                )
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
        f"[LifeLine] {parsed['node_id']} (chain: {node_chain}, msg_id: {message_id}) -> "
        f"{parsed['latitude']}, {parsed['longitude']} | {parsed['alert']}"
    )

    # Send ACK back over LoRa mesh if message_id is present
    if message_id:
        if radio is not None:
            # Brief delay so airwaves clear
            time.sleep(0.1)
            ack_msg = f"{COMMAND_CENTER_NODE_ID}: [ACK] : {message_id}"
            try:
                success = radio.send(ack_msg)
                if success:
                    with seen_lock:
                        if message_id in seen_message_ids:
                            seen_message_ids[message_id]["ack_sent"] = True
                    print(f"[LifeLine] Sent ACK back to mesh: '{ack_msg}'")
                else:
                    print(f"[LifeLine] Failed to send ACK: '{ack_msg}'")
            except Exception as e:
                print(f"[LifeLine] Error transmitting ACK: {e}")
        else:
            print(f"[LifeLine] Radio not available; recorded ACK event for message_id '{message_id}'")

    return parsed


def lora_listener():
    global active_radio
    try:
        radio = LoRa()
        active_radio = radio
        radio.start_receive()
        print(f"[LifeLine] LoRa receiver started (Command Center node ID: {COMMAND_CENTER_NODE_ID}).")

        while True:
            packet = radio.receive()

            if packet is not None:
                message, rssi, snr = packet

                full_message = message
                if "RSSI:" not in full_message.upper():
                    full_message += f"\nRSSI: {rssi} dBm"
                if "SNR:" not in full_message.upper():
                    full_message += f"\nSNR: {snr} dB"

                handle_lora_message(full_message, radio=radio)

            time.sleep(0.02)

    except Exception as exc:
        print(f"[LifeLine] LoRa listener stopped: {exc}")
        print("[LifeLine] Check SPI, wiring, antenna and Lora.py configuration.")


@app.route("/")
def index():
    return render_template("index.html")


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
