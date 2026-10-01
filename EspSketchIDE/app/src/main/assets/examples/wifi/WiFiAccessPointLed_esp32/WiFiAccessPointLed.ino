// WiFiAccessPointLed: control the LED from your phone's browser.
//
// The board creates its own WiFi network, so no router is needed:
//   1. Upload, then connect your phone to the WiFi network "EspSketch-LED"
//      (password: circuithub).
//   2. Open http://192.168.4.1 in the browser and tap the buttons.
// Change the network name and password below if several boards are nearby.

#include <WiFi.h>
#include <WebServer.h>

const char *NETWORK_NAME = "EspSketch-LED";
const char *PASSWORD = "circuithub";  // at least 8 characters
const int LED_PIN = 2;

WebServer server(80);
bool ledOn = false;

void sendPage() {
  String page = "<!DOCTYPE html><html><head>"
                "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                "<title>ESP32 LED</title></head><body style='font-family:sans-serif;text-align:center'>"
                "<h1>ESP32 LED</h1><p>The LED is <b>";
  page += ledOn ? "ON" : "OFF";
  page += "</b></p><p><a href='/on'><button style='font-size:2em'>ON</button></a> "
          "<a href='/off'><button style='font-size:2em'>OFF</button></a></p></body></html>";
  server.send(200, "text/html", page);
}

void setLed(bool on) {
  ledOn = on;
  digitalWrite(LED_PIN, on ? HIGH : LOW);
  sendPage();
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);

  WiFi.softAP(NETWORK_NAME, PASSWORD);
  Serial.print("Connect to ");
  Serial.print(NETWORK_NAME);
  Serial.print(" and open http://");
  Serial.println(WiFi.softAPIP());

  server.on("/", sendPage);
  server.on("/on", []() { setLed(true); });
  server.on("/off", []() { setLed(false); });
  server.begin();
}

void loop() {
  server.handleClient();
}
