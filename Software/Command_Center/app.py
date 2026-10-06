from flask import Flask, jsonify, render_template
import threading
import time
import re
from datetime import datetime, timezone

from lora import LoRa

app = Flask(__name__)

# node_id -> latest node information
nodes = {}
nodes_lock = threading.Lock()

# -------------------------------------------------------------------
# Message format:
# SOS: LifeLine_Node_001: 🌊 Flood | GPS: 6.799308,79.901005
# RSSI: -34 dBm
# SNR:  10.00 dB
# -------------------------------------------------------------------

# Accept both formats used by LifeLine nodes:
#   SOS: LifeLine_Node_001: 🌊 Flood | GPS: 6.799308,79.901005
#   LifeLine_Node_001: SOS: thenul | GPS: 6.799317,79.900963
NODE_RE = re.compile(r"\bLifeLine_Node_(?P<node_id>\d+)\b", re.IGNORECASE)

# A complete LifeLine record. We deliberately require the complete GPS
# coordinate so a truncated/corrupted packet can never update the map.
RECORD_RE = re.compile(
    r"LifeLine_Node_(?P<node_id>\d+)\s*:\s*"
    r"(?:SOS\s*:\s*)?(?P<alert>.*?)\s*\|\s*"
    r"GPS:\s*(?P<lat>[+-]?\d+(?:\.\d+)?)\s*,\s*"
    r"(?P<lon>[+-]?\d+(?:\.\d+)?)",
    re.IGNORECASE | re.DOTALL,
)

# Also accept the older format: SOS: LifeLine_Node_001: Alert | GPS: ...
OLD_RECORD_RE = re.compile(
    r"SOS\s*:\s*LifeLine_Node_(?P<node_id>\d+)\s*:\s*"
    r"(?P<alert>.*?)\s*\|\s*"
    r"GPS:\s*(?P<lat>[+-]?\d+(?:\.\d+)?)\s*,\s*"
    r"(?P<lon>[+-]?\d+(?:\.\d+)?)",
    re.IGNORECASE | re.DOTALL,
)

RSSI_RE = re.compile(r"RSSI:\s*(-?\d+(?:\.\d+)?)\s*dBm", re.IGNORECASE)
SNR_RE = re.compile(r"SNR:\s*(-?\d+(?:\.\d+)?)\s*dB", re.IGNORECASE)


def parse_lora_message(raw_message: str):
    """Parse only a complete LifeLine record.

    If a bad packet contains pieces of two messages, we select the last
    complete record rather than allowing a truncated GPS value to reach the map.
    """
    # Find all complete records. Choosing the last one handles a payload that
    # accidentally contains the tail of an old record followed by a new one.
    matches = list(RECORD_RE.finditer(raw_message))
    matches += list(OLD_RECORD_RE.finditer(raw_message))

    if not matches:
        return None

    # Sort by position in the received payload and use the final complete record.
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
    """Parse and update the latest position for a node."""
    parsed = parse_lora_message(raw_message)
    if parsed is None:
        print(f"[LifeLine] Ignored unrecognised message: {raw_message!r}")
        return

    with nodes_lock:
        old = nodes.get(parsed["node_id"])

        # Preserve the operator's Mark state when the same node sends
        # another update. A new node starts unmarked.
        parsed["marked"] = old["marked"] if old else False
        nodes[parsed["node_id"]] = parsed

    print(
        f"[LifeLine] {parsed['node_id']} -> "
        f"{parsed['latitude']}, {parsed['longitude']} | {parsed['alert']}"
    )


def lora_listener():
    """Background LoRa receive loop."""
    try:
        radio = LoRa()
        radio.start_receive()
        print("[LifeLine] LoRa receiver started.")

        while True:
            packet = radio.receive()

            if packet is not None:
                message, rssi, snr = packet

                # -------------------------------------------------------
                # RAW PACKET DEBUG
                # Print exactly what the SX1278 delivered BEFORE the
                # Flask parser changes or appends anything.
                # -------------------------------------------------------
                raw_bytes = message.encode("utf-8", errors="replace")
                print("\n========== RAW LORA PACKET ==========")
                print(f"Length: {len(raw_bytes)} bytes")
                print(f"Text : {message!r}")
                print(f"Hex  : {raw_bytes.hex(" ")}")
                print(f"RSSI : {rssi} dBm")
                print(f"SNR  : {snr} dB")
                print("=====================================\n")

                # The LoRa driver already extracts RSSI/SNR. Append them
                # if the sender's text does not contain them.
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


@app.post("/api/nodes/<node_id>/unmark")
def unmark_node(node_id):
    with nodes_lock:
        node = nodes.get(node_id)
        if node is None:
            return jsonify({"error": "Node not found"}), 404

        node["marked"] = False
        return jsonify(node)


if __name__ == "__main__":
    # Start the LoRa receiver independently of Flask request handling.
    threading.Thread(target=lora_listener, daemon=True).start()

    # Accessible from other computers on the disaster-relief-center LAN.
    app.run(host="0.0.0.0", port=5000, debug=False, threaded=True)
