#include <LoRa.h>
#include <SPI.h>


// Pin Configurations
#define SS 5
#define RST 14
#define DIO0 26
#define LED_PIN 2 // Standard onboard LED (Usually GPIO 2)

#define LORA_FREQUENCY 433E6

// =========================================================================
// CUSTOM TRANSMISSION FUNCTION
// Sends any custom string payload and instantly restores background receiving
// =========================================================================
void sendLoRaMessage(String message) {
  Serial.print("\n-> Sending Outbound Payload: ");
  Serial.println(message);

  // Package and broadcast the string data
  LoRa.beginPacket();
  LoRa.print(message);
  LoRa.endPacket(); // Briefly switches radio to TX mode to push the data out

  // Clear out underlying hardware register pipelines
  LoRa.flush();

  // CRITICAL: Instantly re-engage continuous background listening profile
  LoRa.receive();
}

// =========================================================================
// SERIAL INPUT ENGINE
// Checks if the user typed text into the monitor terminal
// =========================================================================
void checkSerialInput() {
  if (Serial.available() > 0) {
    // Read the string from the serial buffer until a newline character is hit
    String inputMessage = Serial.readStringUntil('\n');

    // Clean up any hidden carriage return characters (\r) left over by terminal
    // settings
    inputMessage.trim();

    // Only transmit if the user actually typed characters
    if (inputMessage.length() > 0) {
      sendLoRaMessage(inputMessage);
    }
  }
}

void setup() {
  Serial.begin(115200);
  while (!Serial)
    ;

  // Initialize Built-in LED pin
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  Serial.println("\n=============================================");
  Serial.println("--- BOOTING LORA INTERACTIVE TERMINAL ---");
  Serial.println("Type your message below and press Enter to send!");
  Serial.println("=============================================");

  // Force physical pins to proper initial states
  pinMode(SS, OUTPUT);
  pinMode(DIO0, INPUT);

  // Hardware reset sequence
  pinMode(RST, OUTPUT);
  digitalWrite(RST, LOW);
  delay(20);
  digitalWrite(RST, HIGH);
  delay(20);

  LoRa.setPins(SS, RST, DIO0);

  if (!LoRa.begin(LORA_FREQUENCY)) {
    Serial.println("[CRITICAL] LoRa initialization failed!");
    while (1)
      ;
  }

  // Stable default radio profiles
  LoRa.setTxPower(17);
  LoRa.setSpreadingFactor(7);
  LoRa.setSignalBandwidth(125E3);
  LoRa.setCodingRate4(5);

  // Start up continuous background receiving mode
  LoRa.receive();
}

void loop() {
  // 1. NON-BLOCKING BACKGROUND RECEIVE CHECK
  int packetSize = LoRa.parsePacket();

  if (packetSize) {
    // BLINK LED ON: Turn on immediately when data code is receiving
    digitalWrite(LED_PIN, HIGH);

    String incomingMessage = "";
    while (LoRa.available()) {
      incomingMessage += (char)LoRa.read();
    }

    // Print out what the other node sent you
    Serial.print("\n[SUCCESS] Received: ");
    Serial.println(incomingMessage);
    Serial.print("[RSSI]: ");
    Serial.print(LoRa.packetRssi());
    Serial.println(" dBm");

    // Short visible flash duration for the LED, then turn it off
    delay(100);
    digitalWrite(LED_PIN, LOW);
  }

  // 2. ON-DEMAND SERIAL CHECK
  // Instead of an automated timer, this only sends data when you ask it to
  checkSerialInput();
}
