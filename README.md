# LifeLine - Disaster Communication Network

> **Group EN-3 Project Proposal (Term 1)**  
> A LoRa-based communication network for instant communication during disaster situations.

---

## 📌 Problem Statement

In disaster situations—such as tsunamis, terrorist attacks, or earthquakes—if the telecommunications grid collapses, conventional communication methods cannot be used to inform emergency responders (police, fire fighters) about the situation. An off-grid communication method is essential to bridge this critical gap.

---

## 💡 Proposed Solution

A LoRa-based mesh network where each node assists other devices by relaying messages to a central command center:

- **Survivor Support:** Anyone in a hazard zone can request food, medical supplies, or rescue missions.
- **Survivor Discovery:** Users can locate the nearest survivors to facilitate extraction.
- **Smartphone Integration:** People can interact with the network via their smartphones by connecting to a node over Bluetooth.
- **Command Center Connectivity:** The central command center also connects to mobile devices via Bluetooth to receive messages, survivor locations, and coordinate relief supply distribution.

---

## 🛠️ Hardware & Components

- **Command Center:** Raspberry Pi
- **Communication Module:** LoRa Module (AI-Thinker RA-02 SX1278)
- **Survivor / Field Nodes:** ESP32 + RA-02 LoRa Module
- **Software:** Bluetooth communication application for the command center and field nodes

---

## 🎯 Objectives

- **Reliable Communication:** Implement dependable end-to-end communication between Nodes and the Command Center.
- **Mesh Networking:** Create a LoRa mesh network connecting nearest available nodes, enabling long-range communication even for nodes located outside the direct range of the command center.
- **Self-Healing Topology:** Automatically reroute mesh connections if a node becomes corrupted or goes offline to maintain steady and uninterrupted communication.

---

## 🔌 Pin Configuration

### RA-02 LoRa to ESP32 (Survivor / Field Nodes)

| RA-02 | Function | ESP32 |
| :--- | :--- | :--- |
| **3.3V / VCC** | Power | 3V3 |
| **GND** | Ground | GND |
| **SCK** | SPI clock | GPIO 18 |
| **MISO** | SPI data out | GPIO 19 |
| **MOSI** | SPI data in | GPIO 23 |
| **NSS / CS** | SPI chip select | GPIO 5 |
| **RESET / RST** | Radio reset | GPIO 14 |
| **DIO0** | Interrupt | GPIO 26 |

### RA-02 LoRa to Raspberry Pi (Command Center)

| RA-02 Pin | Function | Raspberry Pi Physical Pin | Raspberry Pi GPIO / Function |
| :--- | :--- | :--- | :--- |
| **VCC** | 3.3V Power | Pin 1 | 3.3V Power |
| **GND** | Ground | Pin 6 (or 9, 14, 20, 25, 30, 34, 39) | Ground |
| **SCK** | SPI Clock | Pin 23 | GPIO 11 (SPI0 SCLK) |
| **MISO** | SPI Data Out | Pin 21 | GPIO 9 (SPI0 MISO) |
| **MOSI** | SPI Data In | Pin 19 | GPIO 10 (SPI0 MOSI) |
| **NSS (CS)** | SPI Chip Select | Pin 24 | GPIO 8 (SPI0 CE0) |
| **RST** | Reset | Pin 22 | GPIO 25 (General GPIO for Reset) |
| **DIO0** | Interrupt | Pin 18 | GPIO 24 (Interrupt / Digital IO 0) |
