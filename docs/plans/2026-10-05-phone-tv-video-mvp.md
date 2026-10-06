# Phone to TV Video MVP Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a native Android phone media server and Android TV client that discovers phones on the LAN and plays their videos with seek support.

**Architecture:** A two-application Kotlin project shares a small JSON protocol. The phone scans MediaStore and serves metadata, thumbnails and byte-range streams through a foreground service; the TV discovers the service with Android NSD, browses the HTTP API and plays streams with Media3.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose, Android NSD, Media3 ExoPlayer, AndroidX Lifecycle.

---

### Task 1: Create the Android project foundation

Create Gradle settings, root build configuration, wrapper configuration, and independent `phone-app` and `tv-app` Android application modules. Set minimum SDK 26 and a shared compile SDK.

### Task 2: Implement phone media catalog and HTTP server

Query MediaStore for video metadata, start/stop an Android foreground service, advertise `_phonevideo._tcp`, expose paged JSON APIs, thumbnails, and an HTTP Range stream endpoint that reads from ContentResolver without buffering entire files.

### Task 3: Implement the phone control screen

Build a Compose screen for scan status, video count, device name, sharing state, and start/stop controls. Request media permissions at runtime.

### Task 4: Implement TV discovery, browsing, and playback

Discover NSD services, list online phones, fetch paged video results and thumbnails, and play the selected URL with Media3 controls suitable for D-pad input.

### Task 5: Verify project configuration

Run Gradle compilation where a supported JDK and Android SDK are available; inspect manifest permissions and app launch metadata.
