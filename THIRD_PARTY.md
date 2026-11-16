# Third-party material

| Component | Licence | Where |
|---|---|---|
| Hanken Grotesk (variable font) | SIL Open Font License 1.1 | `app/src/main/assets/fonts/HankenGrotesk.ttf`, licence text `OFL-HankenGrotesk.txt` |
| IBM Plex Mono (Regular, Medium) | SIL Open Font License 1.1 | `app/src/main/assets/fonts/IBMPlexMono-*.ttf`, licence text `OFL-IBMPlexMono.txt` |
| ZXing core 3.5.3 (com.google.zxing:core) | Apache License 2.0 | Gradle dependency, used only to draw the Feedback page's QR code. https://github.com/zxing/zxing |

The fonts were downloaded from the Google Fonts repository (github.com/google/fonts) and are bundled unmodified.

Test fixtures under `app/src/test/resources/fixtures/` are player status replies captured from a DMP-A6, with MAC addresses redacted.

No other third-party code or libraries are included. Unit tests use JUnit 4 and org.json at build time only.
