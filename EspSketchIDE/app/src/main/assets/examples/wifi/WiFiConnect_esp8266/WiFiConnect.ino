// WiFiConnect: join a Wi-Fi network and print the IP address.

#include <ESP8266WiFi.h>

// Replace with your network.
const char* WIFI_SSID = "your-network";
const char* WIFI_PASSWORD = "your-password";

const unsigned long CONNECT_TIMEOUT_MS = 15000;

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting");
  unsigned long started = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - started < CONNECT_TIMEOUT_MS) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Connected, IP address: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("Could not connect. Check the network name and password.");
  }
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Connection lost");
    delay(5000);
  }
}
