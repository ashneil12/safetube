# Release verification: parent controls and safe fallback

Scope: Android APK, unsigned sideloadable iOS IPA, shared parent screens and YouTube page filter.
Authority: Ash requested implementation, GitHub upload and a new build in this chat on 26 October 2026.

## Required evidence

1. Kotlin core tests cover local priority, only-show rules, exceptions/cache keys, invalid setup, fallback and missing/invalid AI answers.
2. Swift core tests cover matching iOS behavior.
3. Parent browser tests exercise onboarding settings persistence, preset reuse and video inspection.
4. Page browser tests exercise whole-page outage lock, late allow replies, reset and playback pause.
5. Native Android and iOS builds compile from the proposed revision.
6. Independent reviewer examines final patch and raw test output, with any findings resolved before merge.
7. GitHub main workflow succeeds; latest release points at that exact main commit and contains APK and IPA.
8. Downloaded APK signature matches the previous release; assets have recorded checksums.

## Boundaries

The source-built browser tests use a stand-in native bridge and mock YouTube markup. They do not prove current YouTube markup on a physical Fire tablet. Engine tests use controlled provider responses; no parent's live AI key is available for quota exhaustion acceptance. Unsigned IPA compilation is distinct from installed iPhone acceptance. Those gaps must remain explicit in the final handoff.

## Recovery

Code changes are in commits on a feature branch and can be reverted by PR. Existing signing secrets are reused. No user settings/database is edited on a real device. Published binaries should be archived with checksums before replacing latest assets.

## Local checks before PR

- Kotlin: 41 tests, zero failures (`JAVA_HOME=/tmp/safetube-toolchain/jdk-17.0.21+9/Contents/Home SKIP_ANDROID=1 ./gradlew :core:test`). Results: `core/build/test-results/test/`.
- Swift: 54 tests passed and unsigned simulator compilation succeeded. Local raw logs: `/tmp/safetube-swift-tests.log`, `/tmp/safetube-ios-build.log`.
- Browser: 70 page-filter and 65 parent checks passed. Shared suites cover onboarding, later settings, details, legacy dialog fallback, outage styling, direct-watch retry and native-bridge mocks. Captures: `tools/parent-test/screenshots/`.
- Previous APK and IPA archived under `/tmp/safetube-toolchain/previous-release/`. Prior APK signer SHA-256: `70:E6:16:86:65:2B:64:FE:D7:92:A4:22:C1:23:12:2B:46:66:F3:85:50:00:5A:52:DF:3A:94:0C:0B:76:F9:C7`.

Independent review reproduced two failures: direct-watch outage retry did not submit a fresh check, and inline visibility could reveal descendants behind the safety wall. Both received fixes and browser regressions. Recovery also probes AI availability when all submitted videos already have local decisions, without replacing those local decisions. CI remains the Android-native build gate.

Skill-upkeep assessment: no general skill or harness changes are required. Project-specific verification and release boundaries are documented here.
