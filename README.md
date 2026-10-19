# SafeTube

Normal YouTube for kids, minus the videos you've said no to.

SafeTube is an open-source Android app (made for Amazon Fire tablets, works on any Android
tablet or phone) that opens the regular YouTube website and hides every video that matches
rules you write in plain English, for example:

> Nothing involving witches, Halloween, ghosts, zombies or anything designed to scare
> children. Ordinary fairy tales and silly cartoon fun are fine.

Your child still browses, searches and watches YouTube as usual. Videos that break your
rules simply aren't there.

## How it works

- **Unknown means hidden.** Every video thumbnail, title and link starts invisible. It only
  appears once SafeTube has decided it's fine, so nothing flashes up and then disappears.
- **Two layers of checks.** A keyword list (e.g. `halloween, witch, ghost`) is checked
  instantly on the device. Optionally, an AI model reads each video's title, channel and
  thumbnail against your plain-English rules.
- **Direct links and autoplay are checked too.** If a video opens some other way (a shared
  link, "up next", autoplay), the player stays hidden and paused until the video is checked.
  Blocked videos show "This video isn't available".
- **Decisions are remembered.** Each video is checked once per set of rules and cached on
  the device, so it costs almost nothing after the first few days.
- **Strict / Balanced / Relaxed.** Strict (the default) hides anything the AI isn't fairly
  sure is fine.
- **You stay in charge.** A parent PIN protects the settings. The "Recently hidden" list
  shows what was hidden and why, with an Allow button for mistakes.
- **Extras on by default:** Shorts hidden, comments hidden, ads hidden, links to other
  websites disabled.

Only YouTube page data (video id, title, channel, the text next to it and the thumbnail URL)
is ever sent to the AI. SafeTube never sees any other traffic on the device.

## Install on a Fire tablet

1. On the tablet, open **Settings → Security & Privacy** and turn on **Apps from Unknown
   Sources** (for the Silk browser or your file manager).
2. Download `SafeTube-vX.Y.Z.apk` from this repository's **Releases** page and open it.
3. Open SafeTube, choose a parent PIN, and review the rules.
4. Recommended: in **Settings → Parental Controls** (or Amazon Kids), block the Silk browser
   and the YouTube app so SafeTube is the only way onto YouTube.

## Choosing an AI (optional)

Without an AI the keyword list still works, fully offline. To use plain-English rules, hold
the faint 🔒 in the top corner for two seconds, enter your PIN, and pick a provider:

| Provider | What to enter |
| --- | --- |
| Claude (Anthropic) | An API key from console.anthropic.com. Default model: `claude-haiku-5-5`. |
| OpenAI-compatible | Base URL + model + key. Works with OpenRouter (`https://openrouter.ai/api/v1`), OpenAI (`https://api.openai.com/v1`), Groq, Together, or a local Ollama / LM Studio server on your home network (`http://192.168.x.x:11434/v1`). |

Use **Test the AI** to see how your rules treat a Halloween song and a normal song.

Keys stay on the tablet and are sent only to the provider you chose. Never put your own key
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
```

Every push builds an APK in GitHub Actions; pushing a tag like `v0.1.0` publishes it as a
release. To sign releases with your own key, add the repository secrets
`SAFETUBE_KEYSTORE_B64` (base64 of the .jks), `SAFETUBE_KEYSTORE_PASSWORD`,
`SAFETUBE_KEY_ALIAS` and `SAFETUBE_KEY_PASSWORD`. Without them the APK is signed with the
build machine's debug key, which installs fine but can't be upgraded in place by a build
from a different machine.

## Project layout

| Path | What it is |
| --- | --- |
| `core/` | The filtering engine in plain Kotlin: keyword matching, the `VideoClassifier` interface, Anthropic and OpenAI-compatible providers, caching rules and strictness. No Android code, so a browser extension or TV app can reuse the same logic. |
| `app/src/main/assets/filter.js` | The script that runs inside YouTube pages: hides unchecked videos, sends them to the app, applies decisions, guards the player. |
| `app/src/main/java/app/safetube/` | The Android app: WebView, PIN-protected parent settings, SQLite decision cache. |
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
