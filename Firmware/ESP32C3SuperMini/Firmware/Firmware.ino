#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <LoRa.h>
#include <SPI.h>

// =========================================================================
// HARDWARE PIN DEFINITIONS
// =========================================================================

#define LORA_SCK 4
#define LORA_MISO 5
#define LORA_MOSI 6
#define SS 7
#define RST 1
#define DIO0 0
#define LED_PIN 8 // ESP32-C3 Super Mini onboard blue LED (active LOW)

// LoRa Radio Parameters
#define LORA_FREQUENCY 433E6

// BLE Parameters & Nordic UART Service (NUS) UUIDs matching LifeLine Android
// App
#define NODE_NUMBER = "002";

const char *BLE_DEVICE_NAME = "LifeLine_Node_" NODE_NUMBER;

#define SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX                                                 \
  "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // Phone -> Node (Write)
#define CHARACTERISTIC_UUID_TX                                                 \
  "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // Node -> Phone (Notify)

BLEServer *pServer = nullptr;
BLECharacteristic *pTxCharacteristic = nullptr;
BLECharacteristic *pRxCharacteristic = nullptr;

bool deviceConnected = false;
bool oldDeviceConnected = false;

// Buffer for incoming BLE data
String bleRxAccumulator = "";
unsigned long lastBleRxTime = 0;
portMUX_TYPE bleMux = portMUX_INITIALIZER_UNLOCKED;

// =========================================================================
// BLE CALLBACKS
// =========================================================================
class LifeLineServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *pServer) override {
    deviceConnected = true;
    Serial.println("\n[BLE] Smartphone connected to LifeLine BLE node!");
  }

  void onDisconnect(BLEServer *pServer) override {
    deviceConnected = false;
    Serial.println("\n[BLE] Smartphone disconnected.");
  }
};

class LifeLineRxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) override {
    String rxValue = pCharacteristic->getValue();
    if (rxValue.length() > 0) {
      portENTER_CRITICAL(&bleMux);
      bleRxAccumulator += rxValue;
      lastBleRxTime = millis();
      portEXIT_CRITICAL(&bleMux);
    }
  }
};

// =========================================================================
// LORA TRANSMISSION FUNCTION
// Sends custom string payload and instantly restores background receiving
// =========================================================================
void sendLoRaMessage(String message) {
  Serial.print("\n-> Sending Outbound LoRa Payload: ");
  Serial.println(message);

  // Blink LED to indicate transmission
  digitalWrite(LED_PIN, HIGH);

  // Package and broadcast the string data
  LoRa.beginPacket();
  LoRa.print(message);
  LoRa.endPacket(); // Briefly switches radio to TX mode to push the data out

  // Clear out underlying hardware register pipelines
  LoRa.flush();

  // Turn LED back off after TX
  digitalWrite(LED_PIN, LOW);

  // CRITICAL: Instantly re-engage continuous background listening profile
  LoRa.receive();
}

// =========================================================================
// SEND MESSAGE OVER BLE TO CONNECTED PHONE
// =========================================================================
void sendBleMessage(const String &message) {
  if (deviceConnected && pTxCharacteristic != nullptr) {
    pTxCharacteristic->setValue((uint8_t *)message.c_str(), message.length());
    pTxCharacteristic->notify();
    Serial.print("[BLE] Sent notify to phone: ");
    Serial.println(message);
  }
}

// =========================================================================
// BLE & SERIAL INPUT PROCESSING ENGINE
// =========================================================================
void checkInputs() {
  // 1. Process data received over Bluetooth Low Energy
  String bleMessage = "";
  portENTER_CRITICAL(&bleMux);
  if (bleRxAccumulator.length() > 0) {
    int newlineIdx = bleRxAccumulator.indexOf('\n');
    if (newlineIdx >= 0) {
      bleMessage = bleRxAccumulator.substring(0, newlineIdx);
      bleRxAccumulator = bleRxAccumulator.substring(newlineIdx + 1);
    } else if (millis() - lastBleRxTime > 350) {
      // Idle timeout: process message even if client omitted trailing newline
      bleMessage = bleRxAccumulator;
      bleRxAccumulator = "";
    }
  }
  portEXIT_CRITICAL(&bleMux);

  bleMessage.trim();
  if (bleMessage.length() > 0) {
    Serial.print("\n[BLE RX] Received from App: ");
    Serial.println(bleMessage);
    sendLoRaMessage(String(NODE_NUMBER) + ": [SOS] : " + bleMessage);
  }
}

void setup() {
  Serial.begin(115200);
  while (!Serial && millis() < 3000)
    ;

  // Initialize Built-in LED pin
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  Serial.println("\n=============================================");
  Serial.println("--- BOOTING LIFELINE NODE ---");
  Serial.println("=============================================");

  // 1. Initialize Bluetooth Low Energy (BLE)
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(517);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new LifeLineServerCallbacks());

  // Create the Nordic UART Service (NUS)
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Create TX Characteristic (Node -> Phone, Notify)
  pTxCharacteristic = pService->createCharacteristic(
      CHARACTERISTIC_UUID_TX, BLECharacteristic::PROPERTY_NOTIFY);
  pTxCharacteristic->addDescriptor(new BLE2902());

  // Create RX Characteristic (Phone -> Node, Write)
  pRxCharacteristic = pService->createCharacteristic(
      CHARACTERISTIC_UUID_RX,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  pRxCharacteristic->setCallbacks(new LifeLineRxCallbacks());

  // Start the service
  pService->start();

  // Start advertising
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06); // Helps with client connection latency
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.print("[BLE] Advertising started as: ");
  Serial.println(BLE_DEVICE_NAME);

  // 2. Hardware reset sequence & pins for LoRa (SX1278)
  pinMode(SS, OUTPUT);
  pinMode(DIO0, INPUT);

  pinMode(RST, OUTPUT);
  digitalWrite(RST, LOW);
  delay(20);
  digitalWrite(RST, HIGH);
  delay(20);

  SPI.begin(LORA_SCK, LORA_MISO, LORA_MOSI, SS);

  LoRa.setPins(SS, RST, DIO0);

  Serial.println("Initializing LoRa radio at 433 MHz...");
  if (!LoRa.begin(LORA_FREQUENCY)) {
    Serial.println("[CRITICAL] LoRa initialization failed!");
    while (1) {
      digitalWrite(LED_PIN, HIGH);
      delay(200);
      digitalWrite(LED_PIN, LOW);
      delay(200);
    }
  }

  // Stable default radio profiles
  LoRa.setTxPower(17);
  LoRa.setSpreadingFactor(7);
  LoRa.setSignalBandwidth(125E3);
  LoRa.setCodingRate4(5);

  // Start up continuous background receiving mode
  LoRa.receive();

  Serial.println("[LoRa] Radio initialized successfully at 433 MHz.");
  Serial.println("Waiting for LoRa packets or BLE connections...\n");
}

void loop() {
  // 1. PROACTIVELY CHECK FOR OUTBOUND BLE / SERIAL MESSAGES
  checkInputs();

  // 2. NON-BLOCKING BACKGROUND LORA RECEIVE CHECK
  int packetSize = LoRa.parsePacket();

  if (packetSize) {
    // BLINK LED ON: Turn on immediately when data packet is receiving
    digitalWrite(LED_PIN, HIGH);

    String incomingMessage = "";
    while (LoRa.available()) {
      incomingMessage += (char)LoRa.read();
    }

    // Print out what was received locally to Serial Monitor
    Serial.print("\n[SUCCESS] Received LoRa: ");
    Serial.println(incomingMessage);
    Serial.print("[RSSI]: ");
    Serial.print(LoRa.packetRssi());
    Serial.println(" dBm");

    // Push the received message to the mobile phone app over BLE
    sendBleMessage(incomingMessage);

    // Short visible flash duration for the LED, then turn it off
    delay(100);
    digitalWrite(LED_PIN, LOW);
  }

  // 3. HANDLE BLE DISCONNECTION & AUTO-RESTART ADVERTISING
  if (!deviceConnected && oldDeviceConnected) {
    delay(500); // give the bluetooth stack a moment to settle
    pServer->startAdvertising();
    Serial.println("[BLE] Device disconnected. Restarted advertising...");
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }
}
