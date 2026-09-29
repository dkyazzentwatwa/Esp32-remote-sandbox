// DeepSleepTimer: wake up, do something, and go back to deep sleep for 10 seconds.
// The board restarts from setup() after every sleep; RTC memory survives it.

const uint64_t SLEEP_SECONDS = 10;

RTC_DATA_ATTR int bootCount = 0;

void setup() {
  Serial.begin(115200);
  delay(500);
  bootCount++;
  Serial.printf("Wake-up number %d\n", bootCount);

  // ... read a sensor or send data here ...

  Serial.println("Going to sleep");
  Serial.flush();
  esp_sleep_enable_timer_wakeup(SLEEP_SECONDS * 1000000ULL);
  esp_deep_sleep_start();
}

void loop() {
  // Never reached: the board sleeps at the end of setup().
}
