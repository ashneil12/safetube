# Simple OpenRouter checker — 0.6.1 build 8

Ash requested a simple recommended primary checker: Clef Flash through OpenRouter, paste a key, keep other services and editable decision models. Backup configuration is optional advanced UI; default-on outage protection stays visible.

## Changes and migration

- New provider `OPENROUTER_DECISIONS` uses the Decisions API and defaults to `cloudflare/clef-flash`. Key-only configuration; no Cloudflare account ID. Custom OpenRouter Decisions model IDs are editable. Existing CLEF/JEV/Claude/compatible/Off selections and credentials are preserved.
- New onboarding recommends OpenRouter only when the parent chooses AI. Keyword helper remains a separate chat-model feature. Decision requests here are text-only, even where an upstream model supports images.
- Pending loaded cards/links far offscreen remain hidden without a bridge request. Checking begins within a 240px viewport margin. Direct-watch and current playback checks bypass card geometry. Scroll/resize, recycled cards and batching delays are covered by real browser tests; a previous safe video never implicitly approves a channel.
- Clef-family requests are preflighted and split into at most 1800 serialized UTF-8 state bytes, conservatively below the documented roughly2000 text-token serving limit. No parent rules are dropped. If even one video's state cannot fit, the classifier refuses the batch before sending it; the existing fallback/fail-closed behavior applies.
- A format marker invalidates old potentially truncated Clef scores. A separate outage identity matches the old policy/provider hash, preserving an existing outage lock across upgrade. Real changes to rules, model or key still change that identity.

## Sources checked 26 October 2026

- https://openrouter.ai/cloudflare/clef-flash
- https://openrouter.ai/docs/guides/community/multimodal-decisions
- https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-request

## Verification and limits

Kotlin52 tests and Swift66 tests pass locally. Source-built parent/browser suites cover new setup, preservation, request demand and fail-closed behavior. Native iOS unsigned device Release build passes. GitHub PR/main native builds and published binary verification are release gates.

HTTP request/response contracts are tested with injected local transport. No live OpenRouter key is available, so authenticated provider calls, actual model quality and measured savings are unverified. There is no claim that a small decision model reliably recognizes everything in a video. Physical Fire/iPhone acceptance is distinct from source-built browser tests and native compilation.

Independent review checked routing, state bounds, ID remapping, preserved caches/outage latches and viewport hiding. Minor copy findings were corrected. No changes to billing, API keys or safety defaults on any installed device. Rollback is a normal code revert; old public binaries are archived before publishing the new build.
