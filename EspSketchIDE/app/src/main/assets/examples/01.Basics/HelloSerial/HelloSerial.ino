// HelloSerial: send messages from the board to the Serial Monitor.
//
// Open the Serial Monitor at 115200 baud to see the output.

int count = 0;

void setup() {
  Serial.begin(115200);
  Serial.println("Hello from the ESP32!");
}

void loop() {
  count = count + 1;
  Serial.print("Loop number ");
  Serial.println(count);
  delay(1000);
}
