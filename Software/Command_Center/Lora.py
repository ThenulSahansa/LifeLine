import time
from SX127x.LoRa import *
from SX127x.board_config import BOARD

# Configure the pin mappings matching our table
BOARD.setup()
BOARD.reset_pin = 25  # GPIO 25

class MyLoRa(LoRa):
    def __init__(self, verbose=False):
        super(MyLoRa, self).__init__(verbose)
        self.set_mode(MODE.SLEEP)
        
        # VERY IMPORTANT: AI-Thinker Ra-02 operates at 433MHz
        self.set_pa_config(pa_select=1)
        self.set_freq(433.0) 

    def on_rx_done(self):
        print("\nReceived packet!")
        self.clear_irq_flags(RxDone=1)
        payload = self.read_payload(nocheck=True)
        print("Payload:", bytes(payload).decode('utf-8', errors='ignore'))
        
        # Put back into receive mode
        self.set_mode(MODE.RXCONT)

# Initialize the LoRa object
lora = MyLoRa(verbose=False)

# Configure modem settings (Must match your transmitter node)
lora.set_spreading_factor(7)
lora.set_bw(BW.BW125)
lora.set_coding_rate(CODING_RATE.CR4_5)

print("Listening for LoRa packets on 433MHz...")
lora.set_mode(MODE.RXCONT)

try:
    while True:
        time.sleep(0.5)
except KeyboardInterrupt:
    print("\nDisconnecting...")
    lora.set_mode(MODE.SLEEP)
    BOARD.teardown()
