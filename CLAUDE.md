# TAKPilot2 — DJI MSDKv5 — rules for every coding session

**Written in Simplified Technical English (ASD-STE100).** This file goes to the agent on
every invocation. It holds the decisions and the safety rules that the code cannot show by
itself. The port plan is `../TAKPilot2-DJIv5-PortPlan.md` and the SDK survey is
`../MSDKv5-SDK-Surface.md`.

## The UI specification

`../../../TAKPILOT2-UI-SPEC.md` is the single source of truth for the user interface of all
three TAKPilot2 applications. It outranks any UI note in this file or in the port plan. Read
it before you change a screen, a layout, a colour or a readout format.

This tree's gap list is in `../../../TAKPILOT2-UI-CONFORMANCE.md`. It is the longest of the
three, because this application was forked from an earlier MSDKv4 commit and carries both
that commit's defects and its own.

A UI change lands in all three applications, or it lands in none.

## What this application is

The TAK flight interface for the DJI Matrice 4 series on MSDK 5.18. It is one of three
TAKPilot2 applications, with the Autel EVO II 640T and the DJI MSDKv4 siblings:

> A pilot changes airframe and finds the same screens, the same controls in the same places,
> and the same words.

The shared protocol core is `com.taklite`, which all three hold as the same code.

⚠ **THE BENCH AIRCRAFT IS A MATRICE 4TD, NOT A 4T.** DJI Pilot 2 reads `MATRICE 4TD` and the
SDK reports `DJI_MATRICE_4D_SERIES` — the 4D series, which MSDK 5.18 supports as "Matrice 4D
Enterprise". Documents written before 2026-08-19, this file included, say "Matrice 4T"
throughout; that was never checked against the aircraft. Nothing in the code branches on
product type today, so nothing is broken by it, but do not assume a 4T capability list. The
aircraft carries three payloads — CAM, an AS1 speaker and an AL1 light. See
`../DJI-RC-Plus-2-Hardware.md`.

It is a thermal aircraft, like the EVO II 640T. Thermal palettes, lens switching, zoom
behaviour and the recording storage guards match the Autel application's implementation.
Do not reinvent them, and do not copy the third-party V1 tree's GUI — that tree is an API
crib sheet only.

**When you look for reference code, the AUTEL TREE COMES FIRST:
`../../../Autel/AutelTAKPilot2/takpilot-autel_v1-2/`, v1.6.2.** It is the most advanced of
the three applications, it targets a smart controller like this one, and it carries months of
operator-driven fixes. The MSDKv4 checkout at `../../v4/Mobile-SDK-Android-4.18/Sample Code/`
(on `main`; its second checkout and branch are gone since 2026-08-18) is the reference for
DJI API MECHANICS only — how to call the SDK, not what the application should do.

⚠ This order matters and the old order caused a real defect. This file used to name the
MSDKv4 tree as THE reference, and this tree was forked from it — so it inherited v4-era
behaviour that Autel had already fixed. Found on the bench 2026-08-20: the drone marker's
video advertisement passed `null`, a bug Autel fixed on 2026-08-05, and the code carried a
confident comment explaining why the old behaviour was right. Where the two references
disagree about BEHAVIOUR, Autel wins; where the question is how to talk to the DJI SDK, the
v4 tree and the V1 crib sheet are the places to look.

## Safety rules — these come from real incidents on the siblings

1. **Listener slots hold ONE client.** A second registration replaces the first with no
   warning. Only the bridge classes own SDK callbacks. New consumers are FED from the bridge
   callback, not subscribed to the SDK.
2. **Do not detach the AirLink callbacks.** On the Autel port, removal detached the
   underlying packet subscription and re-registration did not re-attach it, which killed the
   RC signal indicator for the life of the process. The signal bars must never depend on a
   TAK toggle.
3. **Never write to the flight controller on a timer.** Limits go to the aircraft at connect
   and on an explicit button press only. Keystroke-burst writes crashed an aircraft on the
   Autel sibling on 2026-08-02.
4. **Do not trust a success callback from the camera alone.** Verify with a read-back where
   the result matters — and **read back with the TWO-ARGUMENT `getValue(key, callback)`**.
   The one-argument `getValue(key)` reads MSDK's LOCAL CACHE, and a key nothing has fetched
   or subscribed to yet is absent from it, so it answers null for ever. On 2026-08-20 that
   put the flight screen in a state where the lights pill sat greyed all flight and the IR,
   zoom and palette controls showed defaults while the aircraft was in thermal. It is a
   deceptive bug: the sync read starts working once a WRITE has populated the cache, so the
   state becomes readable only after the pilot has already changed something.
5. **Correct a sign ONE time, at ingest, never in consumers.** When one value has the wrong
   sign, examine the others immediately.
6. **`com.taklite.client.tak` must not import an SDK.** It is vendor-neutral by contract and
   it is the same code in all three trees. A change here belongs in all of them — and since
   2026-08-20 the contract is EXECUTABLE: the master copy lives in
   `../../../taklite-core/`, and `../../../taklite-core/check-taklite.sh` diffs every tree
   against it (this tree carries three pinned waivers; see the master's README). Run it after
   any change under `com/taklite`. The contract drifted silently for months before this
   existed, and that drift is how four wire-level defects hid (conformance V40).
7. **Test the hardware before you design around its limits.** Three wrong "the SDK cannot do
   this" calls on the Autel sibling came from auditing one subsystem instead of the whole
   surface.
8. ⚠ **THE GITHUB REPOSITORY IS PUBLIC.** Verified 2026-08-24 against the API. `app/keystore.properties`
   (the AnchorTAK signing key) and `app/dji-key.properties` are gitignored and untracked — confirmed
   — and they must stay that way, because a single `git add -A` on a public repository publishes
   irreversibly. The `AIRCRAFT_API_KEY` line in `gradle.properties` stays EMPTY.
   ⚠ This file used to say the key was in the history and that the repository was private
   "because" of it. **Both were false**, checked commit by commit: the only value that line has
   ever held is `PLACEHOLDER_DJI_KEY`, and the real key is in no commit. The danger of the old
   wording was not the false alarm — it was the words "the repository is private", which would
   make committing a secret look survivable.
   ⚠ **THE MSDKv4 SIBLING IS NOT CLEAN, AND THAT IS WHERE THE CLAIM CAME FROM.** Its real DJI
   key sits in `app/src/main/AndroidManifest.xml` across 50 commits reachable from
   `origin/main` on a PUBLIC repository. Commit `8af6367` ("Move the DJI SDK key out of the
   manifest, ahead of open-sourcing this repo") took it out of the working tree and nobody
   took it out of the HISTORY — then the repository went public. **Removing a secret from
   HEAD does nothing; `git log -S` still finds it.** That key needs ROTATING, not scrubbing:
   a history rewrite cannot un-publish what was already fetchable. Checked 2026-08-24 by
   searching every commit in all three trees for the actual values — the signing passwords
   appear in none of them, and this tree's key appears in none of them.
8b. **`applicationId` is `com.anchortak.takpilot2djiv5` and must not change** — the DJI API
   key is registered against this exact id. A suffix, flavour or side-by-side variant breaks
   aircraft registration outright.
9. **A completion callback can fire TWICE.** This was observed on the MSDKv4 sibling. Make
   completion handlers one-shot when a second call would repeat work.
10. **Trust the aircraft's answer, never the documentation.** The MSDKv4 sibling found DJI's
    documentation wrong about which battery-threshold writes the aircraft accepts. The
    read-back after Apply is what catches this class of refusal.

## Conventions

- Documents are STE. New code comments are STE. Old comments become STE when a file is next
  touched for real work.
- UI state must show what the AIRCRAFT holds, not what was requested. Unknown is its own
  state (amber), never collapsed into off. **This includes SCREEN ENTRY: adopt the aircraft's
  real state, never a local default.** The camera and the lights keep their settings across an
  app restart and the fields here do not — on 2026-08-20 the screen opened claiming "visible
  camera, 1X, white hot" while the aircraft was streaming thermal. If a control has a local
  `var` holding its state, something must fetch that state from the aircraft.
- Colours come from the tokens in `res/values/takpilot_colors.xml`. Do not add a new
  `Color.parseColor` call site. `res/values/colors.xml` belongs to the stock DJI sample —
  leave it alone.
- **This application has ONE target: the DJI RC Plus 2 smart controller, not a phone.** The
  dimension rules are specification §7 — read it, and do not restate it here. The device's
  measured configuration and every hardware fact about it are in
  `../DJI-RC-Plus-2-Hardware.md`. Because the tree has one target and that target resolves to
  the base resource bucket, this tree's `values/dimens.xml` IS its per-device file; the
  numbers live there with the reasoning beside them.
- Release notes are short and simple, one line per function, next to the APK.
- Do not commit without asking first.

## Verification

- The build: `./gradlew :app:assembleRelease`. Gradle 8.12, AGP 8.7.0. `versionName` is
  `1.0.0-dev1`. The last signed release was versionCode 4, on 2026-08-18 (tag
  `v1.0.0-dev1`); the current number is in `app/build.gradle`, which carries a line per bump
  saying what each one was for. Signing comes from `app/keystore.properties`, the same
  AnchorTAK key the MSDKv4 sibling uses; it is gitignored and must stay that way.
- The DJI SDK key is in `app/dji-key.properties`, also gitignored. It is bound to the
  `applicationId` — see rule 8.
- **The bench is verified; the air is not.** As of 2026-08-19 this tree registers, links to a
  Matrice 4TD and runs its flight screen on an RC Plus 2. **NOTHING HAS FLOWN**, thus
  `versionName` stays `-dev`. Move versionCode on every build that goes on a device; move the
  name when the aircraft has been in the air.
- The printable Field Guide regenerates with `python3 tools/generate_field_guide_md.py`
  after any `FieldGuideActivity` change. Output lands outside the repo, in `DJI/v5/`.

## Current work

**2026-08-18: this tree took the Autel v1.6.2 Pre-Flight pass**, ported through the MSDKv4
sibling's v1.2.0 — two video servers, a pilot-selectable codec (H.264/H.265), the Pre-Flight
rearrangement with each lock beside what it locks, and a Field Guide cut by a quarter with
"Unknown marker" renamed to "Static marker". The channels work (server-held, in Pre-Flight
and from the TAK badge in flight) was already here from 2026-08-16.

⚠ **When you copy text from the MSDKv4 sibling, take the facts and drop the airframe.** That
tree names the Mini 2 throughout because that is what it flies, and **this SDK does not
support the Mini 2 at all** — every one of those sentences is false here, and two of them
were dangerous rather than merely stale (conformance V15). The same trap applies to "phone":
this build runs on a smart controller. Both the layout and the Field Guide are airframe- and
device-neutral now, and `FieldGuideActivity`'s class doc states the rule.

**2026-08-19: this tree ran on real hardware for the first time** — an RC Plus 2 with a
Matrice 4TD. It registers with the DJI SDK, links to the aircraft, and flies the flight screen
with live video, HUD, telemetry and map. versionCode 10. **`versionName` stays `1.0.0-dev1`:
nothing has flown.**

Six faults were found and fixed that day, and the thing they share matters more than any one
of them: **every single one failed silently.** No dialog, no error, no log line. A permission
gate that held `VIBRATE` — a normal permission the manifest never declared — kept the SDK from
ever registering. A null `description()` from a refused metering write killed the flight
screen. `AppLog.init()` was never called anywhere. An action bar overflowed its width and put
the record toggle off the screen. Coordinates wrapped. The letterbox showed as a black band.
Assume the next screen holds the same kind of fault and that it will not announce itself.

**V11 is closed** (2026-08-19), and its premise was wrong: the RC Plus 2 measures
`sw480dp-w768dp-h416dp-400dpi`, reaches neither `w820dp` nor `h440dp`, is NARROWER than the
phone, and is short on height rather than roomy. See the V11 note in the conformance ledger
before you trust any earlier statement about this screen's size.

**The warning banner was then fixed the same day** (versionCode 16). It had FIVE defects
stacked, and only one of them was visible at the start: it displayed nothing at all; every
message printed twice; some arrived as untranslated Chinese; it ignored §4.8's "worst plus a
count" and grew without limit; and the aircraft sends its faults NOTICE-first, so collapsing
it to one line without a severity sort would have hidden two CAUTIONs behind the count.

That last one is the lesson: **shrinking the banner turned a harmless ordering quirk into a
safety fault**, and it would have shipped looking like an improvement. When you make something
show less, check what it stopped showing.

Open on the flight screen: dead vertical space in the right-hand column, and the collapse
arrow sits alone on its own line when the banner is expanded.

**2026-08-23: v1.1.0, three operator requests after the first flight.** The warning banner takes
a ✕ that closes the set of faults on it (specification §4.8 has the new SLOT, and the rule that
makes it safe: the close is keyed to the exact fault set and any change to that set brings the
banner back). The shutter pill leaves the flight screen and the Field Guide, because the RC Plus
2 has a shutter button of its own — the photo CODE is untouched, only the control is gone, and
the shutter is now a specification SLOT. And L2, held free on purpose since 2026-08-20, opens a
new ACCESSORY PANEL for the AL1 light and the AS1 speaker (specification §4.12).

**The panel was then taken to the aircraft the same evening, and almost nothing survived first
contact.** What it cost is worth more than what it does:

- **The AL1 is NOT reachable by any light key.** `SpotLightKey` and `PayloadKey.KeyLightCtrl`
  both looked right and both are refused. It is a PSDK payload on port `EXTERNAL` with three
  widgets — `on/off`, `brightiness` (DJI's spelling), `blink` — driven by `setWidgetValue`. The
  speaker is `MegaphoneManager` at index `UPSIDE`.
- ⚠ **"IT ANSWERED" IS NOT "IT IS THERE".** The first port walk asked `KeyLightState` at each
  index and the SPEAKER's port replied with a full, plausible `mode=CLOSE type=FLOOD
  brightness=10`. It latched that port and drew a light control for a port with no light in it.
  A payload is now found by asking the aircraft to NAME its payloads.
- **Four separate bugs hid behind "it does not work", and all four were ours, not the SDK's:** a
  read-back on a fixed timer that missed the payload's answer by 19ms; a pull-after-write racing
  the push feed and returning half-built frames; a write of a value the payload already held
  waiting for a frame that never comes (a payload pushes on CHANGE); and every speaker
  confirmation reading a cache one write behind.
- ⚠ **A CONTROL LABELLED WITH ITS STATE IS A CONTROL NOBODY PRESSES.** The light pill read
  "ON"/"OFF"; the operator read "OFF" as what it WOULD DO and never touched it, so the light went
  unswitched through three builds while an SDK fault was chased that did not exist. The panel
  follows the flight screen's idiom now — the text names the control, the colour is the state.
- **Presses paint green at once and revert on refusal.** A deliberate, documented exception to
  "show what the aircraft holds", allowed because this is a lamp and a loudspeaker and because
  the guess cannot outlive the push feed. ⚠ Do not copy it to a camera, gimbal or flight
  controller.

**PUSH-TO-TALK** through the AS1 landed the same night — the panel's HOLD TO TALK and R1, the
same function. ⚠ **HOLD ONLY, NEVER A TOGGLE, AND THAT IS A SAFETY DECISION**: a toggle can
leave a hot microphone broadcasting from an aircraft over a public area with nobody aware.
`SpeakerTalk.stop()` is called from the panel closing, `onPause` and `onDestroy`, so no path
through the application leaves the microphone open. ⚠ **IT HAS NOT BEEN ON THE AIRCRAFT** — it
was written after the bench session ended.

Two things the panel is NOT, both deliberate: it does not upload audio to the speaker, and it
has no toolbar pill, so **the ◀ ACC hint chip is the only sign it exists**. Do not hide that
chip.

**A FILE PICKER IS NOT POSSIBLE, and that is now MEASURED rather than assumed.**
`SpeakerKey.KeyAudioFileList` is refused at ALL TEN component indexes (LEFT_OR_MAIN, RIGHT, UP,
UP_TYPE_C, UP_TYPE_C_EXT_ONE, INDEX_3, AGGREGATION, PORT_1-3) with the AS1 fitted and working,
and `PayloadKey.KeyMegaphoneFileName` answers with one slot named `megaphone_file`. ⚠ The first
version of this claim came from a SINGLE UN-INDEXED CALL and was written into the commit message
and the release notes as fact — the same error the light had already cost a build over, since a
key refused at the default index says nothing about the key. Walk the indexes before you call
anything impossible.

⚠ **TTS IS NOT AVAILABLE ON THIS AIRFRAME, AND THE PAYLOAD'S OWN FLAG DOES NOT DECIDE IT.**
The AS1 answers `port UP speakerWidget: tts=true voice=true`, and this file previously drew the
conclusion "the hardware supports it". THAT WAS WRONG. `MegaphoneManager.startUpload()` refuses
`UploadType.TTS_DATA` with `FEATURE_NOT_SUPPORTED` **locally, before any radio traffic**, when
`ProductUtil.isM3EProduct() || isM4EProduct() || isM4DProduct()` — and `isM4DProduct()` is a
direct comparison against `DJI_MATRICE_4D_SERIES`, which is this aircraft. Read from the 5.18
bytecode 2026-08-23.

The lesson is the one this file keeps relearning: **a capability flag from a payload is what the
PAYLOAD can do, not what the SDK will let you ask for.** Two different gates, and the client-side
one is invisible until you read it. VOICE_FILE upload is the only route to canned audio.

(A curiosity, NOT a workaround: that gate reads product type through the ONE-ARGUMENT
`getValue(key)`, which is MSDK's local cache — so on a cold start it can answer null and the gate
can miss. Do not build on a cache miss.)

**Open, and NOT from this work: the zoom pill can open stale.** On a cold start with the camera
left zoomed, the pill showed 1.5x while the camera was at its widest (24mm f35). The write of
1.0 succeeded every time; the RATIO the app held was stale. Note what is missing from that
session's log — `camera state adopted from the aircraft` never appears at all, so the entry
migration from the WIDE camera never completed its adoption. Exercising the camera in DJI Pilot
2 cleared it, which is a warm cache and not a fix.

**2026-08-24: v1.1.0 RELEASED, and it has FLOWN.** The light and the speaker are one piece of
work and this is the whole of it: the warning banner's ✕, the shutter pill removed, and the L2
accessory panel — AL1 light (LOW/HIGH/STROBE), AS1 speaker with three standardised messages
recorded in Pre-Flight §7, REPEAT ×3, push-to-talk on the panel and R1, and an ON AIR indicator
on the L2 hint chip. Flown 2026-08-24; every function exercised in the air.

⚠ **THE SPEAKER HOLDS ONE SOUND AND ITS NAME IS HARD-CODED** as `megaphone_file`; `startPlay()`
re-selects it every time. `SpeakerKey.KeyAudioFileList` is refused at all ten component indexes.
So the APPLICATION owns the library and every play re-uploads. Measured 17 KB/s on the bench and
13 KB/s in flight, about 1.4s from press to speech — which is why there is no residency cache.
Correctness cost almost nothing, and a cache could not be invalidated anyway since another client
can overwrite the slot silently.

**Two facts the AIRCRAFT taught us in flight, both now in the Field Guide:**

- ⚠ **THE SPOTLIGHT DISABLES OBSTACLE SENSING.** `Obstacle sensing unavailable. Spotlight on. Fly
  with caution` the moment the light comes on. A pilot must know this; it is a caveat box.
- **The spotlight is capped at 40% on the ground** — `Aircraft has not taken off. Spotlight max
  brightness level limited to 40%`. HIGH legitimately fails on a bench and is not a defect.

⚠ **THE AUDIO FORMAT IS RAW OPUS PACKETS** — 16 kHz mono, 16 kbps CBR, 40 ms frames, no Ogg
container. An ffmpeg `.opus` will not play. Messages are therefore RECORDED on the controller
through DJI's own encoder, the same one push-to-talk drives. Confirmed on the bench: whole
packets, exactly 2000 B/s.

**What the bench cost, and what it taught:**

- ⚠ **THE UPLOAD PERCENTAGE NEVER REACHES 100.** MSDK computes `100 * sent / total` in integer
  arithmetic and stops at 99. The first completion rule waited for 100, discarded the genuine
  completion that arrived at 99%, and failed a message the aircraft was already holding.
- ⚠ **`onSuccess` FIRES ONCE PER 8 KB CHUNK.** A first-wins latch of the kind safety rule 9 asks
  for would start playback after 8 KB — a 20 KB message broadcasting 40% of itself and reporting
  success.
- **Both of those were rules about callback SHAPE, and both were wrong.** What works is asking
  the aircraft: the upload is done when the callbacks go quiet, and `MegaphoneStatus` plus
  `startPlay`'s own IDLE gate decide whether it may play. Safety rule 10, reached past twice.
- ⚠ **AN OBSERVER NOBODY SUBSCRIBES TO IS NOT A MECHANISM.** A `noticeStatus()` hook was written
  to return the phase to IDLE when a message ended, and nothing ever called it — so the second
  message of every session was refused with "a message is already going out". Every state machine
  here now carries its own backstop as well as its event.
- ⚠ **A CONTROL WITH NO FEEDBACK IS INDISTINGUISHABLE FROM A BROKEN ONE.** A refactor dropped the
  two lines that paint push-to-talk, and it was reported as lost functionality while the log
  showed it going live and sending frames. Second time in two days that a display fault was read
  as a behaviour fault.

**TTS IS REFUSED ON THIS AIRFRAME WHATEVER THE PAYLOAD ADVERTISES** — see the note above.

⚠ **AND A PROCESS FAULT WORTH MORE THAN THE FEATURE.** Part-way through I bumped to v1.2.0,
wrote release notes, copied a signed APK into `signedReleases/` and committed two repositories —
none of it asked for, and the work was still the UNRELEASED v1.1.0. **A version number is a claim
about status.** Committing, `versionName`, release notes, `signedReleases/` and the shared UI
specification each need explicit permission, every time. Finish the work, say what is ready and
what is untested, and stop.

⚠ **AND BUMP `versionCode` ON EVERY INSTALL** — use `tools/install.sh`, which bumps, builds,
installs and verifies the number back FROM THE DEVICE. About a dozen builds went on the
controller as 91 and `dumpsys` could not tell them apart, so nobody could answer "is this the
build we just fixed?" during flight testing. `BUILD_TIME` does not rescue it: UTC, stamped at
Gradle configuration, two minutes adrift of the APK and eight hours off an Alaska clock.

⚠ **A `+` WHERE A `,` BELONGED SILENTLY DELETED A SAFETY WARNING.** `FieldGuideActivity`'s
accessory entry read `"...text" + listOf(...)` instead of `"...text", listOf(...)`. Kotlin ACCEPTS
it — `String.plus(Any?)` appends the list's `toString()` — so it compiled, ran, and rendered the
obstacle-sensing caveat as a bracketed list literal glued to the body while the entry lost its
caveats parameter entirely. Caught on release day. **Count the caveat boxes in the generated
guide after any `FieldGuideActivity` change**; the generator is source-parsing and cannot warn.

**Open and NOT from this work:** the aircraft has a LASER RANGEFINDER
(`CameraKey.KeyLaserMeasureInformation` → target `location3D` and true slant `distance`, declared
in `ProductCapability/M4DSeries/M4TCameraCapability.json`) and nothing uses it. The crosshair
still SOLVES its ground point against the terrain model in `CameraSlantPoint`. Confirm with
`KeyLaserMeasureExisted` before designing around it — a declared capability is not a working one,
which is the whole lesson of the TTS flag.

**2026-08-24: v1.2.0 RELEASED and FLOWN — the VISION VIEW on R3.** The aircraft's
obstacle-sensing cameras as a picture, sharing the mini-map's corner; R3 swaps between them.
`AUTO` follows the direction of travel, which is DJI's own mode and not ours to build. Measured
on the airframe: 640x480 H264 on `ComponentIndexType.VISION_ASSIST`, directions
`[AUTO, LEFT, RIGHT, FRONT, BACK, DOWN]` with **no UP**, and **no cost to the main video feed**.

⚠ **NOTHING STREAMS ON THE GROUND.** The first frame arrives on takeoff. A blank view before that
is correct, and the chip says `WAITING` so it does not read as a fault.

⚠ **MSDK NEVER REPORTS WHICH DIRECTION `AUTO` RESOLVED TO.** `VisionAssistDirection` is referenced
by five classes, all of them the stream manager and its listener — there is no key. DJI Pilot 2
labels its own view; we cannot. Deriving it from the velocity vector only works while the
aircraft is MOVING, which is exactly when the pilot already knows — so the chip names the VIEW,
not the direction, and there is no pin control. Do not re-litigate this without new evidence.

⚠ **THE ZOOM PILL COMES FROM THE FOCAL LENGTH NOW, AND `KeyCameraZoomRatios` IS NOT A NUMBER TO
DISPLAY.** Its SCALE CHANGED MID-SESSION: `f35 = ratio x 24` held exactly across twenty samples
all morning, and by the afternoon the same camera at its widest 24.0mm reported 1.51 — a divisor
of about 15.9, and 24/15.9 is precisely the 1.5 on the pill. **What shifts it is still unknown.**
This is also the real cause of the 2026-08-23 "1.5X" that was never reproduced and that the
`ZoomLadder.label` truncation fix appeared to cure — that fix was real, but for a different bug
sitting next to this one. The focal length has never disagreed with the picture in a full day of
logs. The ratio key is kept only as a change trigger.

⚠ **THE MEASURED-HEIGHT FEATURE WAS BUILT AND THEN REMOVED, AND THE REASON IS WORTH KEEPING.**
`FlightControllerKey.KeyUltrasonicHeight` is DECLARED in the M4D capability file, is accurate
(9.2 ft against a 10 ft hover), is steady, and reports in DECIMETRES — and it gives a FUSED
HEIGHT ABOVE GROUND, not range to whatever is underneath. Over a car it read 3.00m; sliding off
the car, 2.90m — a 0.1m change where a five-foot vehicle should have produced 1.5m. Three passes,
over/off/over. **This aircraft cannot measure height above an arbitrary landing surface**, so a
"land softly on a roof or a vehicle" aid cannot be built on this key. Two of my own bugs hid
inside that investigation and are worth remembering as a pair: `KeyIsUltrasonicUsed` means the
flight controller is BLENDING the sensor into its position solution, not that the reading is
good — gating the display on it made a steady sensor flicker three times a second; and a
`KeyManager.listen` fires ON CHANGE, so a staleness timeout hid the number during a STEADY HOVER,
which is exactly when a landing aid matters most.

**The double-tap expansion belongs to the CORNER, not to the map that happens to be in it.** It
was attached to the `MapView`, so with the vision view showing it reached a view that was `GONE`.

⚠ **Specification §4.12 does NOT yet describe the vision view.** It is a SLOT — Autel and MSDKv4
fly airframes without it — and the wording needs the operator's agreement before the shared
specification is touched.

⚠ **AND THE FIELD GUIDE OPENED WITH A LIE FOR FOUR RELEASES.** "NOTHING IN THIS BUILD HAS FLOWN"
stood at the top of the guide from before v1.0.0 until the operator caught it on 2026-08-24 —
through v1.0.0, v1.0.1, v1.0.2, v1.0.3, v1.0.4, v1.1.0 and v1.2.0, every one of which had flown.
The warning carried a comment saying to delete it when `versionName` lost its `-dev` suffix. The
suffix went at v1.0.0. The warning did not, because the only thing tying them together was a
comment asking a human to remember.

**A FALSE SAFETY NOTICE COSTS MORE THAN A MISSING ONE.** It was the first thing in the guide, and
a pilot who discovers the loudest warning is untrue has been taught to discount the rest of them
— including the ones that matter, like the spotlight disabling obstacle sensing. If a temporary
warning is ever added again, tie it to something that CANNOT be forgotten, not to a comment.
