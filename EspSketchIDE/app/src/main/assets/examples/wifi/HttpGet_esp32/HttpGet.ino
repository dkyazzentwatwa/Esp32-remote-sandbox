// HttpGet: join Wi-Fi, fetch a web page over plain HTTP and print the start of it.

#include <WiFi.h>
#include <HTTPClient.h>

// Replace with your network.
const char* WIFI_SSID = "your-network";
const char* WIFI_PASSWORD = "your-password";

const char* URL = "http://example.com/";

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  WiFiClient client;
  HTTPClient http;
  if (http.begin(client, URL)) {
    int code = http.GET();
    Serial.printf("HTTP status: %d\n", code);
    if (code == HTTP_CODE_OK) {
      String body = http.getString();
      Serial.println(body.substring(0, 300));
    }
    http.end();
  } else {
    Serial.println("Could not start the request");
  }
}

void loop() {

}
