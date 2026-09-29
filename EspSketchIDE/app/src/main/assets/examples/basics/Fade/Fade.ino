// Fade: smoothly brighten and dim the on-board LED with PWM (analogWrite).

#ifndef LED_BUILTIN
#define LED_BUILTIN 2
#endif

const int MAX_DUTY = 255;

int duty = 0;
int step = 5;

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
#if defined(ESP8266)
  // ESP8266 defaults to a 0..1023 range; use 0..255 like the other boards.
  analogWriteRange(MAX_DUTY);
#endif
}

void loop() {
  analogWrite(LED_BUILTIN, duty);
  duty += step;
  if (duty <= 0 || duty >= MAX_DUTY) {
    step = -step;
    duty = constrain(duty, 0, MAX_DUTY);
  }
  delay(20);
}
// Note: on ESP8266 boards the LED is active-low, so the fade looks inverted.
