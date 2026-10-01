// BlinkWithoutDelay: blink the LED with millis() so the loop is never blocked.

#ifndef LED_BUILTIN
#define LED_BUILTIN 2
#endif

const unsigned long INTERVAL_MS = 500;

bool ledOn = false;
unsigned long lastToggle = 0;

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
  unsigned long now = millis();
  if (now - lastToggle >= INTERVAL_MS) {
    lastToggle = now;
    ledOn = !ledOn;
    digitalWrite(LED_BUILTIN, ledOn ? HIGH : LOW);
  }
  // Other work can run here without waiting for the LED.
}
