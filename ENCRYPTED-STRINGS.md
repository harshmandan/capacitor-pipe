# Encrypted strings

What `tools/strenc` hides, how, and — as importantly — what it does **not**.

## The goal, stated precisely

A `strings` or grep over a shipped release APK should not reveal that the app is
a YouTube extractor. That means the identifying **string literals** — hosts,
endpoints, client identifiers — must not appear in plaintext in the dex.

This is separate from R8. R8 renames **symbols** (class, method, field names);
it never touches string literals. So the two are complementary: R8 hides the
names, `tools/strenc` hides the literals, and neither does the other's job.

## What is encrypted

`tools/strenc` runs in `scripts/build-extractors.sh`, on the finished, relocated
extractor jars in `android/libs/`. It rewrites two forms in which a literal
reaches a class file:

- every `ldc "..."` that loads a matching string, and
- every `static final String` whose `ConstantValue` attribute is a matching
  string (attribute stripped, value reassigned in `<clinit>` via the injected
  `Codec`, so reflective reads still work).

"Matching" means the literal contains one of these, case-insensitively:

```
youtube  googlevideo  googleapis  googleusercontent  ytimg
gstatic  innertube    jnn-pa      pipepipe.dev        google.com/
```

The current set of encrypted literals is snapshotted in
[`tools/strenc/encrypted-strings.txt`](tools/strenc/encrypted-strings.txt),
regenerated on every `extractors:build`. **A diff there on an extractor bump is
the signal that the identifying surface changed** — review it the way you review
a DIVERGENCES.md change.

### Why not "google" on its own

It would sweep up `com.google.protobuf.*` message strings, some of which
protobuf-lite resolves reflectively — so the pattern list uses the narrower host
forms. The YouTube client-version strings (`com.google.ios.youtube/…`) still
match via `youtube`.

### Deliberately left in plaintext

Generic protocol vocabulary — `adaptiveFormats`, `serverAbrStreamingUrl`,
`signatureCipher`, `streamingData` — is **not** encrypted. It names no host or
client, is identical across every YouTube client including yt-dlp, and
encrypting it widens the transform's blast radius for almost no concealment.
`scripts/check-apk-obfuscation.sh` asserts a couple of these are *present*, so
the day one is encrypted or dropped, that file is updated rather than silently
rotting.

## How it works, and what that is worth

The cipher is XOR with a position-dependent key, reversed at runtime by a small
generated `ink.harsh.pipe.strenc.Codec` injected into `pipepipe-extractor.jar`
(one jar only — all three reference the same class, so a second copy would be a
duplicate-class error at dex time).

**This is obfuscation, not cryptography.** It defeats a static scan — `strings`,
grep, a decompiler's constant view. It does **not** defeat a debugger or a Frida
hook that watches `Codec.d` return the plaintext, because the plaintext must
exist in memory at the moment it is used. Anyone doing dynamic analysis recovers
everything. That boundary is intended: the goal above is about a *simple search*,
and this meets exactly that bar and no more.

## The invokedynamic dependency (fragile, load-bearing)

Since Java 9, `a + b` compiles to an `invokedynamic makeConcatWithConstants`
whose literal parts live in the `BootstrapMethods` constant pool, **not** as
`ldc` instructions — where `strenc` cannot see them. Left alone, roughly half the
identifying strings (`"https://www.youtube.com/…" + id`) survive encryption.

`tools/force-java17.init.gradle` therefore compiles the extractors with
`-XDstringConcat=inline`, restoring pre-9 `StringBuilder` concatenation, which
loads every literal via `ldc`. It is a `-XD` internal javac flag; if a future JDK
drops it, concatenation reverts to invokedynamic and the leak returns **silently**.
`check-apk-obfuscation.sh` over the release APK is the backstop that catches it.

## What is NOT covered — the plugin's own code

`strenc` post-processes the extractor **jars**. The plugin's own Kotlin
(`android/src/main/kotlin`) is shipped as **source** and compiled by the
consuming app, so there is no jar for `strenc` to rewrite, and only the
consumer's R8 touches it.

That code still contains identifying literals, and a grep over a release APK
finds them today:

- attestation diagnostics in `PipeAttestationBootstrap.kt` /
  `PipeBotGuardMinter.kt` — e.g. `"YouTube home has no client context"`;
- data-class names baked into Kotlin-generated `toString()` — e.g.
  `YoutubePageAttestationBootstrap`, `YoutubeGlobalPoToken`;
- the exception simple name `YoutubeMusicPremiumContentException`, which is a
  string literal by necessity (DIVERGENCES.md §10);
- a few `https://www.youtube.com` bases in `HttpCore.kt` / `PipeWebViewRuntime.kt`.

These are **not** hidden. They are our own diagnostics and type names, they carry
less than the endpoints did, and hiding them means obfuscating error messages
(worse crash logs for consumers) and renaming classes that mirror upstream for
port-sync. Whether that trade is worth making is a deliberate open decision, not
an oversight — recorded here so it stays visible.

## Tests

- `tools/strenc` unit tests (`bun`-independent, JVM): cipher symmetry, and a
  synthetic class transformed, loaded and run through both rewrite paths.
- `scripts/verify-strenc.sh`: every `extractors:build` proves on a **real**
  extractor class (`YoutubeApiDecoder.API_BASE_URL`) that the plaintext is gone
  **and** that it decrypts to the original at runtime.
- `scripts/check-apk-obfuscation.sh`: over the shipped APK, asserts the
  identifying literals are absent and the deliberately-kept vocabulary is present.
