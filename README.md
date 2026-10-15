# WiFi AutoSwitch

## I Vibe Coded this app just for the ease of use. !

A small Android app I built for myself so my phone always uses the better of my two home Wi-Fi networks, and so I can keep an eye on my home network without logging into router pages.

## Why I built this

At home I have two routers: a C-Data fiber router upstairs and a Dialog ZLT S50 downstairs, connected by an Ethernet cable. Same internet, different Wi-Fi names.

My phone kept clinging to whichever network it joined first. I'd walk downstairs and it would stay on the weak upstairs Wi-Fi until pages crawled, then I'd switch by hand. Same thing going back up, every day. I didn't want to change my router setup, so I built an app that does the switching for me, and then added the network tools I kept wishing I had.

## What it does

**Automatic switching.** The app watches my Wi-Fi signal. When the network I'm on gets weak and the other one is clearly stronger, it moves my phone over, and back again when I return. It only switches when two separate checks agree, confirms the internet works afterwards, and waits a couple of minutes before it's allowed to switch again, so it doesn't jump back and forth.

**Wi-Fi tools:**

- **Who's on my Wi-Fi:** every device on my network with its name, IP and MAC address. New devices are flagged, and tapping one copies its MAC so I can block it in the router.
- **New device alert:** while my phone is charging, it checks every ~15 minutes and notifies me if an unknown device joins.
- **Wi-Fi channels:** shows how crowded each channel is around my house and suggests a better channel for each router.
- **Speed test:** download, upload, internet ping and router ping, with averages per network to compare upstairs and downstairs.
- **Internet outages:** logs when my Wi-Fi is connected but the internet stops working, with a share button for my ISP.
- **No-internet failover:** if one router loses internet, it moves me to the other.

**Small comforts:**

- Waits for phone and WhatsApp calls to end before switching
- "Switch now" button, plus two Quick Settings tiles (Auto Switch on/off, Switch Wi-Fi)
- Auto-tune: learns the signal levels in my house and suggests the best switching threshold
- Tells me if Shizuku stops

## How it works (simply)

Android doesn't let normal apps change your Wi-Fi network or see other devices' MAC addresses. The app uses **Shizuku**, a free app that gives it the same permissions the Settings app has, without root. With them it can join a network that's already saved on the phone and read the list of devices on the network.

It never sees or stores Wi-Fi passwords and never changes router or network settings. Without Shizuku, switching still works with a one-tap notification instead, but the device list needs Shizuku.

## Battery

I built it to be as light as possible:

- Screen off: it basically does nothing
- Away from home: completely idle
- Strong signal: barely checks anything
- Scans only when the signal is actually weak, and strictly limited
- Device list, channel check and speed test only run when I open them
- New-device checks only run while charging

## What it needs

- An Android phone (I use a Pixel 9 Pro XL on Android 17)
- **Location permission:** Android treats Wi-Fi names as location data. The app never uses GPS or tracks where I am.
- **Shizuku**, for automatic switching and the device list. It has to be restarted after a phone reboot.

## Privacy

Everything stays on my phone: settings, device lists, outage log and speed history. No accounts, no ads, no tracking. The only outside connection is the speed test, which uses Cloudflare's public speed-test server when I start a test.

## Limits

- Switching with the screen off is slower; it mostly happens right after I turn the screen on.
- Data usage per device and blocking devices can only be done in the router itself (the app copies the MAC address and opens the router page).
- Power cuts that switch the routers off aren't counted as internet outages, because they look the same as leaving home.
- Devices that are asleep may not show up in a device scan straight away.

## Built with

Kotlin, Jetpack Compose, Material 3, Kotlin Coroutines, DataStore and the Shizuku API. The switching, auto-tune, channel, outage and speed logic is plain Kotlin with 56 unit tests.

---

A personal project made for my own home setup. Feel free to look around or adapt it for yours.
