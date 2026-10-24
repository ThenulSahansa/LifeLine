#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <LoRa.h>
#include <SPI.h>

// =========================================================================
// HARDWARE PIN DEFINITIONS
// =========================================================================
#define SS 5
#define RST 14
#define DIO0 26
#define LED_PIN 2 // Built-in LED on ESP32

// LoRa Radio Parameters
#define LORA_FREQUENCY 433E6

// BLE Parameters & Nordic UART Service (NUS) UUIDs matching LifeLine Android
// App

#define NODE_NUMBER "001"

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
// MESH NETWORKING ENGINE & LOOP PREVENTION
// =========================================================================

// =========================================================================
// SENT MESSAGE TRACKING BUFFER (FOR ACK MATCHING)
// =========================================================================
#define MAX_SENT_MSGS 64
String sentMsgIds[MAX_SENT_MSGS];
int sentMsgCount = 0;

void recordSentMessageId(const String &msgId) {
  if (msgId.length() == 0) return;
  sentMsgIds[sentMsgCount % MAX_SENT_MSGS] = msgId;
  sentMsgCount++;
  Serial.print("[LoRa Mesh] Recorded outbound Message ID: ");
  Serial.println(msgId);
}

bool isOurSentMessage(const String &ackId) {
  if (ackId.length() == 0) return false;
  int limit = (sentMsgCount < MAX_SENT_MSGS) ? sentMsgCount : MAX_SENT_MSGS;
  for (int i = 0; i < limit; i++) {
    if (sentMsgIds[i].length() > 0) {
      if (sentMsgIds[i] == ackId || ackId.indexOf(sentMsgIds[i]) >= 0 || sentMsgIds[i].indexOf(ackId) >= 0) {
        return true;
      }
    }
  }
  return false;
}

// Checks if targetNode is present in nodeChain (space/delimiter separated)
bool isNodeInChain(const String &chain, const String &targetNode) {
  int start = 0;
  int len = chain.length();
  while (start < len) {
    // Skip any leading whitespace or delimiters
    while (start < len && (chain[start] == ' ' || chain[start] == '\t' || chain[start] == ',' || chain[start] == '{' || chain[start] == '}')) {
      start++;
    }
    if (start >= len) break;
    int end = start;
    while (end < len && chain[end] != ' ' && chain[end] != '\t' && chain[end] != ',' && chain[end] != ':' && chain[end] != '}' && chain[end] != '{') {
      end++;
    }
    String token = chain.substring(start, end);
    token.trim();

    if (token.equalsIgnoreCase(targetNode)) {
      return true;
    }

    // Also match numeric values if both are numeric (e.g. "2" matches "002")
    if (token.length() > 0 && targetNode.length() > 0) {
      bool tokenNumeric = true;
      for (unsigned int i = 0; i < token.length(); i++) {
        if (!isDigit(token[i])) { tokenNumeric = false; break; }
      }
      bool targetNumeric = true;
      for (unsigned int i = 0; i < targetNode.length(); i++) {
        if (!isDigit(targetNode[i])) { targetNumeric = false; break; }
      }
      if (tokenNumeric && targetNumeric && token.toInt() == targetNode.toInt()) {
        return true;
      }
    }

    start = end;
  }
  return false;
}

// Inspects incoming LoRa packet and forwards SOS/ACK messages if this node is not yet in chain
void processMeshForwarding(const String &incomingMessage) {
  // 1. Process SOS Emergency Broadcasts
  int sosIndex = incomingMessage.indexOf("[SOS]");
  if (sosIndex >= 0) {
    // Extract node chain before [SOS]
    // Standard format: <Node Chain>: [SOS] : <Payload>
    int colonIndex = incomingMessage.lastIndexOf(':', sosIndex);
    String nodeChain = "";
    if (colonIndex >= 0) {
      nodeChain = incomingMessage.substring(0, colonIndex);
    } else {
      nodeChain = incomingMessage.substring(0, sosIndex);
    }
    nodeChain.trim();

    // Stop circulations: if current node is already in the chain, do not forward
    if (isNodeInChain(nodeChain, NODE_NUMBER)) {
      Serial.print("[LoRa Mesh] Node " NODE_NUMBER " already in chain (");
      Serial.print(nodeChain);
      Serial.println("). Dropping packet to stop circulation.");
      return;
    }

    // Construct forwarded message by appending current node to the chain
    String newChain = (nodeChain.length() > 0) ? (nodeChain + " " + NODE_NUMBER) : String(NODE_NUMBER);
    String forwardedMessage = newChain + ": " + incomingMessage.substring(sosIndex);

    Serial.print("[LoRa Mesh] Forwarding SOS (Added " NODE_NUMBER " to chain): ");
    Serial.println(forwardedMessage);

    // Random backoff delay (150ms - 350ms) to avoid collision when multiple mesh nodes forward
    delay(random(150, 350));
    sendLoRaMessage(forwardedMessage);
    return;
  }

  // 2. Process ACK Delivery Confirmations
  int ackIndex = incomingMessage.indexOf("[ACK]");
  if (ackIndex >= 0) {
    // Format: <Node chain>: [ACK] : <base85 msgId>
    int colonAfterAck = incomingMessage.indexOf(':', ackIndex + 5);
    String ackId = (colonAfterAck >= 0) ? incomingMessage.substring(colonAfterAck + 1) : incomingMessage.substring(ackIndex + 5);
    ackId.trim();

    // Strip wrapping braces or quotes if present
    while (ackId.length() > 0 && (ackId[0] == '<' || ackId[0] == '{' || ackId[0] == ' ')) {
      ackId = ackId.substring(1);
    }
    while (ackId.length() > 0 && (ackId[ackId.length() - 1] == '>' || ackId[ackId.length() - 1] == '}' || ackId[ackId.length() - 1] == ' ')) {
      ackId = ackId.substring(0, ackId.length() - 1);
    }
    ackId.trim();

    // If the ACK is for our current node, mark delivered and DROP message
    if (isOurSentMessage(ackId)) {
      Serial.print("[LoRa Mesh] [ACK] Delivery confirmed for our message ID: ");
      Serial.println(ackId);
      Serial.println("[LoRa Mesh] [ACK] Dropping packet (reached destination originator).");
      return;
    }

    // If the message is not for us, forward it across the mesh
    int colonBeforeAck = incomingMessage.lastIndexOf(':', ackIndex);
    String nodeChain = "";
    if (colonBeforeAck >= 0) {
      nodeChain = incomingMessage.substring(0, colonBeforeAck);
    } else {
      nodeChain = incomingMessage.substring(0, ackIndex);
    }
    nodeChain.trim();

    // Stop circulation loops for ACK
    if (isNodeInChain(nodeChain, NODE_NUMBER)) {
      Serial.print("[LoRa Mesh] Node " NODE_NUMBER " already in ACK chain (");
      Serial.print(nodeChain);
      Serial.println("). Dropping packet to stop circulation.");
      return;
    }

    String newChain = (nodeChain.length() > 0) ? (nodeChain + " " + NODE_NUMBER) : String(NODE_NUMBER);
    String forwardedAck = newChain + ": [ACK] : " + ackId;

    Serial.print("[LoRa Mesh] Forwarding ACK (Added " NODE_NUMBER " to chain): ");
    Serial.println(forwardedAck);

    delay(random(150, 350));
    sendLoRaMessage(forwardedAck);
    return;
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

    // Extract message ID if present (<message id> : <timestamp> | ...) and track it
    int colonIdx = bleMessage.indexOf(':');
    if (colonIdx > 0) {
      String msgId = bleMessage.substring(0, colonIdx);
      msgId.trim();
      recordSentMessageId(msgId);
    }

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

  LoRa.setPins(SS, RST, DIO0);

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

    // Mesh network forwarding for SOS packets
    processMeshForwarding(incomingMessage);
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
