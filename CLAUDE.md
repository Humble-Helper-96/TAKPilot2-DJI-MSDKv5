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

**When you look for reference code, use the live MSDKv4 worktree at
`../../v4/Mobile-SDK-Android-4.18/SampleCode-device-compat/`.** The port plan points at
`Sample Code/`, which is the stale parent checkout of the same repository.

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
  `1.0.0-dev`, versionCode 1.
- Nothing in this tree is verified on hardware yet.
