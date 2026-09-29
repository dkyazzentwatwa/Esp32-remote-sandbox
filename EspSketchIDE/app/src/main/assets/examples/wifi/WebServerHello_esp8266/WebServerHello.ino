// WebServerHello: a tiny web server. Open http://<board IP>/ in a browser on the same network.

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>

// Replace with your network.
const char* WIFI_SSID = "your-network";
const char* WIFI_PASSWORD = "your-password";

ESP8266WebServer server(80);

void handleRoot() {
  server.send(200, "text/plain", "Hello from the ESP8266!");
}

void handleNotFound() {
  server.send(404, "text/plain", "Not found");
}

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  Serial.print("Open http://");
  Serial.print(WiFi.localIP());
  Serial.println("/");

  server.on("/", handleRoot);
  server.onNotFound(handleNotFound);
  server.begin();
}

void loop() {
  server.handleClient();
}
