// AnalogRead: read a voltage on an analog pin and print it.
// GPIO 34 is input-only and belongs to ADC1, which works while Wi-Fi is on.

const int ANALOG_PIN = 34;

void setup() {
  Serial.begin(115200);
  analogReadResolution(12);  // readings from 0 to 4095
}

void loop() {
  int raw = analogRead(ANALOG_PIN);
  int millivolts = analogReadMilliVolts(ANALOG_PIN);
  Serial.print("raw=");
  Serial.print(raw);
  Serial.print("  mV=");
  Serial.println(millivolts);
  delay(500);
}
