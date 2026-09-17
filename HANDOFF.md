# Handoff: build + live-test Yoink in a network-enabled session

Yoink is an Android app (Kotlin + Jetpack Compose) that downloads media
(photos/videos/GIFs) from Twitter/X, Instagram, and Pinterest post links, saving
into the gallery via MediaStore. "Magpie Heist" dark theme (charcoal + electric
yellow accent).

**Branch:** `claude/yoink-android-app-aqjca6` (already pushed, latest commit at the
time of writing: `b2a3136`). Continue from there — don't start over.

## State

The full app has been written — Gradle scaffold, theme, clipboard/share-intent
detection, per-platform extractors, WorkManager download pipeline + MediaStore
save, Room-backed history ("The Nest") with a media picker for multi-item posts,
Settings screen, adaptive launcher icon. Every item from the original build order
is done.

## Critical caveat

**None of this has ever been compiled.** The sandbox it was built in had outbound
network blocked to `dl.google.com`/`maven.google.com` (so no AGP/Android SDK
artifacts), and also to `instagram.com`/`pinterest.com`/`twitter.com`/`x.com` (so
the scraping logic in the extractors was written from reasoning and training
knowledge, never tested against a live response). Your first job in a
network-enabled session is to actually build and test this for the first time.

## Next steps, in order

1. **Get a real compile.** Run `./gradlew assembleDebug` (or open in Android
   Studio). A `.claude/hooks/session-start.sh` SessionStart hook is already in
   the repo and should auto-provision the Android SDK (checks `$ANDROID_HOME`,
   then a cached prior install, then downloads cmdline-tools + platform-tools +
   `platforms;android-35` + `build-tools;35.0.0`) — check its output on first
   run in a new session; the download/`sdkmanager` step itself was never
   verified against a real network (it was written and locally sanity-checked
   in the same blocked sandbox as everything else here). Separately, expect
   dependency-version friction — versions in `app/build.gradle.kts` and the
   root `build.gradle.kts` (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01,
   Room 2.6.1, WorkManager 2.10.0, DataStore 1.1.1, Coil 2.7.0,
   navigation-compose 2.8.5, etc.) were best-confidence guesses at versions
   that work together, not verified against what's actually current — bump
   anything Android Studio/Gradle flags as unresolvable or suggests updating.

2. **Test the URL-detection/share-intent/download flow end to end** on a device
   or emulator with a real Twitter/X link first — `TwitterExtractor` is the most
   likely to already work since it hits a documented public syndication
   endpoint, but its `syndicationToken()` function (reverse-engineered token
   math, in `TwitterExtractor.kt`) is the one part of it that isn't fully
   certain — if Twitter extraction 403s, look there first.

3. **Test Instagram and Pinterest against real links** — these are the two the
   user already found broken once (Instagram: "can't find any media"; Pinterest:
   video/GIF pins only downloaded a still image). Commit `b2a3136` pushed fixes
   for both, based on reasoning alone (couldn't verify live):
   - `InstagramExtractor.kt`: now tries the `?__a=1&__d=dis` pseudo-API with an
     `X-IG-App-ID: 936619743392459` header first, then falls back to parsing
     embedded JSON in the post page (trying both an older GraphQL shape and a
     newer `carousel_media`/`image_versions2`/`video_versions` shape), then OG
     tags as a last resort.
   - `PinterestExtractor.kt`: now scans the raw page text for any
     `"url":"...mp4..."` occurrence anchored near the pin's own numeric ID (from
     the URL), instead of relying on a specific `video_list` JSON key that was
     apparently wrong or outdated.

   Test with: an Instagram single-image post, a Reel (video), and a carousel
   post; a Pinterest still-image pin and a Pinterest video pin. If any of these
   still fail, you'll have live network access to actually inspect the real
   response (curl the URL, view page source, log the JSON) and fix precisely
   instead of guessing — that's the fix this whole handoff exists to enable.

4. **Once extraction works, sanity-check the rest:** MediaStore save (does the
   file actually show up in Photos/gallery on API 29+ and, if you can test it,
   API 26-28), the download progress notification, the media picker for
   carousels, the Settings screen folder options, the history list.

5. **Commit and push fixes to the same branch**
   (`claude/yoink-android-app-aqjca6`). Don't open a PR unless the user asks for
   one.

## Architecture

Everything lives under `app/src/main/java/com/zuruikyoku/yoink/` (package
`com.zuruikyoku.yoink`, minSdk 26):

- `data/extractor/` — the `MediaExtractor` interface plus the Twitter/Instagram/
  Pinterest implementations. Each is deliberately self-contained so one breaking
  doesn't affect the others.
- `data/download/` — `DownloadWorker` (WorkManager) + `MediaStoreSaver` +
  notifications. Takes an already-resolved `ExtractedMedia`; extraction happens
  earlier, in the ViewModel.
- `data/db/` — Room (`DownloadEntity`/`DownloadDao`/`YoinkDatabase`).
- `data/settings/` — DataStore-backed settings repository.
- `ui/main/` — `MainViewModel` (extraction → picker-if-carousel → download
  queue), `MainScreen`, the media picker sheet.
- `ui/settings/` — the Settings screen.
- `ui/theme/` — Magpie Heist colors/typography/theme.
