// Button: print a message while a button is held.
// GPIO 0 is the BOOT (ESP32) / FLASH (ESP8266) button on most dev boards.
// The pin uses the internal pull-up, so pressing the button reads LOW.

const int BUTTON_PIN = 0;

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
}

void loop() {
  if (digitalRead(BUTTON_PIN) == LOW) {
    Serial.println("Button pressed");
    delay(200);
  }
}
