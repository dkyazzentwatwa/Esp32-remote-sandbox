#!/usr/bin/env python3
"""Build app/src/main/assets/libraries/catalog.json from Arduino's library index.

The app offers a short, curated list of libraries students actually use (not all ~7000), each
pinned to the latest release in the index with its URL, SHA-256 and size. Dependencies listed in
the index are added automatically (shown only as dependencies). Re-run to update versions:
    tools/make-library-catalog.py [library_index.json.gz]
"""
import gzip
import hashlib
import io
import json
import pathlib
import sys
import urllib.request
import zipfile

INDEX_URL = "https://downloads.arduino.cc/libraries/library_index.json.gz"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/libraries/catalog.json"
BOARDS = {"esp32", "esp8266"}

# (index name, category, what a student would use it for)
CURATED = [
    ("DHT sensor library", "Sensors", "DHT11 and DHT22 temperature and humidity sensors."),
    ("Adafruit BMP280 Library", "Sensors", "BMP280 air pressure and temperature sensor."),
    ("Adafruit BME280 Library", "Sensors", "BME280 temperature, humidity and pressure sensor."),
    ("Adafruit MPU6050", "Sensors", "MPU6050 accelerometer and gyroscope (motion, tilt)."),
    ("OneWire", "Sensors", "1-Wire bus, needed by DS18B20 temperature probes."),
    ("DallasTemperature", "Sensors", "DS18B20 waterproof temperature probes (use with OneWire)."),
    ("NewPing", "Sensors", "HC-SR04 ultrasonic distance sensor."),
    ("HCSR04", "Sensors", "HC-SR04 ultrasonic distance sensor, simple version."),
    ("MFRC522", "Sensors", "RC522 RFID card and tag reader."),
    ("RTClib", "Sensors", "DS3231 / DS1307 real-time clock modules."),
    ("Adafruit SSD1306", "Displays", "Small 128×64 or 128×32 OLED displays (SSD1306)."),
    ("ESP8266 and ESP32 OLED driver for SSD1306 displays", "Displays", "SSD1306 OLED displays, a simpler driver made for ESP boards."),
    ("LCD_I2C", "Displays", "16×2 and 20×4 character LCDs with an I2C backpack."),
    ("TM1637", "Displays", "4-digit 7-segment displays (TM1637)."),
    ("Adafruit NeoPixel", "LEDs and motors", "WS2812 / NeoPixel addressable RGB LED strips and rings."),
    ("ESP32Servo", "LEDs and motors", "Hobby servo motors on the ESP32."),
    ("IRremoteESP8266", "LEDs and motors", "Send and receive infrared remote-control codes."),
    ("Keypad", "Input", "Matrix keypads (4×4, 4×3)."),
    ("PubSubClient", "Internet", "MQTT: send and receive messages through a broker (IoT dashboards)."),
    ("ArduinoJson", "Internet", "Read and write JSON, e.g. from web APIs."),
    ("WiFiManager", "Internet", "Set the Wi-Fi network from your phone instead of in the code."),
]

# Dependencies the index doesn't declare but the library needs to compile.
EXTRA_DEPENDENCIES = {"DallasTemperature": ["OneWire"]}


def version_key(version):
    parts = []
    for part in version.split("."):
        digits = "".join(c for c in part if c.isdigit())
        parts.append(int(digits) if digits else 0)
    return parts


def headers_in(release):
    """Headers a sketch can #include from this release: those in src/ (1.5 format) or the root."""
    data = urllib.request.urlopen(release["url"]).read()
    expected = release["checksum"].removeprefix("SHA-256:")
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit(f"checksum mismatch for {release['url']}")
    names = zipfile.ZipFile(io.BytesIO(data)).namelist()
    top = names[0].split("/")[0] + "/" if all(n.startswith(names[0].split("/")[0] + "/") for n in names) else ""
    rel = [n[len(top):] for n in names]
    has_src = any(n.startswith("src/") for n in rel) and "library.properties" in rel
    folder = "src/" if has_src else ""
    return sorted({n[len(folder):] for n in rel if n.startswith(folder) and "/" not in n[len(folder):]
                   and n.lower().endswith((".h", ".hpp"))})


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else None
    raw = open(source, "rb").read() if source else urllib.request.urlopen(INDEX_URL).read()
    releases = json.loads(gzip.decompress(raw))["libraries"]
    latest = {}
    for r in releases:
        if r["name"] not in latest or version_key(r["version"]) > version_key(latest[r["name"]]["version"]):
            latest[r["name"]] = r

    def entry(name, category, description, dependency):
        r = latest[name]
        archs = r.get("architectures") or ["*"]
        if "*" not in archs and not BOARDS & {a.lower() for a in archs}:
            raise SystemExit(f"{name} doesn't support esp32/esp8266: {archs}")
        return {
            "name": name,
            "version": r["version"],
            "category": category,
            "description": description or r.get("sentence", ""),
            "author": r.get("author", ""),
            "url": r["url"],
            "archiveFileName": r["archiveFileName"],
            "sha256": r["checksum"].removeprefix("SHA-256:"),
            "size": r["size"],
            "architectures": archs,
            "headers": headers_in(r),
            "dependencies": [d["name"] for d in r.get("dependencies", [])] + EXTRA_DEPENDENCIES.get(name, []),
            "dependency": dependency,
        }

    out = [entry(name, category, description, False) for name, category, description in CURATED]
    known = {e["name"] for e in out}
    queue = [d for e in out for d in e["dependencies"]]
    while queue:
        name = queue.pop(0)
        if name in known:
            continue
        dep = entry(name, "Dependencies", None, True)
        out.append(dep)
        known.add(name)
        queue.extend(dep["dependencies"])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"libraries": out}, indent=2, ensure_ascii=False) + "\n")
    print(f"{len(out)} libraries -> {OUT}")


if __name__ == "__main__":
    main()
