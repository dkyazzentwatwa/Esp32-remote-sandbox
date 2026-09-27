// Fade: smoothly brighten and dim an LED with PWM.
//
// analogWrite() switches the pin on and off very fast; the fraction of time it
// is on (0-255) sets the brightness.

const int LED_PIN = 2;

int brightness = 0;
int step = 5;

void setup() {
  pinMode(LED_PIN, OUTPUT);
}

void loop() {
  analogWrite(LED_PIN, brightness);
  brightness = brightness + step;
  if (brightness <= 0 || brightness >= 255) {
    step = -step;  // reverse direction at either end
  }
  delay(30);
}
