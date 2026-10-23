# Getting SafeTube onto an iPhone or iPad (instructions for Claude Code on a Mac)

The person asking is most likely a parent, not a developer. They want the SafeTube app on
their child's iPhone or iPad, signed with their own Apple ID, without the App Store. Talk to
them in plain, friendly English, do every command yourself, and only ask them for the things
only they can do (unlock the phone, tap Trust, type their Apple ID password into Xcode).
Never ask them to paste a password into the chat.

Run everything from the `ios/` folder of this repository unless it says otherwise.

## 1. Check the Mac

```sh
xcode-select -p && xcodebuild -version
```

- If Xcode is missing: ask them to install **Xcode** from the Mac App Store (it's free but
  large; it can take an hour), open it once and accept the licence. Then carry on. The
  Command Line Tools alone are not enough.
- If `xcode-select -p` points at CommandLineTools while Xcode is installed:
  `sudo xcode-select -s /Applications/Xcode.app` (they type their Mac password).
- Make sure the iOS platform is installed: `xcodebuild -downloadPlatform iOS` if a later build
  says the iOS SDK or platform is missing.

## 2. Install XcodeGen

```sh
which xcodegen || brew install xcodegen
```

If `brew` is missing, install Homebrew first (https://brew.sh; the installer asks for their
Mac password), then run `brew install xcodegen`.

## 3. Their Apple ID in Xcode

Signing needs their Apple ID signed in to Xcode. A free Apple ID is fine.

Ask them to open **Xcode → Settings → Accounts**, press **+**, choose **Apple ID** and sign in.
That creates a "Personal Team". Then find its Team ID:

```sh
defaults read com.apple.dt.Xcode IDEProvisioningTeamByIdentifier 2>/dev/null \
  || defaults read com.apple.dt.Xcode IDEProvisioningTeams 2>/dev/null
```

Look for `teamID = XXXXXXXXXX` (10 characters; a free account shows `isFreeProvisioningTeam = 1`).
If neither command shows it, it also appears in Xcode under the SafeTube target →
**Signing & Capabilities → Team** after step 5, or ask them to read it from
https://developer.apple.com/account (Membership details) if they have a paid account.

## 4. Team and bundle id

```sh
cp -n Config/Local.example.xcconfig Config/Local.xcconfig
```

Edit `Config/Local.xcconfig`:

- `DEVELOPMENT_TEAM = <their Team ID>`
- `PRODUCT_BUNDLE_IDENTIFIER = com.<something unique>.safetube`. Use something based on
  their name, e.g. `com.janesmith.safetube`. It must be unique across all of Apple: if
  signing later says the identifier "is not available" or "cannot be registered", change it
  and try again.

`Local.xcconfig` is ignored by git. Don't put their team id anywhere else, and don't commit.

## 5. Generate the project

```sh
xcodegen generate
```

This writes `SafeTube.xcodeproj` (not committed). Rerun it whenever `project.yml` changes.

## 6. Connect the phone

Ask them to plug the iPhone into the Mac with a cable, unlock it, and tap **Trust This
Computer** (and enter the phone's passcode) if asked. Then:

```sh
xcrun devicectl list devices
```

Note the phone's identifier (UUID) in the list. If the phone shows as unavailable or not
paired, ask them to unlock it and keep it unlocked, and check for the Trust prompt again.

**Developer Mode** (iOS 16 and later) must be on: on the phone, **Settings → Privacy &
Security → Developer Mode** → on, then restart and tap **Turn On**. The switch only appears
after the phone has been connected to Xcode, so if they can't find it, open Xcode with the
phone plugged in (Window → Devices and Simulators) and wait for it to finish "preparing" the
phone, then look again.

## 7. Build and install

```sh
xcodebuild -project SafeTube.xcodeproj -scheme SafeTube -configuration Release \
  -destination "id=<DEVICE_UDID>" -derivedDataPath build \
  -allowProvisioningUpdates build
```

If you get "No Accounts" or "No profiles for ..." errors, the Apple ID isn't signed in to
Xcode (step 3). If the bundle id is taken, change it (step 4). If the device id isn't
accepted, try the id from `xcodebuild -showdestinations -scheme SafeTube`
(it can differ from the devicectl UUID).

Then install it:

```sh
xcrun devicectl device install app --device <DEVICE_UUID> build/Build/Products/Release-iphoneos/SafeTube.app
```

(Fallback if the command line won't cooperate: `open SafeTube.xcodeproj`, pick the phone at
the top of the window, and press Run. Xcode handles the same steps.)

## 8. Trust the developer on the phone

The first time, iOS refuses to open the app ("Untrusted Developer"). Ask them to go to
**Settings → General → VPN & Device Management**, tap their Apple ID under **Developer App**
and tap **Trust**. Then open SafeTube from the Home Screen:

```sh
xcrun devicectl device process launch --device <DEVICE_UUID> <their bundle id>
```

## 9. Tell them how it works

- First launch walks them through choosing a parent PIN and what to hide (about a minute).
- To get back to the settings: hold the YouTube logo for three seconds, or long-press the
  SafeTube icon and choose **Parent settings**. Both ask for the PIN.
- **With a free Apple ID the app stops opening after 7 days.** Nothing is lost: plug the phone
  in and run step 7 again (or press Run in Xcode). A paid Apple
  Developer account ($99/year) makes an install last a year. Offer to set up a reminder.
- Recommend locking the phone down with Screen Time so SafeTube is the only way onto YouTube
  (delete the YouTube app, block app installs, turn off Safari or limit websites, and Guided
  Access for younger children). README.md in this folder has the exact settings.
- AI checking is optional; they set it up inside SafeTube's settings with their own key. Keys
  stay on the phone. Never put an API key in the code or the project.

## If something goes wrong

- Build errors in Swift code: read the error, fix it in `SafeTube/` or `SafeTubeCore/`, and run
  `swift test --package-path SafeTubeCore` for the engine. Don't edit anything under `../app/`
  or `../core/` to fix an iPhone-only problem; those files are shared with the Android app.
- "Could not launch": the developer isn't trusted yet (step 8) or Developer Mode is off (step 6).
- The app opened before but now won't: the 7-day free signing ran out. Reinstall (step 7).
- Free Apple IDs can only have 3 self-signed apps installed at once and register 10 app ids a
  week. If signing complains about limits, remove an old self-signed app from the phone or
  reuse the same bundle id.
