// AnalogReadSerial: read a voltage and print it.
//
// Wire a potentiometer: outer legs to 3.3V and GND, middle leg to GPIO 34.
// The ESP32 reads 0 (0 V) to 4095 (3.3 V). Open the Serial Monitor at 115200.

const int SENSOR_PIN = 34;

void setup() {
  Serial.begin(115200);
}

void loop() {
  int value = analogRead(SENSOR_PIN);
  int millivolts = analogReadMilliVolts(SENSOR_PIN);
  Serial.print("raw: ");
  Serial.print(value);
  Serial.print("   millivolts: ");
  Serial.println(millivolts);
  delay(200);
}
