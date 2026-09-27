// Button: light the LED while a button is pressed.
//
// Wire a push button between GPIO 4 and GND. INPUT_PULLUP keeps the pin HIGH
// until the button connects it to GND, so "pressed" reads LOW.

const int BUTTON_PIN = 4;
const int LED_PIN = 2;

void setup() {
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
}

void loop() {
  bool pressed = digitalRead(BUTTON_PIN) == LOW;
  digitalWrite(LED_PIN, pressed ? HIGH : LOW);
}
