package org.espsketchide.app.templates

import org.espsketchide.app.settings.Board

/** Starting points offered by the New sketch dialog. Bodies are plain constants so they are JVM-testable. */
object SketchTemplates {

    const val BARE = "bare"
    const val SERIAL = "serial"
    const val WIFI_STATION = "wifi_station"

    /** In dialog order; the first is the default. */
    val ids: List<String> = listOf(BARE, SERIAL, WIFI_STATION)

    fun contentFor(templateId: String, board: Board): String = when (templateId) {
        SERIAL -> SERIAL_TEMPLATE
        WIFI_STATION -> wifiStation(board)
        else -> BARE_TEMPLATE
    }

    private val BARE_TEMPLATE = """
        void setup() {
          // Runs once at start-up.

        }

        void loop() {
          // Runs over and over.

        }
    """.trimIndent() + "\n"

    private val SERIAL_TEMPLATE = """
        void setup() {
          Serial.begin(115200);
          delay(500);
          Serial.println("Hello from the ESP");
        }

        void loop() {
          Serial.println(millis());
          delay(1000);
        }
    """.trimIndent() + "\n"

    private fun wifiStation(board: Board): String {
        val header = when (board) {
            Board.ESP32 -> "WiFi.h"
            Board.ESP8266 -> "ESP8266WiFi.h"
        }
        return """
            #include <$header>

            // Replace with your network.
            const char* WIFI_SSID = "your-network";
            const char* WIFI_PASSWORD = "your-password";

            void setup() {
              Serial.begin(115200);
              WiFi.mode(WIFI_STA);
              WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
              Serial.print("Connecting");
              while (WiFi.status() != WL_CONNECTED) {
                delay(500);
                Serial.print(".");
              }
              Serial.println();
              Serial.print("IP address: ");
              Serial.println(WiFi.localIP());
            }

            void loop() {

            }
        """.trimIndent() + "\n"
    }
}
