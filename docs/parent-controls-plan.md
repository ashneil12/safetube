# Parent controls and safe AI fallback

Requested 26 October 2026. Scope: Android, iOS, shared onboarding and parent UI, GitHub release.

## Acceptance checklist

- Tap a video in parent lists to inspect its title, channel, available metadata and block explanation; parent can open YouTube to inspect the actual video.
- Explain the current Strict/Balanced/Relaxed threshold and any stored AI score without pretending it is proof of a video's content.
- Offer onboarding hide presets in later parent settings without deleting custom rules.
- Add parent-written AI context for benign subjects such as Planet Coaster; keyword and manual blocks retain their documented priority.
- Only-show description rejects nonmatching content, requires AI, and participates in cache invalidation.
- Primary failure, partial answer or invalid scores try the configured fallback; failed final check stays hidden.
- With outage protection on (default), all YouTube content is covered and paused after a final AI failure, across navigation; cache/local results cannot clear it.
- A new successful check can recover; settings changes discard stale in-flight answers.
- Onboarding exposes content mode, strictness, AI context, primary/fallback checker and outage protection before completion.
- Preserve hold-to-hide and current watch-time tracking.
- Verify engine suites, browser flows, native compilation and independent review; publish APK and IPA from the verified main revision.

## Design

Reuse existing SafeTube onboarding preset cards, parent rules cards and video lists. Reference captures live under tools/parent-test/references. Keep the existing typography, rounded white cards, teal selected state and dark buttons. Detail view uses an accessible dialog; no new design system.

## Limits

No specific Planet Coaster or Romanian video URL or device decision database was supplied. The release adds inspection and tuning; it cannot retrospectively prove those particular block causes. AI uses available metadata, not a full viewing or transcript. Public oEmbed may not supply the complete description; show that limitation and offer parent-only video access.

## Authority

Ash authorized implementation, upload to GitHub and publication of a new build. Existing repository signing secrets are reused; no credentials are generated or changed. Physical tablet acceptance is distinct from engine/browser/native build evidence.
