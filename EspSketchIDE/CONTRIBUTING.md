# Contributing to EspSketchIDE

Thanks for helping! EspSketchIDE is aimed at students, so clarity and honesty
matter as much as features.

## Build and test

Needs JDK 17+ and the Android SDK (compileSdk 37, targetSdk 36, minSdk 26).

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:lintDebug           # lint (must have 0 errors)
./gradlew :app:assembleDebug       # debug APK
```

CI runs the same three steps and also compiles every bundled example with
arduino-cli for the ESP32, so an example that stops compiling fails the build.

## Rules of the road

- **Test first.** Write a failing unit test, then the fix. Keep logic out of
  Activities: storage goes through `SketchStorage` (tests use
  `InMemoryStorage`), screen logic lives in ViewModels.
- **Honest UI.** Never add a button or message that claims something the app
  can't do yet (for example "Upload" before uploads work on a real phone).
- **Clean-room.** arduino-cli (GPL-3.0) and esptool (GPL-2.0) are behaviour
  references only. Don't copy their code or test data into this MIT project.
  Write your own tests from documented behaviour and from outputs you
  generate yourself.
- **Third-party files** keep their licence and get a notice next to them
  (see `app/src/main/assets/textmate/cpp/NOTICE.md`).
- **Examples** in `app/src/main/assets/examples/<NN.Category>/<Name>/<Name>.ino`
  are our own, written for beginners: say what to wire, keep them short, and
  make sure they compile for `esp32:esp32:esp32`.
- **Commits:** small, imperative subject lines ("Fix header MIME type").

## Releases

Maintainers push a tag like `espsketchide-v0.0.2`; the release workflow builds
a signed APK into a draft GitHub Release. Signing uses the repository secrets
`RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD`. Never commit a keystore.
