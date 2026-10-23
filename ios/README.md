# SafeTube for iPhone and iPad

The same SafeTube as the Android app: normal YouTube (the m.youtube.com website), with every
video your rules say no to taken out. Same setup, same parent PIN, same "hold to hide" menu,
same AI options.

SafeTube isn't on the App Store, so you install it yourself. Apple lets anyone with a free
Apple ID put their own apps on their own iPhone. There are two ways to do that:

- **A. Use the ready-made app file** (`SafeTube.ipa`) with Sideloadly or AltStore. Works from
  a Windows PC or a Mac. Easiest.
- **B. Build it yourself on a Mac** with Xcode. Also free.

**The one catch with a free Apple ID:** Apple makes apps installed this way stop opening after
**7 days**. Re-signing them (a couple of clicks, or automatic, see below) gives you another 7
days; nothing is lost when you do. If that gets tiresome, a paid Apple Developer account
($99 a year) makes each install last a year.

## A. Install the ready-made app (Windows or Mac)

1. Download **`SafeTube.ipa`** from this repository's **Releases** page (the "latest" release
   is rebuilt on every change).
2. Install one of these on your computer:
   - **[Sideloadly](https://sideloadly.io)** (Windows or Mac). On Windows, first install
     **iTunes** and **iCloud** from Apple's website (the versions from apple.com, not the
     Microsoft Store), so the computer can talk to the iPhone.
   - **[AltStore](https://altstore.io)** (Windows or Mac). Install AltServer on the computer,
     then use it to put AltStore on the iPhone, as their guide shows.
3. Plug the iPhone in with a cable, unlock it and tap **Trust** if it asks.
4. Put SafeTube on it:
   - **Sideloadly:** drag `SafeTube.ipa` into the window, type your Apple ID email and press
     **Start**. Enter your Apple ID password when asked. (Sideloadly sends it to Apple only,
     to sign the app. If that worries you, make a spare free Apple ID just for this.)
   - **AltStore:** on the iPhone, open AltStore → **My Apps** → **+**, and pick
     `SafeTube.ipa` (save it to the phone's Files app first, e.g. AirDrop it).
5. On the iPhone, allow apps you signed yourself to open:
   - **Turn on Developer Mode** (iOS 16 and later): **Settings → Privacy & Security →
     Developer Mode** → on. The phone restarts; after it does, tap **Turn On** and enter your
     passcode. (If you don't see Developer Mode, it appears after the first install.)
   - **Trust yourself as a developer**: **Settings → General → VPN & Device Management** →
     tap your Apple ID under "Developer App" → **Trust**.
6. Open **SafeTube**. Setup takes about a minute: choose a parent PIN and pick what to hide.

**Every 7 days** (free Apple ID): open Sideloadly and install the IPA again, or, with AltStore,
open AltStore on the phone and tap **Refresh All** while AltServer is running on your
computer on the same Wi-Fi. Sideloadly can also refresh automatically over Wi-Fi: see its
"Automatic refresh" option. Your settings, PIN and hidden lists stay as they were.

## B. Build it on a Mac

You need a Mac with **Xcode** (free from the Mac App Store) and a free Apple ID.

```sh
brew install xcodegen                      # https://brew.sh if you don't have Homebrew
cd ios
cp Config/Local.example.xcconfig Config/Local.xcconfig
# edit Config/Local.xcconfig: your Team ID and a bundle id of your own, e.g. com.yourname.safetube
xcodegen generate
open SafeTube.xcodeproj
```

In Xcode: **Settings → Accounts** → add your Apple ID if it isn't there. Plug in the iPhone,
choose it at the top of the window, and press **Run** (▶). Then do step 5 above (Developer
Mode, trust the developer) the first time. Press Run again every 7 days to renew a free
install.

If you use Claude Code on your Mac, [CLAUDE.md](CLAUDE.md) has the same steps written for it:
open this folder in Claude Code and say "get this running on my phone".

## Make SafeTube the only YouTube on the phone

In the phone's **Settings → Screen Time** (set a Screen Time passcode that's different from
the phone's):

- Delete the YouTube app, then **Content & Privacy Restrictions → iTunes & App Store
  Purchases → Installing Apps → Don't Allow**, so it can't come back.
- **Content & Privacy Restrictions → Allowed Apps**: turn off **Safari** (or limit websites in
  **Content Restrictions → Web Content**), so YouTube can't be opened in a browser.
- For a younger child, **Settings → Accessibility → Guided Access** keeps the phone inside
  SafeTube until you triple-click the side button and enter a code.

## Getting back to the settings

- Hold the **YouTube logo** (top left) for **three seconds**, or
- long-press the **SafeTube icon** on the Home Screen and choose **Parent settings**.

Both ask for your PIN.

## How the iPhone app is put together

| Path | What it is |
| --- | --- |
| `project.yml` | The XcodeGen description of the Xcode project (the `.xcodeproj` is generated, not committed). |
| `SafeTube/` | The app: the YouTube web view and its bridge (`MainViewController`), the parent screens' bridge (`ParentViewController`), settings (`Prefs`, API keys in the Keychain) and the SQLite decision cache. |
| `SafeTubeCore/` | The filtering engine ported from `core/` to Swift, as a Swift package. `swift test --package-path ios/SafeTubeCore` runs its tests on a Mac; some of them read the Kotlin files and fail if a prompt or default differs between the two apps. |
| `../app/src/main/assets/filter.js`, `../app/src/main/assets/parent/` | Shared with Android and used as they are. Change them there and both apps pick it up. |

Things that work a little differently from Android:

- `filter.js` talks to `window.SafeTubeAndroid`; on iPhone a small script defines that object
  first and passes messages to the app. The parent screens' synchronous calls go through
  `window.prompt()`, which the app answers.
- iPhones can't turn on third-party cookies in an app's web view. Google sign-in uses
  ordinary page-to-page redirects, which work without them, and SafeTube sends the same user
  agent as Safari so Google treats it like Safari.
- There's no way to block screenshots of the settings screen on iOS; SafeTube does hide it
  from the app switcher.
