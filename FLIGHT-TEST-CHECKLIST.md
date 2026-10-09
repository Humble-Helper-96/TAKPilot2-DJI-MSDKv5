# TAKPilot2 — DJI MSDKv5 — release test checklist

**Written in Simplified Technical English (ASD-STE100).**

For the `uasvideo-split` branch, built on 2026-10-09. Eight commits above `main`, plus the
AR pose filter. `versionName` is **2.0.1** and has NOT been moved — moving it is a claim about
status and needs the operator, every time.

⚠ **THE ORDER MATTERS.** Section 1 is the headline feature and it has NEVER been proved
end to end. If section 1 fails, nothing after it is worth running.

Mark each line PASS / FAIL / SKIPPED, with the date and the versionCode.

---

## 1. The video split — the one that decides the release

⚠ **THIS IS THE WHOLE POINT OF THE BRANCH AND IT IS UNPROVED.** Everything observed so far
says the app sends two connections and strips the url from one of them. Nobody has yet
confirmed what the two AUDIENCES actually see. Until these two lines pass, the feature is
"it compiles and it transmits", not "it works".

Needs: a TAK client signed in as a **basic** user (channel A, no video) and one as a
**video-allowed** user (channel B). They may be the same person twice, one after the other.

| # | Test | Expected |
|---|---|---|
| 1.1 | Stream LIVE, no Emergency Broadcast. Look at the aircraft marker as the **basic** user. | The aircraft is there, position and FOV normal, and **NO playable video link on it.** |
| 1.2 | Same moment, as the **video-allowed** user. | The aircraft carries a link that **plays**. |
| 1.3 | Both users: look at the PILOT marker. | A plain team dot, **never** a 2525 square — no video on the pilot marker. |

## 2. Emergency Broadcast

| # | Test | Expected |
|---|---|---|
| 2.1 | LIVE already running. Long-press LIVE, Start Emergency Broadcast. | The **basic** user's aircraft gains a playable link within one report. Purple notice, counting down. |
| 2.2 | Tap the notice. | Timer back to 15:00. `renewed` in the events log. |
| 2.3 | Touch and hold the notice. | Notice clears. The basic user's link goes away again. `cancelled` in the events log. |
| 2.4 | Start a broadcast with **LIVE off** and a video server configured. | The stream starts with it. |
| 2.5 | Force-stop the app first, then 2.4, and **DENY** the screen-capture dialog. | The broadcast does NOT stay running. Amber notice: "Screen capture refused — Emergency Broadcast stopped". **Never tested — the consent was already remembered on the bench.** |
| 2.6 | With a broadcast running, tap LIVE to stop the stream. | The notice turns amber: `EMERGENCY BROADCAST — NO VIDEO STREAM — mm:ss`. |
| 2.7 | Let one run to 0:00. | `expired` in the events log, notice clears. **Never seen.** |
| 2.8 | Read `Downloads/TAKPilotFlights/flight-<ts>-events.log` after a flight. | start / renew / cancelled / expired lines, ISO-UTC, no "who". Three of the five types are confirmed; `cancelled` and `expired` are not. |

## 3. The Elevated account

| # | Test | Expected |
|---|---|---|
| 3.1 | Pre-Flight → Configure TAK Server. Switch the Elevated account OFF. | Every client gets video again. |
| 3.2 | Switch it back ON. | The split returns with no re-enrollment. |
| 3.3 | Untick an Elevated channel. ⚠ **FLEET-WIDE — every controller on that account loses it.** | The server takes it; the line says so. Tick it back. |
| 3.4 | Disable the Elevated user on the server, with LIVE running. | Amber "Video link down — feed not advertised" on the flight screen. |
| 3.5 | Put a test user in BOTH accounts' channels. | Red overlap warning on the TAK Server screen. |
| 3.6 | Log Out, then log back in. | ⚠ The Elevated account must be **enrolled again** — this is by design and is the one thing a pilot can be caught by. |

## 4. The screens

| # | Test | Expected |
|---|---|---|
| 4.1 | Home screen. | Four rows: APP PERMISSIONS, WIFI, TAK, MEDIA SERVER. Media server green against a live server, red with it stopped. |
| 4.2 | Pre-Flight, TAK section. | Three generated lines + Configure TAK Server. No stale text after changing anything on the server screen. |
| 4.3 | Pre-Flight, avoidance. | Reads the aircraft and says "Set these in DJI Pilot 2". No check boxes. Never "not read yet" for ever. |
| 4.4 | Flight screen, touch and hold the TAK badge. | Both channel lists fit with the dialog not full-height. |
| 4.5 | A real aircraft fault. | The banner shows the fault NAME only — no paragraph of advice. **Not yet seen with a real fault.** |

## 5. Connection and recovery

| # | Test | Expected |
|---|---|---|
| 5.1 | ⚠ **Swipe the app away WITHOUT force-stopping** (the foreground service keeps the process), then reopen. | TAK reconnects on its own. This is the regression that was fixed; the warm-process case is the one that bit, and it has NOT been reproduced since the fix. |
| 5.2 | Cold start with a saved enrollment. | TAK and the Elevated account both connect. Confirmed once. |
| 5.3 | Flight-screen TAK icon: tap off, tap on. | Disconnects and reconnects, both accounts. |

## 6. In flight

| # | Test | Expected |
|---|---|---|
| 6.1 | Full flight with LIVE on. | Aircraft, SPI, FOV and markers on both audiences per section 1. |
| 6.2 | AR overlay with contacts in range. | ⚠ **The pose filter is UNVERIFIED.** Watch for marker jitter at rest and for the overlay LAGGING a deliberate pan. A lag on pan means the snap threshold is wrong. |
| 6.3 | Land. | ⚠ Stick-down is always manual and needs CSC — release CSC the instant the motors stop. RTH is the only commanded landing this app can give. |
| 6.4 | After landing. | `-events.log`, CSV and GPX all present in `Downloads/TAKPilotFlights`. |

---

## Known gaps, carried into the release knowingly

- **Phase 0 on a TEST server was never run** (`CHANNELS-FINDINGS.md` §12). Section 1 above is
  the field substitute for it, on the live server.
- **The SPI runs long.** Bearing is right; range is stretched by the DTED elevation at the
  target. Not a regression and not fixed here.
- **The aircraft marker publishes ~230 ft low** — `hae` comes from DJI's takeoff altitude,
  which reads 428 ft where the ground is 613 ft. Found this session, NOT fixed.
- **Specification §4.8 and `TAKPILOT2-UI-CONFORMANCE.md` are out of date** for this tree, and
  the Autel sibling owes several of this session's changes. Recorded in `CLAUDE.md`.
