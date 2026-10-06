import spidev
import RPi.GPIO as GPIO
import time
import threading

# ============================================================
# Raspberry Pi GPIO configuration
# ============================================================

RESET_PIN = 25       # Physical pin 22
DIO0_PIN = 24        # Physical pin 18

# SPI0 CE0 = GPIO8 = physical pin 24
SPI_BUS = 0
SPI_DEVICE = 0

# ============================================================
# LoRa configuration
# ============================================================

FREQUENCY = 433_000_000

# These settings MUST be the same on both LoRa modules
BANDWIDTH = 125_000
SPREADING_FACTOR = 7
CODING_RATE = 5       # 4/5

SYNC_WORD = 0x12

# ============================================================
# SX1278 registers
# ============================================================

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

REG_DIO_MAPPING_1 = 0x40
REG_VERSION = 0x42

# ============================================================
# Operating modes
# ============================================================

MODE_SLEEP = 0x00
MODE_STDBY = 0x01
MODE_TX = 0x03
MODE_RX_CONTINUOUS = 0x05

LONG_RANGE_MODE = 0x80

# IRQ flags
IRQ_RX_DONE = 0x40
IRQ_PAYLOAD_CRC_ERROR = 0x20
IRQ_TX_DONE = 0x08

# ============================================================
# LoRa class
# ============================================================

class LoRa:

    def __init__(self):

        # SPI
        self.spi = spidev.SpiDev()
        self.spi.open(SPI_BUS, SPI_DEVICE)

        # SPI mode 0
        self.spi.mode = 0

        # SX1278 supports SPI up to 10 MHz.
        self.spi.max_speed_hz = 5_000_000

        # GPIO
        GPIO.setmode(GPIO.BCM)

        GPIO.setup(RESET_PIN, GPIO.OUT)
        GPIO.setup(DIO0_PIN, GPIO.IN)

        self.reset()

        # Check SX1278 version
        version = self.read_register(REG_VERSION)

        print(f"SX1278 version: 0x{version:02X}")

        if version != 0x12:
            print("WARNING: SX1278 was not detected correctly.")

        self.init_lora()

    # --------------------------------------------------------
    # SPI register functions
    # --------------------------------------------------------

    def write_register(self, address, value):

        self.spi.xfer2([
            address | 0x80,
            value & 0xFF
        ])

    def read_register(self, address):

        result = self.spi.xfer2([
            address & 0x7F,
            0x00
        ])

        return result[1]

    def write_fifo(self, data):

        self.spi.xfer2(
            [REG_FIFO | 0x80] + list(data)
        )

    def read_fifo(self, length):

        result = self.spi.xfer2(
            [REG_FIFO & 0x7F] + [0] * length
        )

        return result[1:]

    # --------------------------------------------------------
    # Hardware reset
    # --------------------------------------------------------

    def reset(self):

        GPIO.output(RESET_PIN, GPIO.LOW)
        time.sleep(0.01)

        GPIO.output(RESET_PIN, GPIO.HIGH)
        time.sleep(0.01)

    # --------------------------------------------------------
    # Set operating mode
    # --------------------------------------------------------

    def set_mode(self, mode):

        self.write_register(
            REG_OP_MODE,
            LONG_RANGE_MODE | mode
        )

        time.sleep(0.001)

    # --------------------------------------------------------
    # Set frequency
    # --------------------------------------------------------

    def set_frequency(self, frequency):

        # FRF = frequency * 2^19 / 32 MHz

        frf = int(
            frequency * (1 << 19) / 32_000_000
        )

        self.write_register(
            REG_FRF_MSB,
            (frf >> 16) & 0xFF
        )

        self.write_register(
            REG_FRF_MID,
            (frf >> 8) & 0xFF
        )

        self.write_register(
            REG_FRF_LSB,
            frf & 0xFF
        )

    # --------------------------------------------------------
    # Initialize SX1278
    # --------------------------------------------------------

    def init_lora(self):

        # Put radio into sleep mode before LoRa configuration
        self.set_mode(MODE_SLEEP)

        time.sleep(0.01)

        # Frequency
        self.set_frequency(FREQUENCY)

        # ----------------------------------------------------
        # PA configuration
        # ----------------------------------------------------

        # PA_BOOST, approximately +17 dBm
        self.write_register(
            REG_PA_CONFIG,
            0x8F
        )

        # PA ramp
        self.write_register(
            REG_PA_RAMP,
            0x09
        )

        # Over-current protection
        self.write_register(
            REG_OCP,
            0x2B
        )

        # LNA boost
        self.write_register(
            REG_LNA,
            0x23
        )

        # ----------------------------------------------------
        # FIFO
        # ----------------------------------------------------

        self.write_register(
            REG_FIFO_TX_BASE_ADDR,
            0x00
        )

        self.write_register(
            REG_FIFO_RX_BASE_ADDR,
            0x00
        )

        # ----------------------------------------------------
        # Modem configuration
        # ----------------------------------------------------

        # BW = 125 kHz
        # Coding rate = 4/5
        # Explicit header
        modem_config_1 = 0x72

        self.write_register(
            REG_MODEM_CONFIG_1,
            modem_config_1
        )

        # SF7
        # CRC enabled
        modem_config_2 = 0x74

        self.write_register(
            REG_MODEM_CONFIG_2,
            modem_config_2
        )

        # AGC enabled
        self.write_register(
            REG_MODEM_CONFIG_3,
            0x04
        )

        # Preamble = 8 symbols
        self.write_register(
            REG_PREAMBLE_MSB,
            0x00
        )

        self.write_register(
            REG_PREAMBLE_LSB,
            0x08
        )

        # Maximum packet length
        self.write_register(
            REG_MAX_PAYLOAD_LENGTH,
            0xFF
        )

        # Sync word
        # 0x12 is the common private LoRa network sync word
        self.write_register(
            0x39,
            SYNC_WORD
        )

        # DIO0:
        # 00 = RxDone in RX mode
        # 01 = TxDone in TX mode
        self.write_register(
            REG_DIO_MAPPING_1,
            0x00
        )

        # Clear all IRQ flags
        self.write_register(
            REG_IRQ_FLAGS,
            0xFF
        )

        # Standby
        self.set_mode(MODE_STDBY)

        print("LoRa initialized.")
        print(f"Frequency: {FREQUENCY / 1_000_000:.3f} MHz")
        print(f"Bandwidth: {BANDWIDTH / 1000:.0f} kHz")
        print(f"Spreading Factor: SF{SPREADING_FACTOR}")

    # --------------------------------------------------------
    # Send LoRa packet
    # --------------------------------------------------------

    def send(self, message):

        if isinstance(message, str):
            data = message.encode("utf-8")
        else:
            data = bytes(message)

        if len(data) > 255:
            raise ValueError("Message is too long.")

        # Standby
        self.set_mode(MODE_STDBY)

        # Clear IRQ flags
        self.write_register(
            REG_IRQ_FLAGS,
            0xFF
        )

        # Set FIFO pointer to TX base
        tx_base = self.read_register(
            REG_FIFO_TX_BASE_ADDR
        )

        self.write_register(
            REG_FIFO_ADDR_PTR,
            tx_base
        )

        # Write payload
        self.write_fifo(data)

        # Payload length
        self.write_register(
            REG_PAYLOAD_LENGTH,
            len(data)
        )

        print(f"TX: {message}")

        # Start transmission
        self.set_mode(MODE_TX)

        # Wait for TX complete
        timeout = time.time() + 10

        while time.time() < timeout:

            irq = self.read_register(
                REG_IRQ_FLAGS
            )

            if irq & IRQ_TX_DONE:
                break

            time.sleep(0.001)

        else:
            print("TX timeout!")
            self.set_mode(MODE_STDBY)
            return

        # Clear TX done
        self.write_register(
            REG_IRQ_FLAGS,
            IRQ_TX_DONE
        )

        # Return to standby
        self.set_mode(MODE_STDBY)

    # --------------------------------------------------------
    # Start continuous receive mode
    # --------------------------------------------------------

    def start_receive(self):

        self.set_mode(MODE_STDBY)

        # Clear IRQ flags
        self.write_register(
            REG_IRQ_FLAGS,
            0xFF
        )

        # FIFO receive pointer
        rx_base = self.read_register(
            REG_FIFO_RX_BASE_ADDR
        )

        self.write_register(
            REG_FIFO_ADDR_PTR,
            rx_base
        )

        # Continuous receive
        self.set_mode(MODE_RX_CONTINUOUS)

        print("RX mode started.")

    # --------------------------------------------------------
    # Check for received packet
    # --------------------------------------------------------

    def receive(self):

        irq = self.read_register(
            REG_IRQ_FLAGS
        )

        if not (irq & IRQ_RX_DONE):
            return None

        # Check CRC
        if irq & IRQ_PAYLOAD_CRC_ERROR:

            print("Received packet with CRC error.")

            self.write_register(
                REG_IRQ_FLAGS,
                IRQ_PAYLOAD_CRC_ERROR | IRQ_RX_DONE
            )

            return None

        # Number of received bytes
        length = self.read_register(
            REG_RX_NB_BYTES
        )

        # Current FIFO address
        current_addr = self.read_register(
            REG_FIFO_RX_CURRENT_ADDR
        )

        # Set FIFO pointer
        self.write_register(
            REG_FIFO_ADDR_PTR,
            current_addr
        )

        # Read packet
        data = self.read_fifo(length)

        # RSSI
        raw_rssi = self.read_register(
            REG_PKT_RSSI_VALUE
        )

        rssi = raw_rssi - 157

        # SNR
        raw_snr = self.read_register(
            REG_PKT_SNR_VALUE
        )

        if raw_snr & 0x80:
            raw_snr -= 256

        snr = raw_snr / 4.0

        # Clear IRQ flags
        self.write_register(
            REG_IRQ_FLAGS,
            0xFF
        )

        try:
            message = bytes(data).decode("utf-8")
        except UnicodeDecodeError:
            message = bytes(data).hex()

        return message, rssi, snr

    # --------------------------------------------------------
    # Close
    # --------------------------------------------------------

    def close(self):

        self.set_mode(MODE_SLEEP)

        self.spi.close()

        GPIO.cleanup()


# ============================================================
# Main program
# ============================================================

lora = LoRa()

running = True
radio_lock = threading.Lock()


# ------------------------------------------------------------
# Receive thread
# ------------------------------------------------------------

def receive_loop():

    global running

    while running:

        with radio_lock:

            # Don't check RX while transmitting
            if lora.read_register(REG_OP_MODE) & 0x07 == MODE_RX_CONTINUOUS:

                packet = lora.receive()

                if packet is not None:

                    message, rssi, snr = packet

                    print()
                    print("================================")
                    print("RX MESSAGE:")
                    print(message)
                    print(f"RSSI: {rssi} dBm")
                    print(f"SNR:  {snr:.2f} dB")
                    print("================================")
                    print("> ", end="", flush=True)

        time.sleep(0.01)


# Start receiving
with radio_lock:
    lora.start_receive()

rx_thread = threading.Thread(
    target=receive_loop,
    daemon=True
)

rx_thread.start()


# ------------------------------------------------------------
# Main chat loop
# ------------------------------------------------------------

print()
print("==========================================")
print("      Raspberry Pi LoRa Chat")
print("==========================================")
print("Type a message and press ENTER to send.")
print("Type 'exit' to quit.")
print()

try:

    while True:

        message = input("> ")

        if message.lower() == "exit":
            break

        if message.strip() == "":
            continue

        with radio_lock:

            # Send
            lora.send(message)

            # Go back to receive mode
            lora.start_receive()

except KeyboardInterrupt:

    print("\nStopping...")

finally:

    running = False

    time.sleep(0.1)

    lora.close()

    print("LoRa stopped.")