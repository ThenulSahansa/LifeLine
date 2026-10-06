# LifeLine — Raspberry Pi Disaster Response Map

A Flask dashboard for a Raspberry Pi connected to an AI-Thinker Ra-02 / SX1278 LoRa receiver.

## Features

- Parses messages such as:

  `SOS: LifeLine_Node_001: 🌊 Flood | GPS: 6.799308,79.901005`

  plus RSSI/SNR.
- Shows every node on a world map.
- Red pin = active/unmarked SOS.
- Green pin = marked incident.
- Clicking a pin opens a rounded details card in the bottom-right.
- Details card can be hidden/shown.
- `Mark` changes the pin to green and changes the button to `Marked`.
- A later message from the same node ID moves the existing pin instead of creating a duplicate.
- Mark state is preserved when the same node sends another update.
- Dashboard is accessible from other computers on the same network.

## Raspberry Pi setup

Enable SPI:

```bash
sudo raspi-config
```

Choose:

`Interface Options -> SPI -> Enable`

Install dependencies:

```bash
sudo apt update
sudo apt install python3-pip python3-spidev python3-rpi.gpio
python3 -m pip install --break-system-packages -r requirements.txt
```

Run:

```bash
python3 app.py
```

Then open from the Raspberry Pi:

`http://127.0.0.1:5000`

From another computer on the same LAN, use:

`http://RASPBERRY_PI_IP:5000`

Find the Pi's IP with:

```bash
hostname -I
```

## LoRa configuration

`lora.py` currently uses:

- Frequency: 433 MHz
- Bandwidth: 125 kHz
- Spreading factor: SF7
- Coding rate: 4/5
- CRC: enabled
- Sync word: 0x12

All LifeLine LoRa nodes must use matching radio settings.

## Wiring Connections

Connect the AI-Thinker Ra-02 SX1278 LoRa module to the Raspberry Pi by wiring the SPI and power pins directly (both operate on 3.3V logic):

| RA-02 Pin | Function | Raspberry Pi Physical Pin | Raspberry Pi GPIO / Header Function |
| :--- | :--- | :--- | :--- |
| **VCC** | 3.3V Power | Pin 1 | 3.3V Power |
| **GND** | Ground | Pin 6, 9, 14, 20, 25, 30, 34, or 39 | Ground |
| **SCK** | SPI Clock | Pin 23 | GPIO 11 (SPI0 SCLK) |
| **MISO** | SPI Data Out | Pin 21 | GPIO 9 (SPI0 MISO) |
| **MOSI** | SPI Data In | Pin 19 | GPIO 10 (SPI0 MOSI) |
| **NSS (CS)** | SPI Chip Select | Pin 24 | GPIO 8 (SPI0 CE0) |
| **RST** | Reset | Pin 22 | GPIO 25 (General GPIO for Reset) |
| **DIO0** | Interrupt | Pin 18 | GPIO 24 (Interrupt / Digital IO 0) |

## Map

The UI uses Leaflet with OpenStreetMap tiles. The dashboard therefore needs network access to load map tiles. For a fully offline disaster-relief deployment, replace the online tile layer with a locally hosted tile service.

## Important

Use the correct antenna and never power the Ra-02 from 5 V.
