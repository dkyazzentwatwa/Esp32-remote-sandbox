package org.espsketchide.app.editor

import org.espsketchide.app.settings.Board

/**
 * Names offered by autocomplete on top of the identifiers already in the file. [core] holds what
 * both cores share; [esp32] and [esp8266] hold only what is specific to that board, so the three
 * lists never overlap (pinned by ArduinoApiTest). Every entry must be a plain identifier because
 * sora's completer matches identifiers. Targets ESP32 Arduino core 3.x and ESP8266 core 3.x.
 */
object ArduinoApi {

    val core: List<String> = words(
        """
        setup loop yield
        pinMode digitalWrite digitalRead analogRead analogWrite
        delay delayMicroseconds millis micros
        attachInterrupt detachInterrupt digitalPinToInterrupt interrupts noInterrupts
        tone noTone pulseIn shiftIn shiftOut
        map constrain min max abs pow sqrt sq sin cos tan random randomSeed
        lowByte highByte bitRead bitWrite bitSet bitClear bit
        isDigit isAlpha isAlphaNumeric isSpace isUpperCase isLowerCase isPunct isHexadecimalDigit
        HIGH LOW INPUT OUTPUT INPUT_PULLUP LED_BUILTIN LSBFIRST MSBFIRST CHANGE RISING FALLING
        PI HALF_PI TWO_PI DEG_TO_RAD RAD_TO_DEG EULER
        String boolean byte word size_t
        uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t
        Serial begin end available read peek write flush print println printf
        setTimeout readString readStringUntil parseInt parseFloat
        Wire beginTransmission endTransmission requestFrom SPI
        length substring indexOf toInt toFloat toCharArray c_str trim replace
        startsWith endsWith equals concat toUpperCase toLowerCase
        WiFi WiFiClient WiFiClientSecure WiFiServer WiFiUDP IPAddress
        status localIP softAP softAPIP scanNetworks SSID RSSI disconnect macAddress mode
        setHostname gatewayIP subnetMask dnsIP
        WL_CONNECTED WL_IDLE_STATUS WL_NO_SSID_AVAIL WL_CONNECT_FAILED WL_DISCONNECTED
        WIFI_STA WIFI_AP WIFI_AP_STA WIFI_OFF
        HTTPClient GET POST addHeader getString getSize HTTP_CODE_OK
        configTime MDNS ArduinoOTA LittleFS EEPROM commit
        ESP restart getFreeHeap
        """
    )

    val esp32: List<String> = words(
        """
        ledcAttach ledcAttachChannel ledcDetach ledcWrite ledcRead ledcReadFreq ledcWriteTone ledcWriteNote
        touchRead touchAttachInterrupt dacWrite temperatureRead
        analogReadResolution analogSetAttenuation analogReadMilliVolts
        ADC_0db ADC_2_5db ADC_6db ADC_11db
        INPUT_PULLDOWN OUTPUT_OPEN_DRAIN IRAM_ATTR
        xTaskCreate xTaskCreatePinnedToCore vTaskDelay vTaskDelete pdMS_TO_TICKS portTICK_PERIOD_MS
        xSemaphoreCreateMutex xSemaphoreTake xSemaphoreGive xQueueCreate xQueueSend xQueueReceive
        esp_sleep_enable_timer_wakeup esp_sleep_enable_ext0_wakeup esp_sleep_enable_ext1_wakeup
        esp_sleep_get_wakeup_cause esp_deep_sleep_start esp_light_sleep_start esp_restart esp_random
        esp_err_t ESP_OK
        WebServer WiFiMulti Preferences getLocalTime SPIFFS
        putInt getInt putUInt getUInt putBool getBool putFloat getFloat putString isKey remove clear
        getChipModel getCpuFreqMHz getFlashChipSize getEfuseMac
        """
    )

    val esp8266: List<String> = words(
        """
        ESP8266WiFi ESP8266WebServer ESP8266WiFiMulti ESP8266mDNS
        A0 D0 D1 D2 D3 D4 D5 D6 D7 D8
        analogWriteRange analogWriteFreq ICACHE_RAM_ATTR
        deepSleep deepSleepInstant getChipId getResetReason reset wdtFeed wdtEnable getCoreVersion
        setSleepMode WIFI_NONE_SLEEP WIFI_LIGHT_SLEEP WIFI_MODEM_SLEEP
        """
    )

    /** [core] plus the board's own names, unique and sorted. */
    fun keywordsFor(board: Board): List<String> {
        val specific = when (board) {
            Board.ESP32 -> esp32
            Board.ESP8266 -> esp8266
        }
        return (core + specific).distinct().sorted()
    }

    private fun words(block: String): List<String> = block.split(Regex("\\s+")).filter { it.isNotEmpty() }
}
