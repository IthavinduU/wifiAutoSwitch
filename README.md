# WiFi AutoSwitch
## Disclaimer : This is a vibe coded app using Claude. I only did some tweaks here and there. 

A small Android app I built for myself so my phone always uses the stronger of my two home Wi-Fi networks, without me having to switch manually.

## Why I built this

At home I have two routers: a C-Data fiber router upstairs and a Dialog ZLT S50 downstairs, connected by an Ethernet cable. Same internet, different Wi-Fi names.

The problem was that my phone clings to whichever network it joined first. I'd walk downstairs and it would stay on the weak upstairs Wi-Fi until pages started crawling, then I'd go into settings and switch by hand. Same thing going back up. Every single day.

I didn't want to touch my router setup, so I built an app that does the switching for me.

## What it does

The app quietly watches my Wi-Fi signal. When the network I'm on gets weak and the other one is clearly stronger, it moves my phone over. When I walk back, it switches back.

It's careful about it. It only switches when the current signal is actually weak, the other network is much stronger, and two separate checks agree. After switching, it confirms the internet really works, and it waits a couple of minutes before it's allowed to switch again. So it doesn't jump back and forth.

## Features

- **Automatic switching** between my upstairs and downstairs Wi-Fi
- **Doesn't interrupt calls:** if I'm on a phone or WhatsApp call, it waits until the call ends
- **Switch now:** a button in the app and a Quick Settings tile when I want to switch immediately
- **Auto Switch tile:** turn it on/off from the notification panel and see which floor I'm on
- **Auto-tune:** it learns the signal levels in my house and suggests the best setting for when to switch
- **Safety checks:** if a switch fails, it reconnects me to the network I was on and backs off before trying again
- **Shizuku alert:** tells me if the helper app it needs has stopped
- **Simple screen:** current connection, my two networks, one big on/off switch, and a status card

## How it works (simply)

Android doesn't let normal apps change your Wi-Fi network on their own. So the app uses **Shizuku**, a free app that gives it the same permission the Settings app uses to join a saved network. No root needed.

The app only joins networks already saved on my phone. It never sees or stores Wi-Fi passwords and never changes any network settings.

Without Shizuku it still works, but instead of switching by itself it sends me a notification to switch with one tap.

## Battery

I built it to be as light as possible:

- With the screen off, it basically does nothing
- When I'm away from home, it goes completely idle
- When my signal is strong, it barely checks anything
- It only scans for networks when the signal is actually weak, and even then it's strictly limited

In practice it sits near the bottom of my battery usage list.

## What it needs

- An Android phone (I use a Pixel 9 Pro XL on Android 17)
- **Location permission:** Android treats Wi-Fi names as location data. The app never uses GPS or tracks where I am.
- **Shizuku**, for fully automatic switching (has to be restarted after a phone reboot)

## Privacy

Everything stays on my phone. No accounts, no internet servers, no ads, no tracking.

## Built with

Kotlin, Jetpack Compose, Material 3, Kotlin Coroutines, DataStore, and the Shizuku API.

---

This is a personal project made for my own home setup. Feel free to look around or adapt it for yours.