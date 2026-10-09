# RGB+ for AYANEO

**Stick-light control for the AYANEO Pocket Air Mini.** 52 lighting effects, 48 ready-made themes, a colour wheel you can drive with the left stick, and a Steam Deck-style interface built for the controller.

> **Unofficial.** RGB+ is a fan-made app. It is not made by, endorsed by or affiliated with AYANEO.

![Lights page](docs/screenshots/01-lights.png)

## Download

**[⬇ Download RGBPlus.apk (latest)](https://github.com/intelarc/rgb-plus-ayaneo/releases/latest/download/RGBPlus.apk)** · [All releases](https://github.com/intelarc/rgb-plus-ayaneo/releases)

## Install

On the Pocket Air Mini:

1. Open the download link above in the device's browser and download **RGBPlus.apk**.
2. Open the downloaded file. If Android asks, allow your browser (or file manager) to **install unknown apps**, then go back and tap **Install**.
3. Open **RGB+ for AYANEO**.
4. In **AYASpace**, turn its own RGB lighting off so the two apps don't fight over the LEDs.
5. Optional but recommended: **Settings → Calibrate**. It lights each LED in turn and asks where it is, so spins and per-LED colours land exactly right on your unit.

From a PC instead, with USB debugging on:

```
adb install RGBPlus.apk
```

To update, install the new APK over the old one. Your settings are kept.

## Features

- **52 effects** in six groups:
  - **Basic:** static, per-LED, breathing, pulse, flash, strobe, colour cycle.
  - **Rainbow:** spectrum, rainbow spin, wave and flash.
  - **Motion:** comet, marquee, scanner, ping-pong, twist, radar, colour wave.
  - **Nature:** fire, candle, lava, ocean, aurora, sunset, forest, starry night, rain, lightning.
  - **Party:** police, disco, confetti, Christmas, matrix, synthwave.
  - **Smart:** battery level, temperature, music, reactive.
- **48 themes** grouped as Gaming, Chill, Nature, Party, Seasonal and Smart. You can also save your own looks.
- **Easy colours:** 16 one-tap swatches plus a colour wheel with brightness and recent colours. Push the left stick to pick a colour without touching the screen.
- **Per-effect controls:** speed, brightness, amount, reverse, up to six palette colours, and each of the four LEDs on or off with its own colour.
- **Both sticks together.** Optionally mirror the right stick, or join both sticks into one ring so spins and comets travel from one stick to the other.
- **Live preview:** a photo of the device shows the current effect on its rings, always in view.
- **Made for the controller:** L1 / R1 switch tabs, A selects, B goes back, and every control can be reached with the D-pad.
- **Runs in the background** and can restore the lights after a restart.

## Smart modes

| Mode | What it needs |
|---|---|
| **Music** | Sound access (microphone permission) to measure how loud the sound playing on the device is. Nothing is recorded or stored. |
| **Reactive** | Turn on *RGB+ – reactive lights* in Android's Accessibility settings. It only notices button presses; it never blocks or changes them. The D-pad isn't detected on this unit. |
| **Battery** | Nothing. The 8 LEDs fill up like a gauge, red when low and green when full. The top LED breathes while charging. |
| **Temperature** | Nothing. Blue when cool (about 30 °C), red when hot (about 50 °C), and it pulses faster as the battery heats up. |

## Efficient by design

- **Measured:** about 1–1.5 % CPU and about 61 MB of memory with an animated effect running in the background. Static colours wake up only once every 15 seconds.
- **Animation speed:** Settings → Animation sets it to 12, 24 or 30 updates per second.
- **No heavy libraries:** no AndroidX or other libraries, only the Android SDK and Kotlin.

## Screenshots

| | |
|---|---|
| ![Lights](docs/screenshots/01-lights.png) | ![Modes](docs/screenshots/02-modes.png) |
| ![Motion effects](docs/screenshots/03-modes-motion.png) | ![Party effects](docs/screenshots/04-modes-party.png) |
| ![Adjust](docs/screenshots/05-modes-adjust.png) | ![Colours and palette](docs/screenshots/06-modes-colours.png) |
| ![Individual LEDs](docs/screenshots/07-modes-leds.png) | ![Themes](docs/screenshots/08-themes.png) |
| ![Seasonal themes](docs/screenshots/09-themes-seasonal.png) | ![Settings](docs/screenshots/10-settings.png) |
| ![Calibration and about](docs/screenshots/11-settings-more.png) | ![Colour picker](docs/screenshots/12-colour-picker.png) |
| ![LED calibration](docs/screenshots/13-calibrate.png) | |

## Compatibility

- **Tested on:** AYANEO Pocket Air Mini, Android 11.
- **How it controls the LEDs:** through the firmware's own LED service (`custom_function`), the same one AYASpace uses. No root and no ADB tweaks are needed.
- **Brightness:** 100 % in RGB+ matches the brightest setting AYASpace uses.
- **Other devices:** other AYANEO Android handhelds that have the same service may work, but they're untested. If you try one, please open an issue and say how it went.

## Build from source

You need JDK 17 or newer and the Android SDK (platform 37).

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. A signed release build needs a `keystore.properties` file and a keystore, which aren't included in this repo.

## Disclaimer

AYANEO, Pocket Air and their logos are trademarks of AYANEO. They're used here only to identify the hardware this app is for. The product photo is AYANEO's. RGB+ is provided as is, with no warranty.

## License

The code is under the [MIT](LICENSE) license.
