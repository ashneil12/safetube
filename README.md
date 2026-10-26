# SafeTube

Normal YouTube for kids, minus the videos you've said no to.

SafeTube is an open-source app for Android (made for Amazon Fire tablets, works on any Android
tablet or phone) and for iPhone and iPad that opens the regular YouTube website and hides every video that matches
rules you write in plain English, for example:

> Nothing involving witches, Halloween, ghosts, zombies or anything designed to scare
> children. Ordinary fairy tales and silly cartoon fun are fine.

Your child still browses, searches and watches YouTube as usual. Videos that break your
rules simply aren't there.

## How it works

- **Unknown means hidden.** Every video thumbnail, title and link starts invisible. It only
  appears once SafeTube has decided it's fine, so nothing flashes up and then disappears.
- **Check what is coming onto the screen.** Loaded video cards far offscreen stay hidden without
  requesting AI checks. SafeTube checks them as they approach the screen; the current video
  and direct links are checked immediately. Cached decisions are still reused.
- **Two layers of checks.** A keyword list (e.g. `halloween, witch, ghost`) is checked
  instantly on the device. Optionally, an AI model reads each video's title, channel and
  thumbnail against your plain-English rules.
- **Direct links and autoplay are checked too.** If a video opens some other way (a shared
  link, "up next", autoplay), the player stays hidden and paused until the video is checked.
  Blocked videos show YouTube's usual "Video unavailable" screen.
- **Decisions are remembered.** Each video is checked once per set of rules and cached on
  the device, so it costs almost nothing after the first few days.
- **Strict / Balanced / Relaxed.** Choose during setup and change later. Strict hides at an
  estimated rule-breaking score of 20%, Balanced at 50%, and Relaxed at 80%. These are model
  estimates, not a guarantee about the actual video.
- **Only show a topic.** Describe the content you want, such as “Planet Coaster park-building
  tutorials and educational science.” Videos outside that description are hidden too. This
  mode requires a working AI checker; keywords alone cannot identify every other topic.
- **Fine-tune the AI.** Add context such as “Ordinary Planet Coaster gameplay is fine; do not
  treat the game itself as frightening.” This guides AI judgement. Exact video overrides,
  blocked channels and keyword matches retain priority. If a keyword is causing the block,
  remove or narrow that keyword in parent settings.
- **Backup checker and outage protection.** Configure a second checker for failed or incomplete
  primary responses. With outage protection enabled (the default), a final AI failure covers
  and pauses all YouTube content, including previously allowed videos. Retry checks again;
  cached results alone cannot reopen the page. Unknown videos stay hidden even if whole-app
  outage protection is switched off. Deliberate keyword-only mode remains available when
  neither AI filtering nor an only-show rule is selected.
- **Hold to hide.** Hold any video (or the title of the one playing) and choose "Hide this
  video" or "Hide everything from" its channel, in a menu that looks like YouTube's own. By default children can do this themselves,
  since hiding has no downside; only a parent can bring it back. Turn that off and hiding asks
  for the PIN.
- **Hide by link too.** In parent settings, paste a video link, a channel link, an @handle or
  a channel name.
- **AI-written keyword lists.** Describe what you don't want and a chat model (Gemini's free
  tier works) suggests every keyword it can think of. You review the list before saving.
- **You stay in charge.** A parent PIN protects the settings. The "Recently hidden" list
  shows what was hidden and why, with an Allow button for mistakes. Tap a video in the parent
  lists to inspect its details, the stored decision and the current strictness threshold, or
  open the actual video from the parent area. Available page text is shown honestly: a full
  YouTube description may be unavailable. The same hide presets offered in setup are available
  later in parent settings, alongside your custom rules.
- **Screen time and bedtime.** An optional daily limit and bedtime. When time is up, YouTube
  is covered by a friendly "that's all for today" screen; the PIN can add 15 more minutes.
- **Watch history.** A list for the parent of what was watched, with one tap to hide a video
  or its channel. Kept on the device only, and can be switched off.
- **Extras, each with a switch:** ads blocked (video ads skipped, ad servers and trackers never
  load), no autoplay, Shorts hidden, comments hidden, and YouTube's Restricted Mode (set only in
  SafeTube's own browser, never on the Google account). Links to other websites are always off.
  All of these are offered during setup and live together under **Time & extras**.

Only YouTube page data (video id, title, channel, the text next to it and the thumbnail URL)
is ever sent to the AI. SafeTube never sees any other traffic on the device.

## Install on a Fire tablet

1. On the tablet, open **Settings → Security & Privacy** and turn on **Apps from Unknown
   Sources** (for the Silk browser or your file manager).
2. Download `SafeTube.apk` from this repository's **Releases** page (the "latest" release is
   rebuilt on every change) and open it.
3. Open SafeTube. Choose a parent PIN, tap what to hide (scary stuff, pranks, violence,
   unboxing...), choose hide-matching or only-show content rules, and select Strict, Balanced
   or Relaxed. Setup also offers AI context, a primary checker, a backup checker and outage
   protection, as well as time limits and extras. Only-show rules require an AI checker.
4. To get back to the parent settings later, **hold the YouTube logo in the top-left corner
   for three seconds**, or long-press the SafeTube app icon and choose **Parent settings**.
   Both ask for your PIN.
5. Recommended: in **Settings → Parental Controls** (or Amazon Kids), block the Silk browser
   and the YouTube app so SafeTube is the only way onto YouTube.

## iPhone and iPad

SafeTube isn't on the App Store; you install it on your own iPhone or iPad with your own
Apple ID. Two ways, both free:

- **From Windows or a Mac, with the ready-made app:** download `SafeTube.ipa` from the
  **Releases** page ("latest" is rebuilt on every change) and install it with
  [Sideloadly](https://sideloadly.io) or [AltStore](https://altstore.io) using a free Apple ID.
  Then, on the phone, turn on **Settings → Privacy & Security → Developer Mode** and trust
  yourself under **Settings → General → VPN & Device Management**.
- **Build it on a Mac** with Xcode and a free Apple ID (`brew install xcodegen`, then
  `cd ios && xcodegen generate`). If you use Claude Code on your Mac, open this repository and
  say "get this running on my phone": [ios/CLAUDE.md](ios/CLAUDE.md) walks it through every step.

**With a free Apple ID, Apple makes the app stop opening after 7 days** until you re-sign it
(reinstall from Sideloadly or Xcode, or tap Refresh in AltStore; your settings are kept). A paid
Apple Developer account ($99 a year) makes an install last a year.

Step-by-step instructions, and how to make SafeTube the only YouTube on the phone with Screen
Time, are in [ios/README.md](ios/README.md). The iPhone app works just like the Android one:
same setup, PIN, rules, AI options and hold-to-hide menu.

## Choosing an AI (optional)

Without an AI, the keyword list and your blocked videos and channels still work, fully
offline. Hold the YouTube logo for three seconds, enter your PIN, open
**Smart checker**. For the recommended setup, choose **Clef Flash via OpenRouter**, paste your
OpenRouter API key, and tap **Test it**. Your OpenRouter account needs credit. You do not need
a Cloudflare account ID, base URL, custom model or backup checker.

**Advanced: model** lets you choose another OpenRouter Decisions model ID, such as
`typesafe/jev-1.14`. Reset restores `cloudflare/clef-flash`. Chat models belong under the
OpenAI-compatible checker instead. **Other checkers** keeps the alternative services
available. Existing saved providers are preserved when updating the app.

An **Optional backup checker** is available under Safety. The default-on pause protection is
visible even when no backup is configured. The checker and the separate keyword helper
serve different purposes:

**Video checker** decides each video against your rules. Decision models return structured
probabilities rather than chat replies.

| Provider | What to enter | Cost |
| --- | --- | --- |
| **Clef Flash via OpenRouter (recommended)** | An OpenRouter API key. Default model `cloudflare/clef-flash`; change it under Advanced: model. | Requires OpenRouter credit. [Current pricing](https://openrouter.ai/cloudflare/clef-flash). |
| **Direct Cloudflare Clef** | Cloudflare account ID and a Workers AI API token. Default model `clef`; `clef-flash` is also available. | Limited daily free allowance, then a paid plan is needed. The allowance is compute usage, not a number of video checks. [Current pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/). |
| **Jev** by TypeSafe, via OpenRouter | Choose the OpenRouter Decisions checker and enter `typesafe/jev-1.14` under Advanced: model. Existing Jev setups are kept. | Requires OpenRouter credit; model pricing applies. |
| Claude (Anthropic) | An API key from console.anthropic.com. Default `claude-haiku-5-5`. Can also look at thumbnails. | Paid per use. |
| OpenAI-compatible | Base URL + model + key: OpenAI, Gemini, Groq, OpenRouter chat models, or Ollama on your home network. Can also look at thumbnails. | Depends on provider. |

The recommended decision checker uses title, channel and available page text; it does not
send thumbnails in this release. Clef-family requests are split into conservative small
states because the Cloudflare serving path can truncate long text. Rules are never silently
cut to fit: an oversized single check stays hidden and reports an actionable error. These
checks assess metadata, not the entire video.

**Keyword helper** turns your rules into a long keyword list. It only ever sees your rules.
Tap "Use Gemini (free tier)" and paste a key from aistudio.google.com, or use Claude or any
OpenAI-compatible model.

Use **Test it** to see how your rules treat a Halloween song and a normal song.

Keys stay on the tablet and are sent only to the providers you chose. Never put your own key
into an APK you share with other people.

## Building it yourself

Requirements: JDK 17 and the Android SDK (Android Studio installs both).

```sh
./gradlew :app:assembleRelease     # APK at app/build/outputs/apk/release/
./gradlew :app:installDebug        # install on a connected device (adb)
```

Tests:

```sh
SKIP_ANDROID=1 ./gradlew :core:test          # filtering engine, no Android SDK needed
cd tools/filter-test && npm install && npx playwright install chromium && node run.js
                                             # page script against a mock YouTube page
node ../parent-test/run.js                   # PIN, setup and parent settings screens
```

Every push to `main` builds an APK and an unsigned iPhone `SafeTube.ipa` in GitHub Actions and
publishes both as the "latest" release; pushing a tag like `v0.1.0` publishes a numbered release as well. To sign every build with the same key, so updates install over the old app and keep its
settings, add two repository secrets: `SAFETUBE_KEYSTORE_B64` (base64 of the .jks) and
`SAFETUBE_KEYSTORE_PASSWORD`. The key alias defaults to `safetube` and the key password to the
store password; set `SAFETUBE_KEY_ALIAS` and `SAFETUBE_KEY_PASSWORD` to override them. Make a
key with:

```sh
keytool -genkeypair -keystore safetube-release.jks -alias safetube -keyalg RSA -keysize 3072 -validity 36500
base64 -w0 safetube-release.jks   # paste the output into SAFETUBE_KEYSTORE_B64
```

Keep the .jks and its password somewhere safe: lose them and the next update has to be
installed fresh. Without the secrets the APK is signed with the build machine's throwaway
debug key, which installs but can't be updated in place.

## Project layout

| Path | What it is |
| --- | --- |
| `core/` | The filtering engine in plain Kotlin: channel and keyword blocking, the `VideoClassifier` interface with Clef, Jev, Anthropic and OpenAI-compatible providers, keyword suggestions, caching rules and strictness. No Android code, so a browser extension or TV app can reuse the same logic. |
| `app/src/main/assets/filter.js` | The script that runs inside YouTube pages: hides unchecked videos, sends them to the app, applies decisions, guards the player. |
| `app/src/main/assets/parent/` | The PIN pad, first-run setup and parent settings screens (HTML/CSS/JS, shown in the app's own WebView). |
| `app/src/main/java/app/safetube/` | The Android app: the YouTube WebView, the bridge to the parent screens, SQLite decision cache. |
| `ios/` | The iPhone and iPad app (Swift, WKWebView) and `ios/SafeTubeCore`, the engine ported to Swift. It uses `filter.js` and `parent/` from `app/src/main/assets` as they are. See [ios/README.md](ios/README.md). |
| `tools/parent-test/` | Headless-browser tests for the parent screens. |
| `tools/filter-test/` | Headless-browser tests for `filter.js` against a mock YouTube page. |

## When YouTube changes its website

YouTube renames its page elements from time to time. If videos stop appearing (or the
filter stops catching some), the list of card selectors at the top of `filter.js` is the
place to look. Even if those selectors miss, any link to an unchecked video stays hidden,
so a markup change makes SafeTube show *less*, never more.

## Limits worth knowing

- Pre-roll ads inside the player can't be filtered (ad thumbnails and promoted cards are hidden).
- The AI judges from the title, channel and thumbnail, not from watching the whole video. A
  long compilation could still contain a scene your rules would block. Strict mode and the
  keyword list help; nothing is perfect.
- SafeTube is an independent parental-control tool. It is not made or endorsed by YouTube or
  Google, and it's intended for sideloading on your own family's devices.

## Roadmap

- Chrome / Safari extension for Mac and PC using the same engine.
- Samsung TV (Tizen) version.
- Optional small hosted AI allowance so parents don't need their own key.

## License

MIT
