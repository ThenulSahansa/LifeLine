#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <LoRa.h>
#include <SPI.h>

// Redefine your pins for the ESP32-C3 Super Mini
#define LORA_SCK 4
#define LORA_MISO 5
#define LORA_MOSI 6
#define LORA_SS 7
#define LORA_RST 1
#define LORA_DIO0 0

void setup() {
  Serial
      .begin(115200)
      // Manually initialize the SPI bus with the new C3 pins
      SPI.begin(LORA_SCK, LORA_MISO, LORA_MOSI, LORA_SS);

  // Explicitly pass these pins to your LoRa setup function
  LoRa.setPins(LORA_SS, LORA_RST, LORA_DIO0);

  if (!LoRa.begin(433E6)) { // adjusting for your region's module frequency
    Serial.println("Starting LoRa failed!");
    while (1)
      ;
  }
}
void loop() {}