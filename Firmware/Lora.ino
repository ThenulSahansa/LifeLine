#include <LoRa.h>
#include <SPI.h>


// CORRECTED PIN CONFIGURATION
#define ss 5
#define rst 14
#define dio0 26 // Updated to match your physical wiring

#define LED_PIN 2 // Built-in LED on GPIO 2 (Safe from conflicts now!)

unsigned long lastSendTime = 0;
const int sendInterval = 4000; // Sends a packet every 4 seconds
int msgCount = 0;
volatile bool packetReceived = false;

// BACKGROUND INTERRUPT RECEIVE
void onReceive(int packetSize) {
  if (packetSize == 0)
    return;

  Serial.print("-> RECEIVED PACKET: ");
  while (LoRa.available()) {
    Serial.print((char)LoRa.read());
  }
  Serial.print(" | RSSI: ");
  Serial.println(LoRa.packetRssi());

  digitalWrite(LED_PIN, HIGH); // Turn on built-in LED instantly
  packetReceived = true;
}

void setup() {
  Serial.begin(115200);
  while (!Serial)
    ;

  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // Apply corrected pins
  LoRa.setPins(ss, rst, dio0);

  // Forced hardware module reset
  pinMode(rst, OUTPUT);
  digitalWrite(rst, LOW);
  delay(20);
  digitalWrite(rst, HIGH);
  delay(20);

  if (!LoRa.begin(433E6)) {
    Serial.println("❌ LoRa Begin Failed! Check SPI wiring.");
    while (1)
      ;
  }

  // Explicit standard matching radio settings
  LoRa.setSignalBandwidth(125E3);
  LoRa.setSpreadingFactor(7);
  LoRa.setCodingRate4(5);
  LoRa.setSyncWord(0xF3);

  // Enable background listening
  LoRa.onReceive(onReceive);
  LoRa.receive();

  Serial.println("📡 Node Ready on DIO0 -> GPIO 26!");
}

void loop() {
  // Clear the LED blink smoothly outside the interrupt loop
  if (packetReceived) {
    delay(100);
    digitalWrite(LED_PIN, LOW);
    packetReceived = false;
  }

  // Regular non-blocking transmission loop
  if (millis() - lastSendTime > sendInterval) {
    String message = "Hello from Node " + String(msgCount);

    Serial.print("<- Sending: ");
    Serial.println(message);

    LoRa.beginPacket();
    LoRa.print(message);
    LoRa.endPacket(); // Send message over the air

    msgCount++;
    lastSendTime = millis();

    // CRITICAL: Instantly reopen background listening mode
    LoRa.receive();
  }
}
