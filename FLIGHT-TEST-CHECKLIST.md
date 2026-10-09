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

| # | Test | Expected | Result |
|---|---|---|---|
| 1.1 | Stream LIVE, no Emergency Broadcast. Look at the aircraft marker as the **basic** user. | The aircraft is there, position and FOV normal, and **NO playable video link on it.** | **PASS** 10-09 vc154 — wire + client |
| 1.2 | Same moment, as the **video-allowed** user. | The aircraft carries a link that **plays**. | **PASS** 10-09 vc154 — wire + client |
| 1.3 | Both users: look at the PILOT marker. | A plain team dot, **never** a 2525 square — no video on the pilot marker. | **PASS** 10-09 vc154 — wire |

## 2. Emergency Broadcast

| # | Test | Expected | Result |
|---|---|---|---|
| 2.1 | LIVE already running. Long-press LIVE, Start Emergency Broadcast. | The **basic** user's aircraft gains a playable link within one report. Purple notice, counting down. | **PASS** 10-09 vc154 |
| 2.2 | Tap the notice. | Timer back to 15:00. `renewed` in the events log. | **PASS** 10-09 vc154 |
| 2.3 | Touch and hold the notice. | Notice clears. The basic user's link goes away again. `cancelled` in the events log. | **PASS** 10-09 vc154 — wire |
| 2.4 | Start a broadcast with **LIVE off** and a video server configured. | The stream starts with it. |**PARTIAL** 10-09 vc154 — see note |
| 2.5 | Force-stop the app first, then 2.4, and **DENY** the screen-capture dialog. | The broadcast does NOT stay running. Amber notice: "Screen capture refused — Emergency Broadcast stopped". **Never tested — the consent was already remembered on the bench.** |**PASS** 10-09 vc154 — first time |
| 2.6 | With a broadcast running, tap LIVE to stop the stream. | The notice turns amber: `EMERGENCY BROADCAST — NO VIDEO STREAM — mm:ss`. |**PARTIAL** 10-09 vc154 — state yes, transition no |
| 2.7 | Let one run to 0:00. | `expired` in the events log, notice clears. **Never seen.** |  |
| 2.8 | Read `Downloads/TAKPilotFlights/flight-<ts>-events.log` after a flight. | start / renew / cancelled / expired lines, ISO-UTC, no "who". Three of the five types are confirmed; `cancelled` and `expired` are not. | **PARTIAL** — see note |

## 3. The Elevated account

| # | Test | Expected | Result |
|---|---|---|---|
| 3.1 | Pre-Flight → Configure TAK Server. Switch the Elevated account OFF. | Every client gets video again. |  |
| 3.2 | Switch it back ON. | The split returns with no re-enrollment. |  |
| 3.3 | Untick an Elevated channel. ⚠ **FLEET-WIDE — every controller on that account loses it.** | The server takes it; the line says so. Tick it back. |  |
| 3.4 | Disable the Elevated user on the server, with LIVE running. | Amber "Video link down — feed not advertised" on the flight screen. |  |
| 3.5 | Put a test user in BOTH accounts' channels. | Red overlap warning on the TAK Server screen. |  |
| 3.6 | Log Out, then log back in. | ⚠ The Elevated account must be **enrolled again** — this is by design and is the one thing a pilot can be caught by. |  |

## 4. The screens

| # | Test | Expected | Result |
|---|---|---|---|
| 4.1 | Home screen. | Four rows: APP PERMISSIONS, WIFI, TAK, MEDIA SERVER. Media server green against a live server, red with it stopped. | **PASS** 10-09 vc154 (green half) |
| 4.2 | Pre-Flight, TAK section. | Three generated lines + Configure TAK Server. No stale text after changing anything on the server screen. |**PASS** 10-09 vc154 |
| 4.3 | Pre-Flight, avoidance. | Reads the aircraft and says "Set these in DJI Pilot 2". No check boxes. Never "not read yet" for ever. |**PASS** 10-09 vc154 |
| 4.4 | Flight screen, touch and hold the TAK badge. | Both channel lists fit with the dialog not full-height. |**PASS** 10-09 vc154 |
| 4.5 | A real aircraft fault. | The banner shows the fault NAME only — no paragraph of advice. **Not yet seen with a real fault.** |  |

## 5. Connection and recovery

| # | Test | Expected | Result |
|---|---|---|---|
| 5.1 | ⚠ **Swipe the app away WITHOUT force-stopping** (the foreground service keeps the process), then reopen. | TAK reconnects on its own. This is the regression that was fixed; the warm-process case is the one that bit, and it has NOT been reproduced since the fix. |**PASS** 10-09 vc154 — warm process |
| 5.2 | Cold start with a saved enrollment. | TAK and the Elevated account both connect. Confirmed once. |**PASS** 10-09 vc154 |
| 5.3 | Flight-screen TAK icon: tap off, tap on. | Disconnects and reconnects, both accounts. |  |

## 6. In flight

| # | Test | Expected | Result |
|---|---|---|---|
| 6.1 | Full flight with LIVE on. | Aircraft, SPI, FOV and markers on both audiences per section 1. |  |
| 6.2 | AR overlay with contacts in range. | ⚠ **The pose filter is UNVERIFIED.** Watch for marker jitter at rest and for the overlay LAGGING a deliberate pan. A lag on pan means the snap threshold is wrong. |  |
| 6.3 | Land. | ⚠ Stick-down is always manual and needs CSC — release CSC the instant the motors stop. RTH is the only commanded landing this app can give. |  |
| 6.4 | After landing. | `-events.log`, CSV and GPX all present in `Downloads/TAKPilotFlights`. |  |

---

## 7. The SRT advertisement — new in vc155-160, NEVER PLAYED

⚠ **UNPROVEN END TO END.** The url formula and the `ConnectionEntry.path` rule come from
`UAS_Apps/srt-cot-video-advertising.md` and are pinned by 20 unit tests, but no real ATAK has
opened a feed from THIS build. Until 7.2 passes, this is "the right XML goes out", not "it
plays". **Leave the read leg on RTSP for any mixed team** — TAK Aware cannot play SRT at all.

Needs: Pre-Flight → Configure Video Servers, and an ATAK client.

| # | Test | Expected | Result |
|---|---|---|---|
| 7.1 | Set TAK Advertisement Protocol to SRT. Read the TAK Advertisement Address. | `srt://<host>:<port>?streamid=read:<path>:<user>:<pass>&passphrase=…`, the passphrase shown as `***`. |  |
| 7.2 | Stream LIVE and tap the aircraft's video in **ATAK**. | It PLAYS. A failure here with a correct address means `ConnectionEntry.path` — check it carries the whole `?streamid=…` string. |  |
| 7.3 | Same moment, in **TAK Aware**. | Expected to FAIL — no SRT in its bundled player. Confirms the mixed-team warning, not a regression. |  |
| 7.4 | Switch back to RTSP with LIVE running. | The link plays again on both clients within one report. |  |
| 7.5 | SRT read with the path's auth and passphrase both EMPTY. | `…?streamid=read:<path>` and nothing more — no trailing colons, no dangling `&passphrase=`. |  |
| 7.6 | Set the publish leg to SRT and the read leg to RTSP. | Both work together: the push is `srt://…publish:…`, the CoT is `rtsp://…`. The two legs are independent. |  |

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

---

## Bench session 2026-10-09, vc154 — what was proved and how

Run on the bench RC Plus 2 with an M4TD linked and powered, MSDK 5.18, against
the live server. The controller clock was in sync with the host, so nothing here is the stale
-CoT trap. Evidence is `adb logcat`, read off the wire; the operator confirmed the client end
of section 1 independently.

**Unit tests: 163 of 163 pass**, `:app:testDebugUnitTest`, including `VideoSplitPolicyTest`
(9) and `OutboundLogShorteningTest` (8). That pins the POLICY — `TakManager.videoFor` — and
pins that A's and B's XML differ by `__video` and nothing else. It says nothing about which
socket each wire reaches; that needed the bench.

**Section 1 — the split, PROVED ON THE WIRE.** `A = [standard]`, `B = [elevated]` (the
`videoClient`), confirmed in `sendCotToBoth`. With no Emergency Broadcast running, the
aircraft marker `a-f-A-M-H-Q` goes out:

    [standard]  __video  ABSENT
    [elevated]  __video  PRESENT, rtsp://…/<aircraft>-…-Low

and the pilot marker `a-f-G-U-C` carries `__group name="Cyan" role="Team Member"` with NO
`__video` on EITHER connection — a plain team dot, never a 2525 square. The operator
confirmed the two audiences in real clients the same session.

**Section 2 — start, renew and cancel all fired.** `STARTED` 20:04:23Z → both connections
gained the url; `RENEWED` 20:05:51Z extended the expiry 20:19:23Z → 20:20:51Z; `cancelled`
20:05:54Z, after which `[standard]` lost the url within one report while `[elevated]` kept
it. **`cancelled` was one of the two event types the checklist recorded as never confirmed.**

⚠ **BUT THE EVENTS LOG WROTE NOTHING, AND CANNOT ON THE BENCH.** All three events logged

    FlightPathLogger: event with no flight in progress (not written): emergency-broadcast …

`FlightPathLogger` only writes once a flight is in progress, so **2.8 can never be satisfied
on the ground however many broadcasts are run** — it needs a real takeoff. 2.7 (`expired`)
is still unseen, and at 15 minutes a run it is cheapest to start one at the beginning of a
flight and let it expire during the flight rather than wait for it on the bench.

**4.1 — the four rows are there**, in Autel's order, all green against a live server:
APP PERMISSIONS: Granted · WIFI: <ssid> · TAK: Connected · MEDIA SERVER: Reachable. Only
the GREEN half is proved; red-with-the-server-stopped was not run, because the media server
is production and stopping it is not a bench action.

**5.1 — THE WARM-PROCESS RECONNECT, REPRODUCED AND PASSED.** This is the regression the fix
was written for and it had never been exercised since. Swiped the app away from recents
without a force-stop: **the process survived** (pid 14793 before and after — the foreground
service, exactly the case the latch would have missed). On reopen TAK dropped at 20:08:39.4,
`TakAutoConnect` logged *"TAK is down and an enrollment is saved — retrying the connection"*
at 20:08:41.1, and BOTH sockets were back by 20:08:41.9, 32 outbound messages each. The
resume-not-process reasoning in `CLAUDE.md` is now evidence, not argument.

**5.2 — cold start.** Force-stopped to a dead process, relaunched: auto-connect on launch,
both sockets up in 0.6 s. The in-flight guard also showed itself working — *"reconnect
already in flight — ignoring this request"* — which is what makes `retryIfDown` safe to call
on every resume.

**2.5 — PASSED, AND IT HAD NEVER BEEN TESTED.** The force-stop did clear the remembered
consent, so the dialog was really asked. Denying it produced the specified amber notice
WORD FOR WORD — *"Screen capture refused — Emergency Broadcast stopped"* — the countdown
notice went, and the wire confirms the override came down with it: neither connection
carried `__video` afterwards. The broadcast does not survive a refused consent.

**2.4 — PARTIAL.** Starting a broadcast with LIVE off DID try to start the stream; that is
why the consent dialog appeared at all. The positive half — accept the consent, watch the
stream come up — was not run, because the run was spent proving 2.5.

**2.6 — PARTIAL.** The amber no-stream notice was SEEN, with the specified text and a live
countdown: `EMERGENCY BROADCAST — NO VIDEO STREAM — 14:59`. But it was reached by starting a
broadcast with nothing streaming, NOT by stopping LIVE under a running broadcast. The STATE
is confirmed; the TRANSITION 2.6 actually describes is not.

**4.2, 4.3, 4.4 — PASS.** The TAK section is three generated lines and a button, with no
stale text. Avoidance is read-only, reads the aircraft live ("avoidance on, downward on,
vision positioning on, precision landing on, RTH avoidance not available, downward braking
distance 2.0 m") and says to set it in DJI Pilot 2 — no check boxes. The flight TAK Channels
dialog fits six Standard channels AND the Elevated section without running to full height.

⚠ **ONE THING WORTH KNOWING ABOUT THE URL.** `__video` is advertised only while something is
actually streaming — with LIVE off, NEITHER connection carries it. So a basic user is never
shown a dead link; the split only has work to do while a stream is up. That is the right
behaviour and it is now observed, not assumed.

**Not run, and deliberately:** 3.3 and 3.4 are marked fleet-wide — unticking an Elevated
channel pulls it from every controller on the account, and disabling the Elevated user hits
the live server. Those stay with the operator.

**Still not reached:** 2.7 (`expired` — 15 minutes, cheapest started at the top of a real
flight), all of section 3, 4.5 (needs a real aircraft fault), 5.3, all of section 6.

### Where v2.0.1 stands after this session

Of the 35 lines (six added for the SRT advertisement, vc155-160), **13 are now PASS and 3 PARTIAL.** The release GATE — section 1, the one the
checklist said decides the release — is **closed**: proved on the wire here and confirmed in
real clients by the operator the same session. The headline regression (5.1) is proved for
the first time, and 2.5, which the checklist recorded as never tested, passed verbatim.

**What still blocks a versionName move is flight, not code.** Everything outstanding needs
either air under the aircraft (2.7, 2.8, 6.1–6.4, and the AR pose filter in 6.2), a real
fault (4.5), or a fleet-wide change only the operator should make (3.3, 3.4). No bench work
remains that would change any of these answers.
