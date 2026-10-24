"""
SX1278 / AI-Thinker Ra-02 driver for Raspberry Pi.

Pin configuration:
    Ra-02 VCC  -> Pi pin 1  (3.3V)
    Ra-02 GND  -> Pi pin 6  (GND)
    Ra-02 SCK  -> Pi pin 23 (GPIO11 / SPI0 SCLK)
    Ra-02 MISO -> Pi pin 21 (GPIO9  / SPI0 MISO)
    Ra-02 MOSI -> Pi pin 19 (GPIO10 / SPI0 MOSI)
    Ra-02 NSS  -> Pi pin 24 (GPIO8  / SPI0 CE0)
    Ra-02 RST  -> Pi pin 22 (GPIO25)
    Ra-02 DIO0 -> Pi pin 18 (GPIO24)
"""

import threading
import time

try:
    import spidev
    import RPi.GPIO as GPIO
    HAS_HARDWARE = True
except (ImportError, RuntimeError):
    HAS_HARDWARE = False
    spidev = None
    GPIO = None


RESET_PIN = 25
DIO0_PIN = 24

SPI_BUS = 0
SPI_DEVICE = 0

# 433 MHz carrier frequency for LifeLine mesh network
FREQUENCY = 433_000_000

# SX1278 Register Map
REG_FIFO = 0x00
REG_OP_MODE = 0x01
REG_FRF_MSB = 0x06
REG_FRF_MID = 0x07
REG_FRF_LSB = 0x08
REG_PA_CONFIG = 0x09
REG_PA_RAMP = 0x0A
REG_OCP = 0x0B
REG_LNA = 0x0C
REG_FIFO_ADDR_PTR = 0x0D
REG_FIFO_TX_BASE_ADDR = 0x0E
REG_FIFO_RX_BASE_ADDR = 0x0F
REG_FIFO_RX_CURRENT_ADDR = 0x10
REG_IRQ_FLAGS = 0x12
REG_RX_NB_BYTES = 0x13
REG_PKT_SNR_VALUE = 0x19
REG_PKT_RSSI_VALUE = 0x1A
REG_MODEM_CONFIG_1 = 0x1D
REG_MODEM_CONFIG_2 = 0x1E
REG_PREAMBLE_MSB = 0x20
REG_PREAMBLE_LSB = 0x21
REG_PAYLOAD_LENGTH = 0x22
REG_MAX_PAYLOAD_LENGTH = 0x23
REG_MODEM_CONFIG_3 = 0x26
REG_SYNC_WORD = 0x39
REG_DIO_MAPPING_1 = 0x40
REG_VERSION = 0x42

# Operating Modes
MODE_SLEEP = 0x00
MODE_STDBY = 0x01
MODE_TX = 0x03
MODE_RX_CONTINUOUS = 0x05
LONG_RANGE_MODE = 0x80

# Bit 3 of RegOpMode: LowFrequencyModeOn (Must be 1 for bands < 525 MHz like 433 MHz)
MODE_LOW_FREQUENCY = 0x08

# IRQ Flags
IRQ_RX_DONE = 0x40
IRQ_PAYLOAD_CRC_ERROR = 0x20
IRQ_TX_DONE = 0x08


class LoRa:
    def __init__(self, frequency: int = FREQUENCY):
        self.lock = threading.Lock()
        self.frequency = frequency

        if not HAS_HARDWARE:
            print("[LoRa] Running in mock/simulation mode (spidev/RPi.GPIO not found)")
            self.is_mock = True
            return

        self.is_mock = False
        self.spi = spidev.SpiDev()
        self.spi.open(SPI_BUS, SPI_DEVICE)
        self.spi.mode = 0
        self.spi.max_speed_hz = 5_000_000

        GPIO.setmode(GPIO.BCM)
        GPIO.setup(RESET_PIN, GPIO.OUT)
        GPIO.setup(DIO0_PIN, GPIO.IN)

        print("[LoRa] Performing hardware reset on SX1278 (RST=GPIO25)...")
        self.reset()

        version = self.read_register(REG_VERSION)
        print(f"[LoRa] Read SX1278 Version Register (0x42): 0x{version:02X}")
        if version != 0x12:
            raise RuntimeError(
                f"SX1278 not detected (got 0x{version:02X}, expected 0x12). "
                "Verify SPI wiring: MOSI=Pin19, MISO=Pin21, SCK=Pin23, NSS=Pin24, 3.3V=Pin1, GND=Pin6."
            )

        self.init_lora()

    def write_register(self, address, value):
        if self.is_mock:
            return
        self.spi.xfer2([address | 0x80, value & 0xFF])

    def read_register(self, address):
        if self.is_mock:
            if address == REG_VERSION:
                return 0x12
            return 0
        return self.spi.xfer2([address & 0x7F, 0x00])[1]

    def write_fifo(self, data):
        if self.is_mock:
            return
        self.spi.xfer2([REG_FIFO | 0x80] + list(data))

    def read_fifo(self, length):
        if self.is_mock:
            return [0] * length
        return self.spi.xfer2([REG_FIFO & 0x7F] + [0] * length)[1:]

    def reset(self):
        if self.is_mock:
            return
        GPIO.output(RESET_PIN, GPIO.LOW)
        time.sleep(0.02)
        GPIO.output(RESET_PIN, GPIO.HIGH)
        time.sleep(0.02)

    def set_mode(self, mode):
        if self.is_mock:
            return
        # Bit 3 (MODE_LOW_FREQUENCY) must remain set for 433 MHz operations
        low_freq = MODE_LOW_FREQUENCY if self.frequency < 525_000_000 else 0x00
        self.write_register(REG_OP_MODE, LONG_RANGE_MODE | low_freq | mode)
        time.sleep(0.002)

    def set_frequency(self, frequency):
        if self.is_mock:
            return
        self.frequency = frequency
        frf = int(frequency * (1 << 19) / 32_000_000)
        self.write_register(REG_FRF_MSB, (frf >> 16) & 0xFF)
        self.write_register(REG_FRF_MID, (frf >> 8) & 0xFF)
        self.write_register(REG_FRF_LSB, frf & 0xFF)

    def init_lora(self):
        if self.is_mock:
            return

        # LoRa mode can only be enabled in sleep mode
        self.set_mode(MODE_SLEEP)
        self.set_frequency(self.frequency)

        # PA_BOOST, approximately +17 dBm
        self.write_register(REG_PA_CONFIG, 0x8F)
        self.write_register(REG_PA_RAMP, 0x09)
        self.write_register(REG_OCP, 0x2B)
        self.write_register(REG_LNA, 0x23)

        self.write_register(REG_FIFO_TX_BASE_ADDR, 0x00)
        self.write_register(REG_FIFO_RX_BASE_ADDR, 0x00)

        # BW 125 kHz, CR 4/5, explicit header
        self.write_register(REG_MODEM_CONFIG_1, 0x72)

        # SF7, CRC enabled
        self.write_register(REG_MODEM_CONFIG_2, 0x74)

        # AGC enabled
        self.write_register(REG_MODEM_CONFIG_3, 0x04)

        # Preamble = 8
        self.write_register(REG_PREAMBLE_MSB, 0x00)
        self.write_register(REG_PREAMBLE_LSB, 0x08)

        self.write_register(REG_MAX_PAYLOAD_LENGTH, 0xFF)

        # Private-network sync word. All LifeLine nodes must match this.
        self.write_register(REG_SYNC_WORD, 0x12)

        self.write_register(REG_DIO_MAPPING_1, 0x00)
        self.write_register(REG_IRQ_FLAGS, 0xFF)

        self.set_mode(MODE_STDBY)

        print("=" * 65)
        print("[LoRa] SX1278 Initialized Successfully!")
        print(f"[LoRa] Frequency       : {self.frequency / 1_000_000:.3f} MHz (Low-Frequency Mode Active)")
        print("[LoRa] Bandwidth       : 125 kHz")
        print("[LoRa] Spreading Factor: SF7")
        print("[LoRa] Coding Rate     : 4/5")
        print("[LoRa] Sync Word       : 0x12 (LifeLine Network)")
        print("[LoRa] CRC Check       : Enabled")
        print("[LoRa] AGC Loop        : Enabled")
        print("=" * 65)

    def start_receive(self):
        if self.is_mock:
            return
        with self.lock:
            self.set_mode(MODE_STDBY)
            self.write_register(REG_IRQ_FLAGS, 0xFF)

            rx_base = self.read_register(REG_FIFO_RX_BASE_ADDR)
            self.write_register(REG_FIFO_ADDR_PTR, rx_base)

            self.set_mode(MODE_RX_CONTINUOUS)

    def check_connectivity(self) -> dict:
        """Verify SPI connectivity and chip status."""
        if self.is_mock:
            return {
                "connected": False,
                "status": "SIMULATION",
                "details": "Running in mock mode (no spidev/hardware detected)",
                "chip_version": None,
                "frequency_mhz": self.frequency / 1_000_000,
                "mode": "MOCK",
            }
        try:
            with self.lock:
                version = self.read_register(REG_VERSION)
                if version == 0x12:
                    op_mode = self.read_register(REG_OP_MODE)
                    mode_name = self.get_mode_name(op_mode)
                    return {
                        "connected": True,
                        "status": "ONLINE",
                        "details": f"SX1278 Online (Version 0x12) | {self.frequency / 1_000_000:.2f} MHz | {mode_name}",
                        "chip_version": "0x12",
                        "frequency_mhz": self.frequency / 1_000_000,
                        "mode": mode_name,
                        "raw_op_mode": f"0x{op_mode:02X}",
                    }
                else:
                    return {
                        "connected": False,
                        "status": "OFFLINE",
                        "details": f"SX1278 not responding (Read 0x{version:02X}, expected 0x12). Check SPI wiring & 3.3V power.",
                        "chip_version": f"0x{version:02X}",
                        "frequency_mhz": self.frequency / 1_000_000,
                        "mode": "DISCONNECTED",
                    }
        except Exception as e:
            return {
                "connected": False,
                "status": "ERROR",
                "details": f"SPI Communication Error: {e}",
                "chip_version": None,
                "frequency_mhz": self.frequency / 1_000_000,
                "mode": "ERROR",
            }

    @staticmethod
    def get_mode_name(op_mode: int) -> str:
        mode_val = op_mode & 0x07
        modes = {
            0x00: "SLEEP",
            0x01: "STANDBY",
            0x02: "FSTX",
            0x03: "TX",
            0x04: "FSRX",
            0x05: "RX_CONTINUOUS",
            0x06: "RX_SINGLE",
            0x07: "CAD",
        }
        return modes.get(mode_val, f"UNKNOWN(0x{mode_val:02X})")

    def receive(self):
        """Read exactly one complete SX1278 RX packet.

        The FIFO is explicitly re-armed after every packet so that a later
        packet cannot inherit a stale FIFO pointer/state.
        """
        if self.is_mock:
            return None

        with self.lock:
            irq = self.read_register(REG_IRQ_FLAGS)

            if not (irq & IRQ_RX_DONE):
                # Self-healing check: ensure chip is still in continuous RX mode
                op_mode = self.read_register(REG_OP_MODE)
                if (op_mode & 0x07) != MODE_RX_CONTINUOUS:
                    rx_base = self.read_register(REG_FIFO_RX_BASE_ADDR)
                    self.write_register(REG_FIFO_ADDR_PTR, rx_base)
                    self.set_mode(MODE_RX_CONTINUOUS)
                return None

            # CRC error: discard the packet and immediately re-arm RX.
            if irq & IRQ_PAYLOAD_CRC_ERROR:
                print("[LoRa RX] CRC error - packet payload corrupted, discarding.")
                self.write_register(REG_IRQ_FLAGS, 0xFF)
                rx_base = self.read_register(REG_FIFO_RX_BASE_ADDR)
                self.write_register(REG_FIFO_ADDR_PTR, rx_base)
                self.set_mode(MODE_RX_CONTINUOUS)
                return ("CRC_ERROR", 0, 0)

            length = self.read_register(REG_RX_NB_BYTES)

            # Never attempt to read an invalid FIFO length.
            if length <= 0 or length > 255:
                print(f"[LoRa RX] Invalid RX length: {length} - packet discarded")
                self.write_register(REG_IRQ_FLAGS, 0xFF)
                rx_base = self.read_register(REG_FIFO_RX_BASE_ADDR)
                self.write_register(REG_FIFO_ADDR_PTR, rx_base)
                self.set_mode(MODE_RX_CONTINUOUS)
                return None

            # SX1278 gives the FIFO address where THIS packet starts.
            current_addr = self.read_register(REG_FIFO_RX_CURRENT_ADDR)
            self.write_register(REG_FIFO_ADDR_PTR, current_addr)

            # Read exactly the number of bytes reported by RegRxNbBytes.
            data = self.read_fifo(length)
            packet_bytes = bytes(data)

            raw_rssi = self.read_register(REG_PKT_RSSI_VALUE)
            # Offset is -164 dBm for LF (433 MHz) port, -157 dBm for HF
            rssi_offset = 164 if self.frequency < 525_000_000 else 157
            rssi = raw_rssi - rssi_offset

            raw_snr = self.read_register(REG_PKT_SNR_VALUE)
            if raw_snr & 0x80:
                raw_snr -= 256
            snr = raw_snr / 4.0

            # Clear all IRQ flags before re-arming RX.
            self.write_register(REG_IRQ_FLAGS, 0xFF)

            # Reset FIFO pointer to RX base. This is deliberate: it prevents
            # stale FIFO state from being carried into the next reception.
            rx_base = self.read_register(REG_FIFO_RX_BASE_ADDR)
            self.write_register(REG_FIFO_ADDR_PTR, rx_base)

            # Explicitly return to continuous RX after every packet.
            self.set_mode(MODE_RX_CONTINUOUS)

            message = packet_bytes.decode("utf-8", errors="replace")

            print("\n" + "-" * 65)
            print(f"[LoRa RX] Packet received: {length} bytes | RSSI: {rssi:.1f} dBm | SNR: {snr:.2f} dB")
            print(f"[LoRa RX] Payload: {message!r}")
            print("-" * 65)

            return message, rssi, snr

    def send(self, message: str) -> bool:
        """Transmit a string packet via LoRa and return to continuous RX mode."""
        if not message:
            return False

        if self.is_mock:
            print(f"[LoRa MOCK TX] Transmitted packet: {message}")
            return True

        data = message.encode("utf-8")
        if len(data) > 255:
            print(f"[LoRa TX] Payload too large ({len(data)} bytes, max 255)")
            return False

        print("\n" + "-" * 65)
        print(f"[LoRa TX] Starting transmission ({len(data)} bytes)...")
        print(f"[LoRa TX] Payload: {message!r}")
        tx_start = time.time()

        with self.lock:
            # Standby mode before configuring transmission
            self.set_mode(MODE_STDBY)

            # Clear all IRQ flags
            self.write_register(REG_IRQ_FLAGS, 0xFF)

            # Set FIFO pointer to TX base address
            tx_base = self.read_register(REG_FIFO_TX_BASE_ADDR)
            self.write_register(REG_FIFO_ADDR_PTR, tx_base)

            # Set payload length
            self.write_register(REG_PAYLOAD_LENGTH, len(data))

            # Write data to FIFO
            self.write_fifo(data)

            # Put radio in TX mode
            self.set_mode(MODE_TX)

            # Wait for TX_DONE
            tx_done = False
            while time.time() - tx_start < 3.0:
                irq = self.read_register(REG_IRQ_FLAGS)
                if irq & IRQ_TX_DONE:
                    tx_done = True
                    break
                time.sleep(0.01)

            # Clear IRQ flags
            self.write_register(REG_IRQ_FLAGS, 0xFF)

            # Re-arm continuous receive
            rx_base = self.read_register(REG_FIFO_RX_BASE_ADDR)
            self.write_register(REG_FIFO_ADDR_PTR, rx_base)
            self.set_mode(MODE_RX_CONTINUOUS)

            elapsed_ms = (time.time() - tx_start) * 1000
            if not tx_done:
                print(f"[LoRa TX] ERROR: Transmission timed out after {elapsed_ms:.1f} ms!")
                print("-" * 65)
                return False

            print(f"[LoRa TX] Transmission successful in {elapsed_ms:.1f} ms. Re-armed Continuous RX mode.")
            print("-" * 65)
            return True

    def close(self):
        if self.is_mock:
            return
        try:
            self.set_mode(MODE_SLEEP)
        finally:
            self.spi.close()
            GPIO.cleanup()
