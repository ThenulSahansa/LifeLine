#include <SPI.h>
#include <LoRa.h>
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled! Please run make menuconfig to verify it
#endif

BluetoothSerial SerialBT;

// Pin Configurations
#define SS      5    
#define RST     14  
#define DIO0    26  
#define LED_PIN 2 // Standard onboard LED (Usually GPIO 2)

#define LORA_FREQUENCY 433E6
const char* BLUETOOTH_NAME = "LifeLine_Node_001";

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
// Checks if the user typed text into the Bluetooth terminal
// =========================================================================
void checkSerialInput() {
  if (SerialBT.available()) {
    // FIXED: Swapped to readStringUntil to clean out app-specific line break fragments
    String inputMessage = SerialBT.readStringUntil('\n');
    inputMessage.trim();
   
    if (inputMessage.length() > 0){
      sendLoRaMessage("LifeLine_Node_001: " + inputMessage);
    }
  }
}

void setup() {
  Serial.begin(115200);
  while (!Serial);
 
  SerialBT.begin(BLUETOOTH_NAME);
  Serial.println("Bluetooth service started!");
 
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
    while (1);
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
  // 1. PROACTIVELY CHECK FOR OUTBOUND BLUETOOTH MESSAGES
  checkSerialInput();

  // 2. NON-BLOCKING BACKGROUND RECEIVE CHECK
  int packetSize = LoRa.parsePacket();
 
  if (packetSize) {
    // BLINK LED ON: Turn on immediately when data code is receiving
    digitalWrite(LED_PIN, HIGH);

    String incomingMessage = "";
    while (LoRa.available()) {
      incomingMessage += (char)LoRa.read();
    }

    // Print out what the other node sent you locally
    Serial.print("\n[SUCCESS] Received: ");
    Serial.println(incomingMessage);
   
    // Push the text to the mobile phone app over Bluetooth
    SerialBT.println(incomingMessage);
   
    Serial.print("[RSSI]: ");
    Serial.print(LoRa.packetRssi());
    Serial.println(" dBm");

    // Short visible flash duration for the LED, then turn it off
    delay(100);
    digitalWrite(LED_PIN, LOW);
  }

  // FIXED: Removed the blocking delay(20); to allow maximum polling frequency
}