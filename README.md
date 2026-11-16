![EverSignal Simple screen: TIDAL, FLAC 44.1 kHz / 16-bit, EQ off, bit-perfect to the USB DAC](docs/screenshots/1-simple.jpg)

# EverSignal: now-playing stats for the Eversolo DMP-A6

**See every bit of your music, from the file to your DAC.** A free, read-only Android app that runs right on your Eversolo DMP-A6 streamer.

Are you a streaming nerd? Do you check the sample rate before the song title? Ever wondered whether that 24-bit TIDAL track really reaches your DAC untouched, or what your Eversolo is quietly doing to it on the way? **This player is for you.**

EverSignal is a now-playing screen for the Eversolo DMP-A6 that shows the whole signal path at a glance: what the source is, what the player does to it, what goes out and through which port. Then it tells you, in plain words, whether it's **bit-perfect**. One tap away there are five pages with every stat the player reports. Your music keeps playing exactly as before; EverSignal just shows you what's going on.

**It only reads. It never writes.** No settings changed, no files touched, nothing in your library edited. The only things it ever asks the player to do are the buttons you tap: play/pause, next, previous, and the VU screen.

## Why did I build this?

I always wanted to know what my DMP-A6 actually sends to my DAC. Is that TIDAL track really bit-perfect, or is something resampling it on the way? The player knows, it just doesn't show you. I was curious how much I could pull right on the device, and it turned out to be a lot. So I built a free app for it.

> **Made by Michael Albers.** I make honest videos about budget audio equipment, including a lot of Eversolo DMP-A6 how-tos. If you like this app, come find more on my YouTube channel: **[youtube.com/@malbers](https://www.youtube.com/@malbers)**

| The stock player screen | The same track in EverSignal |
|---|---|
| ![Stock Eversolo player](docs/screenshots/0-stock-player.jpg) | ![EverSignal Simple](docs/screenshots/1-simple.jpg) |

## What people are saying

From the Eversolo Facebook groups, launch day:

> "You sir are a clever little cookie"<br>
> **Martin M.**

> "Congrats and Thanks for your APP."<br>
> **Patricio M.**

> "Nice, thanks!"<br>
> **Emilio M.**

> "Very cool!"<br>
> **Lou S.**

> "I'm really enjoying this. Thanks!"<br>
> **Michael K.**

> "by the way, it looks like a great product. Thank you."<br>
> **David S.**

> "Pretty awesome!"<br>
> **Weston T.**

## What you get

### Simple: the couch view

Big, readable from across the room:

- **The signal path** in four steps: **Source** (FLAC, 192 kHz, 24-bit) → **Processing** (EQ, DSP, subwoofer) → **Output** (PCM, DSD or MQA, and its rate) → **Port** (XLR/RCA, USB DAC, HDMI, optical/coax).
- **A verdict badge:** Bit-perfect, Resampled, EQ active, Volume (when digital volume changes the samples), DSD native, DSD → PCM, MQA, Lossy. Tap it for exactly what it checks.
- **Stat tiles:** quality, sample rate and bit depth, the file's exact bitrate, file size, and which input goes to which output.
- **The source, always named:** TIDAL, Apple Music, Amazon Music, AirPlay, or Library for your own files.
- Play/pause, next, previous and up next, plus a **VU** button that switches the Eversolo's own screen to its VU meters.
- **made by @malbers** under the cover opens my YouTube channel, if you have a browser on the Eversolo.

| MQA from the library, sent to the DAC | EQ on: no longer bit-perfect, and it says so |
|---|---|
| ![MQA to DAC](docs/screenshots/1b-simple-mqa.jpg) | ![EQ active](docs/screenshots/1c-simple-eq.jpg) |

### Detail: stats for nerds

Tap **Detail**, then swipe left or right through five pages:

| Page | What's on it |
|---|---|
| 1. Overview | Music, signal path, device and today's session: hours listened, tracks, how much was hi-res, CD, DSD or lossy, the highest rate and the bitrate range |
| 2. Track & file | Where it plays from, credits, file details (exact bitrate, uncompressed rate, how much FLAC saved), library data, track flags |
| 3. Queue & playback | Position in the queue, what's up next with formats, the player's raw playback state and flags |
| 4. Device | Model, firmware, memory, storage, network, display settings and what the player says it can do |
| 5. Inputs, outputs & volume | Every input and output, the active output's own settings (filter, DPLL, DSD mode, MQA mode), what the DAC can take, the volume settings |

Anything the source doesn't report shows as N/A instead of being hidden, so you can see what each source does and doesn't give you.

Each page scrolls. Top and scrolled views:

| Page | Top | Scrolled |
|---|---|---|
| **1 Overview** | ![Detail page 1](docs/screenshots/2-detail-1.jpg) | ![Detail page 1, scrolled](docs/screenshots/2-detail-1b.jpg) |
| **2 Track & file** | ![Detail page 2](docs/screenshots/2-detail-2.jpg) | |
| **3 Queue & playback** | ![Detail page 3](docs/screenshots/2-detail-3.jpg) | ![Detail page 3, scrolled](docs/screenshots/2-detail-3b.jpg) |
| **4 Device** | ![Detail page 4](docs/screenshots/2-detail-4.jpg) | ![Detail page 4, scrolled](docs/screenshots/2-detail-4b.jpg) |
| **5 Inputs, outputs & volume** | ![Detail page 5](docs/screenshots/2-detail-5.jpg) | ![Detail page 5, scrolled](docs/screenshots/2-detail-5b.jpg) |

## How to use it

1. **Play some music** the way you always do: TIDAL, your library, the Eversolo phone app, AirPlay, anything.
2. **Press Home** on the Eversolo.
3. **Open EverSignal** from the **Apps** tab.
4. **Watch the stats.** Play/pause, next and previous work right there, and the Eversolo's own controls keep working as normal.

**Stay in front** (Detail, page 1, bottom right): starting a track from your phone or connecting AirPlay makes the Eversolo jump to its own player screen. With Stay in front on, EverSignal comes back by itself a couple of seconds later. The first time, Android asks you to allow **Display over other apps**; turn it on, go back, and tap Stay in front again. The Back button always turns it off, so the app can never trap the screen.

## What "Bit-perfect" means here

The badge says **Bit-perfect** only when all of these are true:

- PCM goes out at the source's own sample rate (no resampling)
- EQ and DSP are off
- The volume is at 0 dB or locked, because digital volume changes the samples

If one isn't, the badge says which one (Resampled, EQ active, Volume −12 dB). What it can't check: the output bit depth (the DMP-A6 never reports it), and anything after the Eversolo's output, like the DAC or amp it feeds.

## What it never does

- **It never writes.** It reads what the player reports and shows it. It never changes a setting, never writes, moves or deletes a file, and never edits your library or tags. The only things it can tell the player to do are play/pause, next, previous, and switch its screen to the VU meters, and only when you tap them.
- It keeps a few small things in its own private app storage: today's listening totals for the Session panel, whether Stay in front is on, and the last update check. Nothing on your drives, unless you tap Update (below).
- It only talks to the Eversolo it runs on, through the player's local interface. Two exceptions: album covers (when a streaming service gives a cover link, the picture loads from that service's own server), and once a day it asks GitHub whether a newer EverSignal is out. That check sends nothing about you or your music.
- If a newer version is out, an **Update** pill appears under the cover. Only when you tap it does it save the new APK to the Eversolo's **Download** folder. It never installs anything by itself.
- No accounts, no analytics, no Google services. Nothing about you or your music is sent anywhere.
- It never shows the signed part of a streaming link. The **Device** page does show the player's network address, MAC addresses and device ID, because they're useful to you; blur them before you share a screenshot.

## Download

[**EverSignal-1.0.2.apk**](https://github.com/malbers/eversignal/releases/download/v1.0.2/EverSignal-1.0.2.apk) (from the [v1.0.2 release](https://github.com/malbers/eversignal/releases/tag/v1.0.2))

Download it to your laptop or desktop, not to the Eversolo. The next section shows how to get it onto the Eversolo.

## How to Install

**What you're doing:** an `.apk` file is an Android app. The Eversolo runs Android underneath, so it can install apps that aren't in its app store, usually called "sideloading". It takes a couple of minutes and you only do it once.

**Before you start:** your computer and the Eversolo must be on the same home network.

### Option A: the Eversolo's upload page (easiest)

1. **Find the Eversolo's address.** On the Eversolo, go to **Settings > Network Settings > Transfer**. Under **Method 1** it shows a web address that starts with `http://` and ends in `:9587`.

   ![Eversolo Transfer screen, Method 1](docs/install/1-transfer-screen.jpg)

2. **Open that address on your computer** in any browser, including the `:9587` at the end. The Eversolo's upload page opens. Click **Upload Apk** at the right.

   ![Eversolo web upload page with the Upload Apk button](docs/install/2-upload-page.jpg)

3. **Send the app.** Click **Select APK File** and choose `EverSignal-1.0.2.apk` from your Downloads folder. It uploads and installs by itself.

   ![Select APK File](docs/install/3-select-apk.jpg)

4. **Find it on the Eversolo** in the **Apps** tab on the home screen.

   ![EverSignal in the Eversolo Apps tab](docs/install/4-apps-tab.jpg)

### Option B: copy it over the network, then tap to install

1. On the same **Transfer** screen, **Method 2** shows a network share address for Windows (starts with `\\`) and Mac (starts with `smb://`).
2. Open it on your computer: in Windows File Explorer type the `\\` address in the address bar; on a Mac use **Go > Connect to Server** with the `smb://` address (choose **Guest** if asked).
3. Copy the APK into any folder on the Eversolo, for example `Download` on the SSD.
4. On the Eversolo, open the **Files** app, go to that folder and tap `EverSignal-1.0.2.apk`. Tap **Install**.

   ![Install prompt in the Eversolo Files app](docs/install/smb-1-install.jpg)

5. **Tap Open** to start it now, or **Done** and find it later in the **Apps** tab.

   ![EverSignal installed](docs/install/smb-2-installed.jpg)

### Is this file really from me?

Every official release is signed with the same key. If you want to check a copy you got somewhere else, its SHA-256 certificate fingerprint should be:

```
351e63f51b6bed49f8e5282c2cf1427ce8bb5e7fee3558ab4899c5b6ca146ca2
```

An APK with a different signer did not come from here.

## Updating

From 1.0.1 on, EverSignal checks for a new version once a day. When one is out, tap **Update … · Download** under the cover. It saves the APK to **Download**. Then open **Files › Download**, tap the APK, and choose **Update**. Your settings stay. If you're on 1.0.0, install 1.0.1 by hand once, the same way you installed 1.0.0.

## What's new

**1.0.2** (15 November 2026)
- Bigger text, readable standing at the rack: Detail is drawn 1.8x larger in two columns, and every page scrolls (page 1 too).
- Simple: smaller cover, larger signal path, tiles and badge line; second lines in green, labels in tan.
- Larger Simple / Detail, VU and volume controls at the top right, easier to hit.
- Up next shows the format on the same line; long values wrap under their label.

**1.0.1** (15 November 2026)
- Update check: once a day it asks GitHub for the latest release, and shows an Update pill when a newer one is out. Tap it to download, no browser needed.

**1.0.0** (15 November 2026)
- First public release: Simple and Detail screens, the bit-perfect verdict, five stats pages, today's listening session, Stay in front.

## Tested on

Eversolo DMP-A6 (Gen 1), firmware 1.6.15, Android 11, with the library (FLAC, WAV, DSD, MQA, MP3), TIDAL, Apple Music, Amazon Music, AirPlay and TIDAL Connect, on XLR/RCA and USB DAC outputs. Other Eversolo models may work but are untested.

EverSignal is an independent app. It is not made by or affiliated with Eversolo, and it reads the player's undocumented local interface, which a firmware update could change.

## Feedback

Found a bug, have an idea, or tried it on another Eversolo model? **[Tell me here](https://docs.google.com/forms/d/e/1FAIpQLSdaQcFaXOcaRyZr520Lxir_NmPKtZG6aojfJ15XBwSbSB9ffQ/viewform)**. It's a short form, no account needed, and I read every one. If you're on GitHub, you can also [open an issue](https://github.com/malbers/eversignal/issues). A screenshot of the Detail pages helps a lot (blur the Device page first).

## Building from source

Java, plain Android SDK, no third-party libraries. JDK 17, then:

```
gradlew assembleRelease testDebugUnitTest
```

Release builds are signed with a key kept outside the repo (`scripts/new-signing-key.ps1`); without it they fall back to the debug key.

## Credits

Fonts: [Hanken Grotesk](https://fonts.google.com/specimen/Hanken+Grotesk) and [IBM Plex Mono](https://fonts.google.com/specimen/IBM+Plex+Mono), both under the SIL Open Font License 1.1 (licence texts in `app/src/main/assets/fonts/`). See [THIRD_PARTY.md](THIRD_PARTY.md).

## Licence

Copyright 2026 Michael Albers ([@malbers](https://www.youtube.com/@malbers)). Licensed under the [Apache License 2.0](LICENSE).

You're welcome to use it, modify it, build on it and send improvements. If you redistribute it or something built from it, keep the [NOTICE](NOTICE) file with it so the credit travels along. The licence doesn't cover the name: please don't publish a modified version as "EverSignal" or as coming from me.

Contributions are welcome. Anything you submit to this repository is accepted under the same Apache 2.0 licence.

## Also from me

Two more free apps for the DMP-A6:

- **[AddMyCoverArt](https://github.com/malbers/addmycoverart)** fills in missing album art on your ripped CDs, right on the Eversolo, and checks your rips for missing or cut-short tracks.
- **[FixMyRips](https://github.com/malbers/fixmyrips)** fixes the tags the Eversolo CD Ripper leaves broken, and tells you from your phone whether a CD is already ripped.

And the videos that go with all three: **[youtube.com/@malbers](https://www.youtube.com/@malbers)**
