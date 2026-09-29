// SavedCounter: count restarts and keep the count in flash with Preferences.

#include <Preferences.h>

Preferences prefs;

void setup() {
  Serial.begin(115200);
  delay(500);

  prefs.begin("counter", false);  // namespace "counter", read/write
  unsigned int boots = prefs.getUInt("boots", 0) + 1;
  prefs.putUInt("boots", boots);
  prefs.end();

  Serial.printf("This is start number %u\n", boots);
}

void loop() {

}
