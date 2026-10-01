// NtpClock: join Wi-Fi, sync the clock over NTP and print the local time every second.

#include <ESP8266WiFi.h>
#include <time.h>

// Replace with your network.
const char* WIFI_SSID = "your-network";
const char* WIFI_PASSWORD = "your-password";

const char* NTP_SERVER = "pool.ntp.org";
// Offset from UTC in seconds (3600 = UTC+1) and daylight saving offset in seconds.
const long GMT_OFFSET_SEC = 0;
const int DAYLIGHT_OFFSET_SEC = 0;

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  configTime(GMT_OFFSET_SEC, DAYLIGHT_OFFSET_SEC, NTP_SERVER);
}

void loop() {
  time_t now = time(nullptr);
  // Before the first sync the clock reads a date in 1970.
  if (now > 8 * 3600 * 24) {
    struct tm timeInfo;
    localtime_r(&now, &timeInfo);
    char text[32];
    strftime(text, sizeof(text), "%Y-%m-%d %H:%M:%S", &timeInfo);
    Serial.println(text);
  } else {
    Serial.println("Waiting for the time...");
  }
  delay(1000);
}
