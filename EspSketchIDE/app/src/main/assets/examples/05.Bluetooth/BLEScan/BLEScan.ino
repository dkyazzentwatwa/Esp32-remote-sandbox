// BLEScan: find Bluetooth Low Energy devices nearby.
//
// Open the Serial Monitor at 115200 baud. Each scan lasts 5 seconds.

#include <BLEDevice.h>
#include <BLEScan.h>

const int SCAN_SECONDS = 5;
BLEScan *scanner;

void setup() {
  Serial.begin(115200);
  BLEDevice::init("");
  scanner = BLEDevice::getScan();
  scanner->setActiveScan(true);  // ask devices for their names
}

void loop() {
  Serial.println("Scanning...");
  BLEScanResults *results = scanner->start(SCAN_SECONDS, false);
  for (int i = 0; i < results->getCount(); i++) {
    BLEAdvertisedDevice device = results->getDevice(i);
    Serial.print(device.getAddress().toString().c_str());
    Serial.print("  RSSI ");
    Serial.print(device.getRSSI());
    if (device.haveName()) {
      Serial.print("  ");
      Serial.print(device.getName().c_str());
    }
    Serial.println();
  }
  Serial.print(results->getCount());
  Serial.println(" devices found.");
  scanner->clearResults();
  delay(2000);
}
