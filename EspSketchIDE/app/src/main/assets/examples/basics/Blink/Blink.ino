// Blink: turn the on-board LED on for a second, then off for a second.

// Most ESP32 boards define LED_BUILTIN; if yours does not, set the pin here.
#ifndef LED_BUILTIN
#define LED_BUILTIN 2
#endif

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_BUILTIN, HIGH);
  delay(1000);
  digitalWrite(LED_BUILTIN, LOW);
  delay(1000);
}
// Note: on ESP8266 boards the LED is wired active-low, so HIGH turns it off.
