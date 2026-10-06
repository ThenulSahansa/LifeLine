const map = L.map("map", {
    zoomControl: true,
    worldCopyJump: true
}).setView([7.8731, 80.7718], 7);

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
const moreOptionsButton = document.getElementById("more-options-button");
const moreOptionsMenu = document.getElementById("more-options-menu");
const deletePinButton = document.getElementById("delete-pin-button");
const deleteHistoryButton = document.getElementById("delete-history-button");

function markerIcon(marked) {
    const color = marked ? "#22c55e" : "#ef4444";
    const border = marked ? "#15803d" : "#b91c1c";
    const svg = `
        <svg width="42" height="54" viewBox="0 0 42 54" xmlns="http://www.w3.org/2000/svg">
            <path d="M21 2C10.5 2 2 10.5 2 21c0 14.2 19 31 19 31s19-16.8 19-31C40 10.5 31.5 2 21 2z"
                  fill="${color}" stroke="${border}" stroke-width="2"/>
            <circle cx="21" cy="20" r="7" fill="white"/>
        </svg>`;

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

function showHistory(node) {
    const list = document.getElementById("history-list");
    const count = document.getElementById("history-count");
    const history = Array.isArray(node.history) ? node.history : [];

    count.textContent = history.length;
    list.innerHTML = "";

    if (!history.length) {
        list.innerHTML = '<div class="history-empty">No previous messages.</div>';
        return;
    }

    // Newest previous message first. The current SOS is shown separately above.
    [...history].reverse().forEach((item, index) => {
        const card = document.createElement("div");
        card.className = "history-card";

        const title = document.createElement("div");
        title.className = "history-alert";
        title.textContent = item.alert || "SOS";

        const meta = document.createElement("div");
        meta.className = "history-meta";
        meta.textContent = `${formatTime(item.received_at)} • ${Number(item.latitude).toFixed(6)}, ${Number(item.longitude).toFixed(6)}`;

        card.appendChild(title);
        card.appendChild(meta);

        if (item.rssi !== null && item.rssi !== undefined) {
            const signal = document.createElement("div");
            signal.className = "history-signal";
            signal.textContent = `RSSI ${item.rssi} dBm${item.snr !== null && item.snr !== undefined ? ` • SNR ${Number(item.snr).toFixed(2)} dB` : ""}`;
            card.appendChild(signal);
        }

        list.appendChild(card);
    });
}

function showDetails(node) {
    selectedNodeId = node.node_id;

    document.getElementById("detail-node").textContent = node.node_name;
    document.getElementById("detail-alert").textContent = node.alert || "SOS";
    document.getElementById("detail-gps").textContent =
        `${Number(node.latitude).toFixed(6)}, ${Number(node.longitude).toFixed(6)}`;
    document.getElementById("detail-rssi").textContent =
        node.rssi === null ? "—" : `${node.rssi} dBm`;
    document.getElementById("detail-snr").textContent =
        node.snr === null ? "—" : `${Number(node.snr).toFixed(2)} dB`;
    document.getElementById("detail-time").textContent = formatTime(node.received_at);
    document.getElementById("detail-raw").textContent = node.raw_message || "";
    document.getElementById("detail-badge").textContent = node.marked ? "MARKED" : "ACTIVE SOS";

    markButton.textContent = node.marked ? "Marked — click to unmark" : "Mark as handled";
    markButton.classList.toggle("marked", node.marked);

    showHistory(node);

    detailsPanel.classList.remove("hidden");
    panelToggle.classList.add("hidden");
}

function hideDetails() {
    // Clear the selection so the 1-second polling loop does not reopen
    // the panel immediately after the user closes it.
    selectedNodeId = null;
    detailsPanel.classList.add("hidden");
    moreOptionsMenu.classList.add("hidden");
    panelToggle.classList.add("hidden");
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

    for (const [nodeId, marker] of markers.entries()) {
        if (!seen.has(nodeId)) {
            map.removeLayer(marker);
            markers.delete(nodeId);
            nodesCache.delete(nodeId);
        }
    }

    document.getElementById("node-count").textContent =
        `${nodes.length} active node${nodes.length === 1 ? "" : "s"}`;

    // Only refresh an already-open details panel. Do not reopen a panel
    // that the user explicitly closed.
    if (selectedNodeId && nodesCache.has(selectedNodeId) && !detailsPanel.classList.contains("hidden")) {
        showDetails(nodesCache.get(selectedNodeId));
    }
}

async function deletePin() {
    if (!selectedNodeId) return;

    const nodeId = selectedNodeId;
    const node = nodesCache.get(nodeId);
    if (!node) return;

    if (!confirm(`Delete the pin for ${node.node_name}?\n\nIts SOS history will be kept.`)) {
        return;
    }

    try {
        const response = await fetch(`/api/nodes/${encodeURIComponent(nodeId)}`, {
            method: "DELETE"
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const marker = markers.get(nodeId);
        if (marker) {
            map.removeLayer(marker);
            markers.delete(nodeId);
        }
        nodesCache.delete(nodeId);
        selectedNodeId = null;
        detailsPanel.classList.add("hidden");
        moreOptionsMenu.classList.add("hidden");
        panelToggle.classList.add("hidden");
    } catch (error) {
        console.error("Could not delete pin:", error);
        alert("Could not delete the pin.");
    }
}

async function deleteHistory() {
    if (!selectedNodeId) return;

    const nodeId = selectedNodeId;
    const node = nodesCache.get(nodeId);
    if (!node) return;

    if (!confirm(`Delete all previous SOS history for ${node.node_name}?\n\nThe current SOS will remain.`)) {
        return;
    }

    try {
        const response = await fetch(`/api/nodes/${encodeURIComponent(nodeId)}/history`, {
            method: "DELETE"
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const updated = await response.json();
        nodesCache.set(updated.node_id, updated);
        showDetails(updated);
    } catch (error) {
        console.error("Could not delete SOS history:", error);
        alert("Could not delete the SOS history.");
    }
}

moreOptionsButton.addEventListener("click", (event) => {
    event.stopPropagation();
    moreOptionsMenu.classList.toggle("hidden");
});

deletePinButton.addEventListener("click", () => {
    moreOptionsMenu.classList.add("hidden");
    deletePin();
});

deleteHistoryButton.addEventListener("click", () => {
    moreOptionsMenu.classList.add("hidden");
    deleteHistory();
});

document.addEventListener("click", (event) => {
    if (!moreOptionsMenu.contains(event.target) && event.target !== moreOptionsButton) {
        moreOptionsMenu.classList.add("hidden");
    }
});

async function pollNodes() {
    try {
        const response = await fetch("/api/nodes", { cache: "no-store" });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const nodes = await response.json();
        updateMap(nodes);
    } catch (error) {
        console.error("LifeLine update error:", error);
    }
}

document.getElementById("close-panel").addEventListener("click", hideDetails);

panelToggle.addEventListener("click", () => {
    // Only refresh an already-open details panel. Do not reopen a panel
    // that the user explicitly closed.
    if (selectedNodeId && nodesCache.has(selectedNodeId) && !detailsPanel.classList.contains("hidden")) {
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
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const updated = await response.json();
        nodesCache.set(updated.node_id, updated);

        const marker = markers.get(updated.node_id);
        if (marker) marker.setIcon(markerIcon(updated.marked));

        showDetails(updated);
    } catch (error) {
        console.error("Could not update Mark state:", error);
    }
});

pollNodes();
setInterval(pollNodes, 1000);
