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

The TAK flight interface for the DJI Matrice 4T on MSDK 5.18. It is one of three TAKPilot2
applications, with the Autel EVO II 640T and the DJI MSDKv4 siblings:

> A pilot changes airframe and finds the same screens, the same controls in the same places,
> and the same words.

The shared protocol core is `com.taklite`, which all three hold as the same code.

The M4T is a thermal aircraft, like the EVO II 640T. Thermal palettes, lens switching, zoom
behaviour and the recording storage guards match the Autel application's implementation.
Do not reinvent them, and do not copy the third-party V1 tree's GUI — that tree is an API
crib sheet only.

**When you look for reference code, use the MSDKv4 checkout at
`../../v4/Mobile-SDK-Android-4.18/Sample Code/`, on `main`.** That tree had a second
checkout (`SampleCode-device-compat`) and a second branch until 2026-08-18; both are gone,
and the two confused three sessions between them. There is now one directory and one branch.

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
   the result matters.
5. **Correct a sign ONE time, at ingest, never in consumers.** When one value has the wrong
   sign, examine the others immediately.
6. **`com.taklite.client.tak` must not import an SDK.** It is vendor-neutral by contract and
   it is the same code in all three trees. A change here belongs in all of them.
7. **Test the hardware before you design around its limits.** Three wrong "the SDK cannot do
   this" calls on the Autel sibling came from auditing one subsystem instead of the whole
   surface.
8. **`applicationId` is `com.anchortak.takpilot2djiv5` and must not change** — the DJI API
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
  state (amber), never collapsed into off.
- Colours come from the tokens in `res/values/takpilot_colors.xml`. Do not add a new
  `Color.parseColor` call site. `res/values/colors.xml` belongs to the stock DJI sample —
  leave it alone.
- **This application runs on a smart controller, not a phone.** The target is the DJI RC
  Plus. It follows the Autel controller treatment and not the MSDKv4 phone layouts —
  specification §7 and conformance finding V11. The tree is still in the phone's default dp
  bucket today; that is the open defect, not the rule. Take the reasoning from the Autel
  tree, never its numbers: no dp value transfers between trees, and the RC Plus dp size must
  be read from the device before any dimension work.
- Release notes are short and simple, one line per function, next to the APK.
- Do not commit without asking first.

## Verification

- The build: `./gradlew :app:assembleRelease`. Gradle 8.12, AGP 8.7.0. `versionName` is
  `1.0.0-dev1`, versionCode 4 — RELEASED 2026-08-18 as the first signed build of this
  tree (tag `v1.0.0-dev1`). Signing comes from `app/keystore.properties`, the same AnchorTAK
  key the MSDKv4 sibling uses; it is gitignored and must stay that way.
- Nothing in this tree is verified on hardware yet. **`versionName` stays `-dev` until
  something flies.** Move versionCode on every build that goes on a device; move the name
  when the aircraft has been in the air.
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

The open defect is unchanged and is **V11**: this tree is still in the phone's default dp
bucket and runs on an RC Plus. It is four numbers in a `values-w820dp` bucket that does not
exist yet, and it is blocked on nobody having measured the device. Do not guess them.
