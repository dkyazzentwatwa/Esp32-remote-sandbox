// TouchSensor: read a capacitive touch pin (classic ESP32).
// Touch a wire connected to GPIO 4 (T0); the value drops when you touch it.
// Other chips (S2, S3) have different touch pins and value ranges.

const int TOUCH_PIN = 4;
const int THRESHOLD = 40;  // adjust after watching the values you get

void setup() {
  Serial.begin(115200);
  delay(500);
}

void loop() {
  int value = touchRead(TOUCH_PIN);
  Serial.print("touch=");
  Serial.print(value);
  Serial.println(value < THRESHOLD ? "  touched" : "");
  delay(300);
}
