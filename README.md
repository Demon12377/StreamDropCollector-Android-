# Stream Drop Collector (Android)

Stream Drop Collector is a Kotlin + Jetpack Compose Android application ported from `Demon12377/StreamDropCollector` to automatically track, mine, and claim Twitch and Kick campaign drops across multiple isolated accounts.

## Features

- **Dual-Platform Parallel Mining**: Simultaneously monitors and mines active drop campaigns on **Twitch** and **Kick** with isolated per-account credentials and settings overrides.
- **Twitch Helix & GQL Integration**: Supports Twitch GQL campaign/inventory sync, auto-claiming (`DropsPage_ClaimDropRewards`), Device Code OAuth for Twitch Helix, EventSub WebSocket updates, and Twitch Open Reward campaigns.
- **Kick Drops & Viewer Engagement**: Syncs Kick drop campaigns (`web.kick.com/api/v1/drops/...`), auto-claims progress rewards, farms Kick channel XP / viewer levels when idle, and claims daily streak rewards.
- **Smart Streamer & Game Filtering**: Choose between four mining priority modes, prioritize badge drops or emotes/raffles, pin specific campaigns (`Mine instead`), reorder whitelisted games (`Mine first`), block unwanted games (`Ignore game`), configure priority streamers, or import your followed Kick channels.
- **Minimal Bandwidth Mode**: Forces lowest playback quality (`160p30`) inside headless WebViews or switches to experimental `HEAD`-only HLS audio playlist requests.
- **Background Service & Telemetry**: Optional Android foreground service (`dataSync`), live/average/total per-account network bandwidth counters, configurable Dark/Light/System themes, local Android notifications, Discord/Slack/JSON webhooks, GitHub release check, and an in-app diagnostic log viewer/exporter.

## Tech Stack

- **Kotlin** & **Jetpack Compose (Material 3)** with adaptive bottom `NavigationBar` / wide-screen `NavigationRail`
- **Navigation Compose** (type-safe `@Serializable` routes)
- **DataStore Preferences** + `kotlinx.serialization`
- **OkHttp** (GQL, Helix REST & EventSub WebSocket, GitHub update check, webhooks)
- **Android WebView** + **Coil** + custom adaptive launcher icon
