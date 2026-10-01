// AnalogRead: read a voltage on the analog pin and print it.
// The ESP8266 has one analog input, A0, with a 10-bit range (0 to 1023).
// Most boards accept 0 to 3.2 V there (NodeMCU has a divider for 3.3 V).

void setup() {
  Serial.begin(115200);
}

void loop() {
  int raw = analogRead(A0);
  Serial.print("raw=");
  Serial.println(raw);
  delay(500);
}
