// WiFiScan: list the WiFi networks around you.
//
// Open the Serial Monitor at 115200 baud. A new scan runs every 10 seconds.

#include <WiFi.h>

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
}

void loop() {
  Serial.println("Scanning...");
  int found = WiFi.scanNetworks();
  if (found == 0) {
    Serial.println("No networks found.");
  }
  for (int i = 0; i < found; i++) {
    Serial.print(i + 1);
    Serial.print(": ");
    Serial.print(WiFi.SSID(i));
    Serial.print("  (");
    Serial.print(WiFi.RSSI(i));
    Serial.print(" dBm)");
    Serial.println(WiFi.encryptionType(i) == WIFI_AUTH_OPEN ? "  open" : "");
  }
  WiFi.scanDelete();
  Serial.println();
  delay(10000);
}
