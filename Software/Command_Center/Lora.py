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

import time
import spidev
import RPi.GPIO as GPIO


RESET_PIN = 25
DIO0_PIN = 24

SPI_BUS = 0
SPI_DEVICE = 0

# Change this if your Ra-02 and all LifeLine nodes use another frequency.
FREQUENCY = 433_000_000

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

MODE_SLEEP = 0x00
MODE_STDBY = 0x01
MODE_TX = 0x03
MODE_RX_CONTINUOUS = 0x05
LONG_RANGE_MODE = 0x80

IRQ_RX_DONE = 0x40
IRQ_PAYLOAD_CRC_ERROR = 0x20
IRQ_TX_DONE = 0x08


class LoRa:
    def __init__(self):
        self.spi = spidev.SpiDev()
        self.spi.open(SPI_BUS, SPI_DEVICE)
        self.spi.mode = 0
        self.spi.max_speed_hz = 5_000_000

        GPIO.setmode(GPIO.BCM)
        GPIO.setup(RESET_PIN, GPIO.OUT)
        GPIO.setup(DIO0_PIN, GPIO.IN)

        self.reset()

        version = self.read_register(REG_VERSION)
        print(f"[LoRa] SX1278 version: 0x{version:02X}")
        if version != 0x12:
            raise RuntimeError(
                f"SX1278 not detected (version 0x{version:02X}). "
                "Check SPI wiring and power."
            )

        self.init_lora()

    def write_register(self, address, value):
        self.spi.xfer2([address | 0x80, value & 0xFF])

    def read_register(self, address):
        return self.spi.xfer2([address & 0x7F, 0x00])[1]

    def write_fifo(self, data):
        self.spi.xfer2([REG_FIFO | 0x80] + list(data))

    def read_fifo(self, length):
        return self.spi.xfer2([REG_FIFO & 0x7F] + [0] * length)[1:]

    def reset(self):
        GPIO.output(RESET_PIN, GPIO.LOW)
        time.sleep(0.01)
        GPIO.output(RESET_PIN, GPIO.HIGH)
        time.sleep(0.01)

    def set_mode(self, mode):
        self.write_register(REG_OP_MODE, LONG_RANGE_MODE | mode)
        time.sleep(0.001)

    def set_frequency(self, frequency):
        frf = int(frequency * (1 << 19) / 32_000_000)
        self.write_register(REG_FRF_MSB, (frf >> 16) & 0xFF)
        self.write_register(REG_FRF_MID, (frf >> 8) & 0xFF)
        self.write_register(REG_FRF_LSB, frf & 0xFF)

    def init_lora(self):
        self.set_mode(MODE_SLEEP)
        self.set_frequency(FREQUENCY)

        # PA_BOOST, approximately +17 dBm.
        self.write_register(REG_PA_CONFIG, 0x8F)
        self.write_register(REG_PA_RAMP, 0x09)
        self.write_register(REG_OCP, 0x2B)
        self.write_register(REG_LNA, 0x23)

        self.write_register(REG_FIFO_TX_BASE_ADDR, 0x00)
        self.write_register(REG_FIFO_RX_BASE_ADDR, 0x00)

        # BW 125 kHz, CR 4/5, explicit header.
        self.write_register(REG_MODEM_CONFIG_1, 0x72)

        # SF7, CRC enabled.
        self.write_register(REG_MODEM_CONFIG_2, 0x74)

        # AGC enabled.
        self.write_register(REG_MODEM_CONFIG_3, 0x04)

        # Preamble = 8.
        self.write_register(REG_PREAMBLE_MSB, 0x00)
        self.write_register(REG_PREAMBLE_LSB, 0x08)

        self.write_register(REG_MAX_PAYLOAD_LENGTH, 0xFF)

        # Private-network sync word. All LifeLine nodes must match this.
        self.write_register(REG_SYNC_WORD, 0x12)

        self.write_register(REG_DIO_MAPPING_1, 0x00)
        self.write_register(REG_IRQ_FLAGS, 0xFF)

        self.set_mode(MODE_STDBY)

        print(
            f"[LoRa] Ready: {FREQUENCY / 1_000_000:.3f} MHz, "
            "BW 125 kHz, SF7, CR 4/5"
        )

    def start_receive(self):
        self.set_mode(MODE_STDBY)
        self.write_register(REG_IRQ_FLAGS, 0xFF)

        rx_base = self.read_register(REG_FIFO_RX_BASE_ADDR)
        self.write_register(REG_FIFO_ADDR_PTR, rx_base)

        self.set_mode(MODE_RX_CONTINUOUS)

    def receive(self):
        """Read exactly one complete SX1278 RX packet.

        The FIFO is explicitly re-armed after every packet so that a later
        packet cannot inherit a stale FIFO pointer/state.
        """
        irq = self.read_register(REG_IRQ_FLAGS)

        if not (irq & IRQ_RX_DONE):
            return None

        # CRC error: discard the packet and immediately re-arm RX.
        if irq & IRQ_PAYLOAD_CRC_ERROR:
            print("[LoRa] CRC error - packet discarded")
            self.write_register(REG_IRQ_FLAGS, 0xFF)
            rx_base = self.read_register(REG_FIFO_RX_BASE_ADDR)
            self.write_register(REG_FIFO_ADDR_PTR, rx_base)
            self.set_mode(MODE_RX_CONTINUOUS)
            return None

        length = self.read_register(REG_RX_NB_BYTES)

        # Never attempt to read an invalid FIFO length.
        if length <= 0 or length > 255:
            print(f"[LoRa] Invalid RX length: {length} - packet discarded")
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
        rssi = raw_rssi - 157

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

        return message, rssi, snr

    def close(self):
        try:
            self.set_mode(MODE_SLEEP)
        finally:
            self.spi.close()
            GPIO.cleanup()
