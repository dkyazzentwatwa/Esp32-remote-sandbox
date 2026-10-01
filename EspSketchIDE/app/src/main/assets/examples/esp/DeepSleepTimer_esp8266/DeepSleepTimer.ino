// DeepSleepTimer: wake up, do something, and go back to deep sleep for 10 seconds.
// To wake up, connect GPIO16 (D0) to RST. The board restarts from setup() after every sleep.

const uint32_t SLEEP_SECONDS = 10;

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("Awake");

  // ... read a sensor or send data here ...

  Serial.println("Going to sleep");
  Serial.flush();
  ESP.deepSleep(SLEEP_SECONDS * 1000000ULL);
}

void loop() {
  // Never reached: the board sleeps at the end of setup().
}
