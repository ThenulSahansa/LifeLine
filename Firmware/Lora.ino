#include <LoRa.h>
#include <SPI.h>


const int ss = 5;
const int rst = 14;
const int dio0 = 26;

void setup() {
  Serial.begin(115200);
  delay(1000); // Give the Serial Monitor time to connect

  Serial.println("--- Boot Debug Started ---");

  Serial.println("Configuring LoRa pins...");
  LoRa.setPins(ss, rst, dio0);

  Serial.println("Attempting to initialize LoRa chip...");
  // Try to initialize. If it hangs here, we know it's a wiring/SPI issue.
  if (!LoRa.begin(433E6)) {
    Serial.println("Result: Starting LoRa failed! Check wiring or frequency.");
    while (1)
      ;
  }

  Serial.println("Result: LoRa Initialized OK!");
}

void loop() {
  // Add your transmit or receive logic here
}
