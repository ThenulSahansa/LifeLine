const map = L.map("map", {
    zoomControl: true,
    worldCopyJump: true
}).setView([7.8731, 80.7718], 7);

// OpenStreetMap base layer.
// The disaster relief center needs LAN/Internet access to load the map tiles.
L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

const markers = new Map();
let selectedNodeId = null;
let nodesCache = new Map();

const detailsPanel = document.getElementById("details-panel");
const panelToggle = document.getElementById("panel-toggle");
const markButton = document.getElementById("mark-button");

function markerIcon(marked) {
    const color = marked ? "#22c55e" : "#ef4444";
    const border = marked ? "#15803d" : "#b91c1c";

    // A simple SVG pin keeps the marker independent of image assets.
    const svg = `
        <svg width="42" height="54" viewBox="0 0 42 54"
             xmlns="http://www.w3.org/2000/svg">
            <path d="M21 2C10.5 2 2 10.5 2 21c0 14.2 19 31 19 31s19-16.8 19-31C40 10.5 31.5 2 21 2z"
                  fill="${color}" stroke="${border}" stroke-width="2"/>
            <circle cx="21" cy="20" r="7" fill="white"/>
        </svg>
    `;

    return L.divIcon({
        className: "lifeline-pin",
        html: svg,
        iconSize: [42, 54],
        iconAnchor: [21, 52]
    });
}

function formatTime(iso) {
    if (!iso) return "—";

    const date = new Date(iso);
    return date.toLocaleString([], {
        day: "2-digit",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
}

function showDetails(node) {
    selectedNodeId = node.node_id;

    document.getElementById("detail-node").textContent = node.node_name;
    document.getElementById("detail-alert").textContent = node.alert || "SOS";
    document.getElementById("detail-gps").textContent =
        `${node.latitude.toFixed(6)}, ${node.longitude.toFixed(6)}`;
    document.getElementById("detail-rssi").textContent =
        node.rssi === null ? "—" : `${node.rssi} dBm`;
    document.getElementById("detail-snr").textContent =
        node.snr === null ? "—" : `${node.snr.toFixed(2)} dB`;
    document.getElementById("detail-time").textContent = formatTime(node.received_at);
    document.getElementById("detail-raw").textContent = node.raw_message;

    markButton.textContent = node.marked ? "Marked" : "Mark";
    markButton.classList.toggle("marked", node.marked);

    detailsPanel.classList.remove("hidden");
    panelToggle.classList.add("hidden");
}

function hideDetails() {
    detailsPanel.classList.add("hidden");

    if (selectedNodeId) {
        panelToggle.classList.remove("hidden");
    }
}

function updateMarker(node) {
    const position = [node.latitude, node.longitude];
    let marker = markers.get(node.node_id);

    if (!marker) {
        marker = L.marker(position, {
            icon: markerIcon(node.marked),
            title: node.node_name
        }).addTo(map);

        marker.on("click", () => {
            const current = nodesCache.get(node.node_id);
            if (current) showDetails(current);
        });

        markers.set(node.node_id, marker);
    } else {
        // Same node: move the existing pin rather than creating another pin.
        marker.setLatLng(position);
        marker.setIcon(markerIcon(node.marked));
    }
}

function updateMap(nodes) {
    const seen = new Set();

    for (const node of nodes) {
        seen.add(node.node_id);
        nodesCache.set(node.node_id, node);
        updateMarker(node);
    }

    // Remove nodes that are no longer returned by the server.
    for (const [nodeId, marker] of markers.entries()) {
        if (!seen.has(nodeId)) {
            map.removeLayer(marker);
            markers.delete(nodeId);
            nodesCache.delete(nodeId);
        }
    }

    document.getElementById("node-count").textContent =
        `${nodes.length} active node${nodes.length === 1 ? "" : "s"}`;

    // Refresh the open detail box if its node sent a new message.
    if (selectedNodeId && nodesCache.has(selectedNodeId)) {
        const selected = nodesCache.get(selectedNodeId);
        const oldScroll = window.scrollY;
        showDetails(selected);
        window.scrollTo(0, oldScroll);
    }
}

async function pollNodes() {
    try {
        const response = await fetch("/api/nodes", { cache: "no-store" });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const nodes = await response.json();
        updateMap(nodes);
    } catch (error) {
        console.error("LifeLine update error:", error);
    }
}

document.getElementById("close-panel").addEventListener("click", hideDetails);

panelToggle.addEventListener("click", () => {
    if (selectedNodeId && nodesCache.has(selectedNodeId)) {
        showDetails(nodesCache.get(selectedNodeId));
    }
});

markButton.addEventListener("click", async () => {
    if (!selectedNodeId) return;

    const node = nodesCache.get(selectedNodeId);
    if (!node) return;

    const action = node.marked ? "unmark" : "mark";

    try {
        const response = await fetch(
            `/api/nodes/${encodeURIComponent(selectedNodeId)}/${action}`,
            { method: "POST" }
        );

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const updated = await response.json();

        nodesCache.set(updated.node_id, updated);

        const marker = markers.get(updated.node_id);
        if (marker) {
            marker.setIcon(markerIcon(updated.marked));
        }

        showDetails(updated);
    } catch (error) {
        console.error("Could not update Mark state:", error);
    }
});

// First load immediately, then update approximately once per second.
pollNodes();
setInterval(pollNodes, 1000);
