from flask import Flask, jsonify, render_template
import threading
import time
import re
import json
import os
from datetime import datetime, timezone

from lora import LoRa

app = Flask(__name__)

# node_id -> latest node information
nodes = {}
nodes_lock = threading.Lock()

# Persistent incident history. Each node keeps its previous SOS messages.
HISTORY_FILE = os.path.join(os.path.dirname(__file__), "lifeline_history.json")
MAX_HISTORY_PER_NODE = 100

NODE_RE = re.compile(r"\bLifeLine_Node_(?P<node_id>\d+)\b", re.IGNORECASE)

RECORD_RE = re.compile(
    r"LifeLine_Node_(?P<node_id>\d+)\s*:\s*"
    r"(?:SOS\s*:\s*)?(?P<alert>.*?)\s*\|\s*"
    r"GPS:\s*(?P<lat>[+-]?\d+(?:\.\d+)?)\s*,\s*"
    r"(?P<lon>[+-]?\d+(?:\.\d+)?)",
    re.IGNORECASE | re.DOTALL,
)

OLD_RECORD_RE = re.compile(
    r"SOS\s*:\s*LifeLine_Node_(?P<node_id>\d+)\s*:\s*"
    r"(?P<alert>.*?)\s*\|\s*"
    r"GPS:\s*(?P<lat>[+-]?\d+(?:\.\d+)?)\s*,\s*"
    r"(?P<lon>[+-]?\d+(?:\.\d+)?)",
    re.IGNORECASE | re.DOTALL,
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
    """Parse only a complete LifeLine record."""
    matches = list(RECORD_RE.finditer(raw_message))
    matches += list(OLD_RECORD_RE.finditer(raw_message))

    if not matches:
        return None

    match = max(matches, key=lambda m: m.start())

    lat = float(match.group("lat"))
    lon = float(match.group("lon"))

    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None

    rssi_match = RSSI_RE.search(raw_message)
    snr_match = SNR_RE.search(raw_message)

    alert = " ".join(match.group("alert").split()).strip(" |")
    alert = re.sub(r"^SOS\s*:\s*", "", alert, flags=re.IGNORECASE).strip()
    alert = alert or "SOS"

    return {
        "node_id": match.group("node_id"),
        "node_name": f"LifeLine_Node_{match.group('node_id')}",
        "alert": alert,
        "latitude": lat,
        "longitude": lon,
        "rssi": float(rssi_match.group(1)) if rssi_match else None,
        "snr": float(snr_match.group(1)) if snr_match else None,
        "raw_message": raw_message.strip(),
        "received_at": datetime.now(timezone.utc).isoformat(),
    }


def handle_lora_message(raw_message: str):
    parsed = parse_lora_message(raw_message)
    if parsed is None:
        print(f"[LifeLine] Ignored unrecognised message: {raw_message!r}")
        return

    with nodes_lock:
        old = nodes.get(parsed["node_id"])

        # Every new valid SOS is a NEW incident that needs attention.
        # Therefore a previously green/marked node becomes red again.
        parsed["marked"] = False

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
        })
        parsed["history"] = history[-MAX_HISTORY_PER_NODE:]

        nodes[parsed["node_id"]] = parsed
        save_history()

    print(
        f"[LifeLine] {parsed['node_id']} -> "
        f"{parsed['latitude']}, {parsed['longitude']} | {parsed['alert']}"
    )


def lora_listener():
    try:
        radio = LoRa()
        radio.start_receive()
        print("[LifeLine] LoRa receiver started.")

        while True:
            packet = radio.receive()

            if packet is not None:
                message, rssi, snr = packet

                full_message = message
                if "RSSI:" not in full_message.upper():
                    full_message += f"\nRSSI: {rssi} dBm"
                if "SNR:" not in full_message.upper():
                    full_message += f"\nSNR: {snr} dB"

                handle_lora_message(full_message)

            time.sleep(0.02)

    except Exception as exc:
        print(f"[LifeLine] LoRa listener stopped: {exc}")
        print("[LifeLine] Check SPI, wiring, antenna and lora.py configuration.")


@app.route("/")
def index():
    return render_template("index.html")


@app.get("/api/nodes")
def api_nodes():
    with nodes_lock:
        return jsonify(list(nodes.values()))


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
