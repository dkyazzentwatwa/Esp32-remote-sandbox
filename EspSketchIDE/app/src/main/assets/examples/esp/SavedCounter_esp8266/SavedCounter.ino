// SavedCounter: count restarts and keep the count in flash with EEPROM.

#include <EEPROM.h>

const int ADDRESS = 0;

void setup() {
  Serial.begin(115200);
  delay(500);

  EEPROM.begin(16);
  uint32_t boots = 0;
  EEPROM.get(ADDRESS, boots);
  if (boots == 0xFFFFFFFF) {
    boots = 0;  // erased flash reads as all ones
  }
  boots++;
  EEPROM.put(ADDRESS, boots);
  EEPROM.commit();

  Serial.printf("This is start number %u\n", (unsigned int)boots);
}

void loop() {

}
