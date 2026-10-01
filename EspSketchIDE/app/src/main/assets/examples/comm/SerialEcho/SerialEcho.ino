// SerialEcho: send back everything received on the serial port.

void setup() {
  Serial.begin(115200);
  Serial.println("Type something and it will be echoed back.");
}

void loop() {
  while (Serial.available() > 0) {
    int c = Serial.read();
    Serial.write(c);
  }
}
