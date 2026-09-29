// WiFiScan: list the Wi-Fi networks in range.

#include <WiFi.h>

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  delay(100);
}

void loop() {
  Serial.println("Scanning...");
  int count = WiFi.scanNetworks();
  if (count <= 0) {
    Serial.println("No networks found");
  } else {
    for (int i = 0; i < count; i++) {
      Serial.printf("%2d: %-32s  %4d dBm  %s\n", i + 1, WiFi.SSID(i).c_str(), WiFi.RSSI(i),
                    WiFi.encryptionType(i) == WIFI_AUTH_OPEN ? "open" : "secured");
    }
  }
  WiFi.scanDelete();
  delay(10000);
}
