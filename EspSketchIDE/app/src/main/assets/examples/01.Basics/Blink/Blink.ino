// Blink: turn an LED on and off every second.
//
// Most ESP32 dev boards have a blue LED on GPIO 2. If yours doesn't blink,
// wire an LED + 220 ohm resistor from GPIO 2 to GND, or change LED_PIN.

const int LED_PIN = 2;

void setup() {
  pinMode(LED_PIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_PIN, HIGH);  // LED on
  delay(1000);                  // wait one second (1000 milliseconds)
  digitalWrite(LED_PIN, LOW);   // LED off
  delay(1000);
}
