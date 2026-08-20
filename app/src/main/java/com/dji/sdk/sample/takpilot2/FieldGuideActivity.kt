package com.dji.sdk.sample.takpilot2

import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.R
import com.taklite.util.AppLog

/**
 * Pilot field guide — a read-in-the-field explanation of what every control does.
 *
 * **Audience is the pilot, not a developer.** No class names, no file paths, no SDK talk. Where
 * a limitation matters to a flight decision it IS stated plainly (what the FAA layer can't tell
 * you, when the altitude readout is approximate, what a local marker delete does and doesn't
 * do), because a guide that only lists happy paths is the kind that gets someone in trouble.
 *
 * **Every pilot-facing string in this file is written in ASD-STE100 Simplified Technical
 * English. Keep it that way when you edit.** STE is the aerospace controlled-language standard
 * (approved dictionary + 53 writing rules); it exists so that a reader who is tired, rushed, or
 * reading in a second language cannot mis-parse a safety-relevant instruction. The rules this
 * text is held to:
 *  - **One word, one meaning, one part of speech.** A short screen press is always "touch",
 *    never tap/press/hit. A long press is always "touch and hold". The airframe is always the
 *    "aircraft", never the drone. A marker is always a "marker", never a pin.
 *  - **Approved vocabulary.** "make sure" not ensure, "about" not approximately, "use" not
 *    utilize, "let" not allow, "get" not obtain, "but" not however, "because of" not due to.
 *  - **Active voice, simple tenses, no -ing forms.** "The app sends the marker", not "the
 *    marker is sent" or "sending the marker".
 *  - **Sentence length**: 20 words max for an instruction, 25 for description. Six sentences
 *    max per paragraph, one topic each.
 *  - **Conditions come first**: "If the signal is weak, select Low" — never the reverse.
 *  - **No idiom, metaphor, or humour.** They are the first thing to fail a tired reader and the
 *    first thing to fail a translator.
 *  - **Warnings open with the command**, then the reason.
 *
 * The one deliberate exception: **on-screen control labels are quoted verbatim** even when they
 * are not STE ("Drop Marker at Crosshair", "Enroll & Connect"). STE treats these as technical
 * names, and a guide that renames the button a pilot is hunting for is worse than useless.
 *
 * **The icon examples are live views, not pictures.** Each one is the real toolbar widget —
 * [BatteryGaugeView], [SignalBarsView], [LiveToggleView], [RecordToggleView], the TAK badge with
 * its status dot — constructed here and driven into the state being described. Screenshots or
 * hand-drawn copies would silently go stale the next time an icon changes; these can't, because
 * they ARE the icons. It also means a state shown here is genuinely reachable in the app.
 *
 * ## The 2026-08-18 cut, following the Autel sibling
 *
 * Two passes, applying the rule the operator set on the sibling (2026-08-15): **keep every
 * fact that changes a flight decision, delete the explanation of why.** A pilot needs "land
 * when the ring is yellow", not a paragraph on how the gauge is computed. A guide long enough
 * that a pilot does not read it is worse than a short one that omits something.
 *
 * The printable handout went 4478 words -> 3033, and the two long sections carried the cut:
 * Pre-Flight 1052 -> 691 and the flight screen 2168 -> 1697.
 *
 * ## The 2026-08-20 cut, toward the Autel sibling
 *
 * **3092 -> 2578 words**, in two passes (Autel is 2196). The operator's judgement: it was
 * long enough that
 * nobody would read it, which makes it worse than a shorter guide that omits something.
 *
 * The method was the sibling's: say what the control is and what to do, and delete the
 * explanation of the consequence. Autel's own entries were the model — its "1. Aircraft
 * Settings" is 19 words where this file's was 277.
 *
 * ## ⚠ WHAT MAKES A GUIDE LOOK LONG IS BOXES, NOT WORDS
 *
 * The first pass cut words and the operator rejected it: the Autel guide was still "vastly
 * shorter" at only 15% fewer words. The word count was the wrong measurement.
 *
 * **Autel's whole guide has TWO callout boxes. This one had NINETEEN.** Every `warn` and
 * `note`, and every string in an `entry`'s `caveats`, renders as a boxed blockquote. Nineteen
 * boxes read as a wall of alarms and make a guide look twice its length, whatever it weighs.
 *
 * So the second pass changed the FORM and not the content: sixteen boxes became ordinary
 * prose. **No sentence was deleted to do it.** Every safety fact still reads, in the same
 * words, in the paragraph where it belongs.
 *
 * Three boxes are left. Two are the two Autel keeps — the calibration note and "the AR view
 * is not accurate for a point". The third is "nothing in this build has flown", which is
 * temporary and goes when the aircraft flies.
 *
 * **Keep it that way.** A new `warn` or `note` here is a fourth box, and the reason to reach
 * for one is almost always that the sentence matters — which is true of most sentences in
 * this file. Write it as prose. A box that is used nineteen times has stopped being a box.
 *
 * A second pass on the same day took it further and removed ONE section, "What this build
 * cannot do", on the operator's decision. Its "nothing has flown" warning did NOT go with it —
 * that warning is at the top of the guide now, and the camera-angle limit it carried rides
 * with the crosshair entry, where a pilot meets the same fact in context.
 *
 * ⚠ **THE REST OF THE GAP TO AUTEL IS CONTENT, NOT PROSE.** Autel is shorter partly by economy
 * and partly because it documents controls it has and does not describe — it covers neither
 * the warnings banner nor the resource row (conformance A17). Closing the gap further means
 * deleting documentation for controls THIS app has: the warnings banner, the obstacle
 * distances, the aircraft-data block, the RTH/HOME lines, the resource bar, the exposure
 * slider, the clock, the map display and the video re-sync. That is an operator decision, not
 * an editing pass.
 *
 * What survived BOTH cuts and must not be trimmed again, because each one changes what a pilot
 * does: the battery-refusal warning, the crosshair angle and error table, the marker refusal
 * conditions, what a marker delete does NOT do, the certificate rule on channels, the FAA
 * "not an approval" warning, the obstacle "no mark is not clear" warning, and the
 * "nothing in this build has flown" warning.
 *
 * ## One section the Autel sibling has and this build does not
 *
 * Absent because the FUNCTION is absent, not because the guide is behind:
 *  - **The controller buttons.** Nothing on the RC Plus is wired in this app — deferred by
 *    the operator 2026-08-20 (C1/C2 preferred, a future decision). Write that section when
 *    there is a listener.
 *
 * The aim calibration EXISTS since 2026-08-20 (V32) and is covered on the AR entry. Autel
 * gives it a whole guide section; this guide keeps it to the entry, because the 2026-08-20
 * cut fought for every word — expand it only if a pilot actually gets lost.
 *
 * The in-flight channel picker (touch and hold on the TAK badge) is documented in BOTH places
 * it can be reached from — section 2 with the rest of the channel rules, and on the TAK
 * connection entry in section 3, where a pilot looking at the badge will find it.
 *
 * ## NO AIRFRAME IS NAMED IN THIS FILE, and it must stay that way
 *
 * The MSDKv4 sibling names the Mini 2 throughout, because that is the aircraft it flies.
 * **This SDK does not support the Mini 2 at all**, so every one of those sentences would be
 * false here — and two of them were dangerous rather than merely stale, which is why the
 * 2026-08-14 pass (conformance V15) removed them. When this text is next taken from the
 * sibling, take the facts and drop the airframe. The same rule applies to "phone": this build
 * runs on a smart controller.
 */
class FieldGuideActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_guide)
        AppLog.v(TAG, "field guide opened")
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        content = findViewById(R.id.fieldGuideContent)

        title("TAKPilot2 Field Guide")
        lede("What each control does, on the screen and in Pre-Flight Setup. Read it before " +
            "you fly. This is the build for the smart controller.")
        // ⚠ THIS WARNING WAS SECTION 5. That section was removed on 2026-08-20 to shorten the
        // guide; the warning moved here instead of going with it, because it is on the
        // must-not-trim list and it is still true. DELETE IT WHEN THE AIRCRAFT HAS FLOWN, at
        // the same time as versionName loses its -dev suffix — not before, and not separately.
        warn("NOTHING IN THIS BUILD HAS FLOWN. The app was tested on the ground only. " +
            "Examine each control on your first flight, and keep the aircraft in sight.")

        sectionOne()
        sectionTwo()
        sectionThree()
        sectionFour()

        divider()
        body("If this guide does not agree with the aircraft, obey the aircraft. Then tell " +
            "the person who maintains the app.")
        spacer(24)
    }

    // ---------------------------------------------------------------- Section 1

    private fun sectionOne() {
        section("1. What this app is for")
        body("TAKPilot2 flies your DJI aircraft and puts it on the shared TAK map of your " +
            "team.")

        bullet("Your team sees the position, heading and altitude of the aircraft, and the " +
            "point on the ground where the camera looks.")
        bullet("You put markers on what you see. Your team gets them in a few seconds.")
        bullet("The quick marker is one marker. Each new touch moves it.")
        bullet("The app can send live video to a server of your team.")

        note("Do the firmware updates, the calibrations and the aircraft registration in the " +
            "DJI app first. Not here.")

        body("A red TAK icon means your team cannot see the aircraft or your markers. The " +
            "aircraft still flies correctly.")
    }

    // ---------------------------------------------------------------- Section 2

    private fun sectionTwo() {
        section("2. Pre-Flight Setup")
        body("Set these on the ground prior to flight. Change them for a new area, a new " +
            "server or a new task.")

        sub("1. Aircraft Settings")
        body("Max altitude, max distance and RTH altitude, in feet. The app sends them at each " +
            "connection. An empty field keeps what the aircraft holds. At the max distance the " +
            "aircraft stops and holds. It does not come home by itself.")
        body("Set the RTH altitude below the Max altitude, and above the highest obstacle " +
            "between you and the aircraft.")

        body("Control response sets the speed of the camera controls. Stick mode sets what the " +
            "sticks do; Mode 2 is usual, and it is sent only on Apply.")

        body("If the signal is lost sets what the aircraft does alone. Select Return Home. It " +
            "applies also if the app stops in flight.")

        body("Obstacle avoidance has three boxes. Read the line below them: it shows what the " +
            "aircraft reports, and the boxes do nothing without obstacle sensors.")

        body("Battery Warning and Battery Critical are where the aircraft warns you and lands.")
        body("Some aircraft keep their own battery levels and refuse a change. If yours " +
            "refuses, the fields become read-only and show the levels the aircraft holds. " +
            "Plan for those.")

        body("Apply sends all of these now, then shows what the aircraft holds — not what " +
            "you typed.")

        sub("2. Video Streaming")
        body("Optional. Type the address of the video server of your team, the port, the " +
            "broadcast ID and the login. Select the quality: Standard, or Low if the " +
            "connection is weak. You can change the quality in flight.")

        body("Active server selects between two servers, each with its own address, login, " +
            "quality and codec. Select the codec H.264: more clients can show it.")
        body("If your team cannot see the video and this screen shows no fault, select H.264. " +
            "A client that cannot show H.265 gives no error that you can see here.")
        body("These settings do not start the video. Use the LIVE button in flight.")

        sub("3. TAK Server Connection")
        body("Type the address of the TAK server, the two ports, your username, your password " +
            "and the callsign of the aircraft. Then touch Enroll & Connect.")

        body("My Channels shows the channels of the server, which holds them, not the app. A " +
            "change applies immediately. \"Rx Only\" gives you data but takes none. You can " +
            "also change channels in flight: touch and hold the TAK icon.")
        body("The channels belong to your certificate, not to this controller. If two " +
            "controllers sign in as the same user, a change on one changes the other.")

        sub("4. Elevation Data (DTED)")
        body("The terrain data for your area, one file for each region. It makes the markers " +
            "more accurate and the altitude true above the ground. Without it, the altitude " +
            "is the height above your takeoff point.")

        sub("5. FAA Airspace Ceilings (UASFM)")
        body("This downloads the FAA ceiling data for an area, and the flight screen then " +
            "shows the ceiling at the aircraft. Download it on wifi before you go.")
        body("Do not use this data as an approval to fly. It can be out of date. You must get " +
            "your own airspace approval.")

        sub("6. Map Display")
        body("The type of the small map on the flight screen: Street, Hybrid (satellite) or a " +
            "custom map of your team. Touch Save Map Display. It changes your map only, not " +
            "the map of your team.")
    }

    // ---------------------------------------------------------------- Section 3

    private fun sectionThree() {
        section("3. The Flight Screen")
        body("The live camera image fills the screen. The toolbar is across the top, with the " +
            "status icons on the left and the buttons on the right. The readout is down the " +
            "right side and the small map is in the bottom right corner.")

        sub("Toolbar: left side (status)")

        entry(
            listOf(icon(R.drawable.ic_menu) to "Menu"),
            "Menu",
            "Closes the flight screen and shows the home screen. The aircraft continues to " +
                "fly and stays connected to TAK.",
        )

        entry(
            listOf(
                takBadge(connected = true) to "Connected",
                takBadge(connected = false) to "Not connected",
            ),
            "TAK connection",
            "Green: your aircraft is on the TAK map of your team. Red: it is not. You can " +
                "fly, but your team cannot see the aircraft.\n\n" +
                "Touch: connect or disconnect. Touch and hold: the TAK Channels. If they are " +
                "locked, touch Unlock and give the password.",
        )

        entry(
            listOf(
                battery(85) to "85%",
                battery(24) to "24%",
                battery(9) to "9%",
            ),
            "Battery",
            "The charge in the battery of the aircraft. Green shows more than 30%. Yellow " +
                "shows 16% to 30%, and red shows 15% or less. Land the aircraft when the ring " +
                "is yellow. Do not wait for red.",
        )

        entry(
            listOf(
                signal(90) to "Strong",
                signal(60) to "Medium",
                signal(20) to "Weak",
            ),
            "Controller signal",
            "The strength of the signal between the controller and the aircraft. One yellow " +
                "bar shows a weak signal, and red with no bars shows that you can lose it. If " +
                "the bars decrease, fly the aircraft nearer or lift the controller.",
        )

        entry(
            listOf(
                gps(hasFix = true) to "Position",
                gps(hasFix = false) to "No position",
            ),
            "GPS satellites",
            "The quantity of satellites that the aircraft receives. Green shows that the " +
                "aircraft has its position. Wait for green before you take off: without a " +
                "position the aircraft cannot hold station, set a home point, or come home.",
        )

        entry(
            listOf(
                image(R.drawable.ic_rth_home_set) to "Home set",
                image(R.drawable.ic_rth) to "No home",
            ),
            "Return to Home",
            "Touch: sends the aircraft home. The app asks you to confirm. Touch the button " +
                "again during the return to stop it and get control.\n\n" +
                "Touch and hold: moves the home point to your position. Use this if you moved " +
                "away from the takeoff point. The app asks you to confirm.\n\n" +
                "The house is green when the home point is set.",
        )

        sub("Toolbar: right side (buttons)")

        entry(
            listOf(image(R.drawable.ic_drop_pin) to "Marker"),
            "Put a marker",
            "Touch: puts a marker at the centre of the image. Point the camera at the target " +
                "first, then select the type and type a name.\n\n" +
                "Touch and hold: the \"Markers\" list. Change your own marker, send it again " +
                "or remove it. Touch a marker of your team to send it again or to remove it " +
                "from your map only.\n\n" +
                "DELETE REMOVES A MARKER FROM THIS AIRCRAFT ONLY. It stays on the screens of " +
                "your team for about 3 days.\n\n" +
                "The app does not put a marker if the crosshair ring is red, if the aircraft " +
                "has no GPS position, or if it is less than 25 ft above the ground. Point the " +
                "camera down more, or climb higher.",
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR: markers on the video",
            "Draws the markers on the live image near their positions. It is green when on, " +
                "and on when you open the flight screen. A marker outside the image shows as " +
                "an arrow at the edge: turn the camera that way.\n\n" +
                "Touch and hold: select what the app draws, set the air-traffic range, and " +
                "open Aim Offsets. Air traffic positions can be about ten seconds old.\n\n" +
                "Aim Offsets moves every marker and the camera point together. If all the " +
                "markers are wrong in the same direction, calibrate there: aim at a known " +
                "object with the camera 25 degrees down, then adjust until the point sits on " +
                "the object.",
            listOf(
                "THE AR VIEW IS NOT ACCURATE FOR A POINT. It shows the general area of a " +
                    "marker. Do not use it to choose between objects that are close together, " +
                    "such as one house in a tight row of houses.",
            ),
        )

        entry(
            listOf(image(R.drawable.ic_camera_shutter) to "Photo"),
            "Photo",
            "Takes a photo. The app saves it to the card in the aircraft, not to the controller.",
        )

        entry(
            listOf(zoomPill("1X") to "Normal", zoomPill("2X") to "2X view"),
            "Zoom",
            "Each touch magnifies one step: 1X, 3X, 7X, 14X, 28X. A touch at 28X goes back to " +
                "1X. Your team sees the same view in the video.\n\n" +
                "1X is the wide camera. Each larger step uses the zoom camera, thus the view " +
                "changes camera when you go from 1X to 3X.\n\n" +
                "The markers are less accurate when you magnify. Put your markers at 1X.",
        )

        entry(
            listOf(image(R.drawable.ic_resync) to "Re-sync"),
            "Video re-sync",
            "Corrects the video image. If blocks or marks occur in the image, touch this " +
                "button and the image becomes correct in a few seconds. It changes your image " +
                "only.",
        )

        entry(
            listOf(
                live(LiveToggleView.State.OFF) to "Off",
                live(LiveToggleView.State.LIVE) to "Video on",
                live(LiveToggleView.State.RECONNECTING) to "Connects again",
            ),
            "LIVE: video to your team",
            "Starts and stops the live video to the video server of your team. Set the server " +
                "in Pre-Flight Setup first.\n\n" +
                "Touch and hold: the video quality. If the stream is on, the new quality " +
                "applies immediately.\n\n" +
                "A yellow button that flashes shows the connection stopped and the app is " +
                "trying again. Do not touch it: that stops the app trying.",
        )

        entry(
            listOf(
                rec(recording = false) to "Off",
                rec(recording = true) to "Records",
            ),
            "REC: record to the aircraft",
            "Records video to the card in the aircraft, independently of the live video. The " +
                "card keeps the full quality; the live video to your team is lower.",
        )

        sub("On the video image")

        entry(
            emptyList(),
            "The crosshair",
            "The centre of the image, and where a marker goes. The ring colour shows the " +
                "accuracy at the current camera angle.\n\n" +
                "WITH terrain data:\n" +
                "GREEN: 25° down or more. The error is about 10 ft.\n" +
                "YELLOW: 10° to 25° down. The error is about 50 ft.\n\n" +
                "WITHOUT terrain data:\n" +
                "GREEN: 30° down or more. The error is about 50 ft.\n" +
                "YELLOW: 15° to 30° down. The error is about 100 ft.\n\n" +
                "RED: less than the yellow angle. The app does not put a marker. Point the " +
                "camera down more, or fly nearer.\n\n" +
                "These values need a good GPS position. Out of your terrain data, the ring " +
                "changes to the no-terrain angles. A marker near the edge of the picture is " +
                "less accurate than one in the centre.",
        )

        entry(
            emptyList(),
            "Quick marker: touch the crosshair",
            "Touch the crosshair to put a marker immediately, with no questions. The marker " +
                "keeps one short name — one letter and three digits — that belongs to this " +
                "controller and does not change.\n\n" +
                "THERE IS ONLY ONE QUICK MARKER. Point the camera at a new location and touch " +
                "the crosshair again: the marker MOVES there, on the screens of all your " +
                "team. Use it to show your team what you look at now.",
        )

        entry(
            emptyList(),
            "Static marker: touch and hold the crosshair",
            "Touch the crosshair and hold it to put a static marker of the type Unknown. THIS " +
                "MARKER DOES NOT MOVE. A second touch and hold puts a SECOND marker. The name " +
                "is the callsign of the aircraft and a number, for example MINI2-P7.\n\n" +
                "Use it to keep a record of a position.",
        )

        entry(
            emptyList(),
            "Obstacle distances",
            "A mark at the nearest edge of the video, with the distance in feet. A curved " +
                "line is an obstacle at that side; FWD is in front, REAR is behind. It shows " +
                "at about 39 ft in yellow, then red at 13 ft or less.\n\n" +
                "AN EDGE WITH NO MARK DOES NOT MEAN THE DIRECTION IS CLEAR. It can also mean " +
                "the aircraft has no sensor there. With no obstacle sensors this display " +
                "always stays empty.",
        )

        entry(
            emptyList(),
            "Warnings (top left)",
            "A box below the toolbar shows a warning. RED means act now. AMBER means know it. " +
                "IF THE MOTORS DO NOT START, READ THIS BOX FIRST.\n\n" +
                "Most come from the aircraft, in its own words. The app adds its own for the " +
                "return home, the battery, the limits, a missing home point and high wind. " +
                "The box shows the most important one, with a count if there are more.\n\n" +
                "Touch the box to read all of them, and again to close it.",
        )

        sub("The readout: right side")

        entry(
            emptyList(),
            "Exposure slider (top right)",
            "Makes the image brighter or darker. Use it when the automatic exposure is not " +
                "correct, for example a dark object against snow.",
        )

        entry(
            emptyList(),
            "Clock",
            "Below the slider, the time of the controller. Use it to give a time to your team.",
        )

        entry(
            emptyList(),
            "Aircraft data",
            "Three lines: the callsign and speed, the height above the ground and above sea " +
                "level, then the latitude and longitude. Below them GIMBAL shows the camera " +
                "angle, in the colour of the crosshair ring.\n\n" +
                "AGL is the true height above the ground and needs terrain data. Without it " +
                "ALT shows instead: the height above your takeoff point. MSL is above sea " +
                "level, which airspace limits use.",
        )

        entry(
            emptyList(),
            "RTH and HOME lines",
            "RTH is the return height the AIRCRAFT holds now. Two dashes mean the app does " +
                "not know it, not that it is zero. HOME is the distance and direction to the " +
                "home point, in degrees true.\n\n" +
                "RTH shows the AIRCRAFT, not what you typed in Pre-Flight Setup. If the two " +
                "differ, the aircraft did not accept it. Send it again with Apply.",
        )

        entry(
            emptyList(),
            "FAA ceiling line",
            "The published ceiling at the position of the aircraft. It becomes red if you fly " +
                "above it. It shows AGL, thus compare it with the AGL line and not MSL.\n\n" +
                "Grey \"Class G\" - no facility map here; the usual 400 ft limit applies.\n" +
                "Yellow \"no data here\" - you flew out of the area you downloaded.",
        )

        entry(
            emptyList(),
            "The map",
            "North is at the top, the aircraft is in the centre, and the red line goes from " +
                "the home point to the aircraft. Touch a marker of your team to remove it from " +
                "your map only.\n\n" +
                "The button at the bottom left gives two zoom levels: WIDE is the full distance " +
                "you may fly, NEAR shows more detail. Touch the map two times to make it " +
                "large, and again to make it small.\n\n" +
                "Map data: OpenStreetMap contributors. Satellite images: Esri World Imagery.",
        )

        sub("The resource bar: bottom left")

        entry(
            emptyList(),
            "System resources",
            "Off unless you switch it on in Debug Log. It shows the memory of the controller " +
                "(SYS) and of the app (APP), the load (CPU) and the quantity of TAK contacts " +
                "(TAK).\n\n" +
                "Watch TAK: it must go up and down with the persons and aircraft near you. If " +
                "it only goes up across a flight, report it.",
        )
    }

    // ---------------------------------------------------------------- Section 4

    private fun sectionFour() {
        section("4. Flight path records")
        body("The app records each flight automatically, with no switch and no TAK server. " +
            "It starts when the aircraft leaves the ground and stops when it is down for 10 " +
            "seconds, thus a short touch does not divide the record.")

        body("Open Downloads/TAKPilotFlights on the controller. Each flight makes two files:")
        bullet(".gpx - the track. Import it into ATAK or Google Earth.")
        bullet(".csv - one row each second: time, position, altitude, speed, heading, " +
            "battery and satellites. Open it in a spreadsheet.")

        body("Without a GPS position the app records nothing for that time. It does not " +
            "write a false position.")
        body("The folder keeps about 50 MB, then deletes the oldest files. Copy a record " +
            "away to keep it.")
    }

    // ---------------------------------------------------------------- Section 5

    // Section 5, "What this build cannot do", was removed on 2026-08-20 (operator). Its
    // "nothing has flown" warning moved to the top of the guide; the camera-angle limit now
    // rides with the crosshair entry, where a pilot meets the same fact in context. The aim
    // correction, the thermal camera and the controller buttons are absent FUNCTIONS, and a
    // guide that lists what a build does not have grows without limit.

    /** Action-bar back arrow behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ------------------------------------------------------- content builders

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 24f
        setTypeface(null, android.graphics.Typeface.BOLD)
    })

    private fun lede(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)); textSize = 14f
        setPadding(0, dp(6), 0, dp(4))
    })

    private fun section(text: String) {
        divider()
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun sub(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent)); textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.03f
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun bullet(text: String) = content.addView(TextView(this).apply {
        this.text = "•  $text"
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(8), 0, 0, dp(6))
    })

    /** Neutral aside — worth knowing, not a hazard. */
    private fun note(text: String) =
        calloutView(text, R.color.tp_accent, R.color.tp_surface_guide_note)

    /** Something that can bite you in the air or on the ground. */
    private fun warn(text: String) =
        calloutView(text, R.color.tp_btn_danger_dialog, R.color.tp_surface_guide_warn)

    /**
     * Callout row: a coloured tint bar against a low-saturation background of the same hue.
     *
     * Takes colour RESOURCES, not hex strings. These four values were literals here until
     * 2026-08-14 (conformance X1) — a literal in Kotlin is easy to reach for and easy to miss
     * in review, which is why §6.1 puts this file inside the token rule.
     */
    private fun calloutView(text: String, @ColorRes barColor: Int, @ColorRes bgColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, bgColor))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
        }
        row.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(applicationContext, barColor))
            layoutParams = LinearLayout.LayoutParams(dp(3), MATCH)
        })
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_dim)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        content.addView(row)
    }

    private fun divider() = content.addView(View(this).apply {
        setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_border))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(20); bottomMargin = dp(12)
        }
    })

    private fun spacer(heightDp: Int) = content.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    })

    /**
     * One control: its icon in each state worth recognising, its name, what it does, and any
     * caveats. [icons] may be empty for parts of the screen that aren't a button.
     */
    private fun entry(
        icons: List<Pair<View, String>>,
        name: String,
        what: String,
        caveats: List<String> = emptyList(),
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }

        if (icons.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(10)
                }
            }
            for ((view, caption) in icons) {
                val captionView = TextView(this@FieldGuideActivity).apply {
                    text = caption
                    setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_muted)); textSize = 11f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setPadding(0, dp(5), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                }
                // Size the chip from the MEASURED caption rather than leaving it to wrap_content.
                // Letting the layout work it out doesn't survive here: the icon is a fixed-width
                // child, so the cell settles on the icon's width and a longer caption
                // ("Not connected") gets silently cut to something that reads as a different
                // state ("Not conn"). Measuring the text and flooring the chip to it is the only
                // version that can't clip.
                val captionWidth = captionView.paint.measureText(caption).toInt()
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    // Toolbar-dark chip behind each example: these icons are drawn to sit on
                    // the flight toolbar, and judging them against a lighter card would be
                    // misleading about how they actually read in the air.
                    setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide_code))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    minimumWidth = captionWidth + dp(20)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        rightMargin = dp(8)
                    }
                }
                cell.addView(view)
                cell.addView(captionView)
                strip.addView(cell)
            }
            card.addView(strip)
        }

        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        card.addView(TextView(this).apply {
            text = what
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        for (c in caveats) {
            card.addView(TextView(this).apply {
                text = "!  $c"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_warn)); textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card)
    }

    // ------------------------------------------------- live icon examples

    private fun iconParams(sizeDp: Int = 34) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))

    private fun icon(res: Int) = image(res)

    private fun image(res: Int): View = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = iconParams()
    }

    /** The TAK badge exactly as the toolbar builds it, dot tinted to the state described. */
    private fun takBadge(connected: Boolean): View {
        val frame = android.widget.FrameLayout(this).apply { layoutParams = iconParams() }
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tak_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(34), dp(34))
        })
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.bg_status_dot)
            setColorFilter(if (connected) CONNECTED_GREEN else DISCONNECTED_RED)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        })
        return frame
    }

    private fun battery(pct: Int): View =
        BatteryGaugeView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun signal(pct: Int): View =
        SignalBarsView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun gps(hasFix: Boolean): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_gps)
        setColorFilter(if (hasFix) CONNECTED_GREEN else NO_FIX_GREY)
        layoutParams = iconParams()
    }

    private fun live(state: LiveToggleView.State): View =
        LiveToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(82), dp(34))
            setState(state)
        }

    private fun rec(recording: Boolean): View =
        RecordToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(34))
            setRecording(recording)
        }

    private fun zoomPill(label: String): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_zoom_pill)
        setTextColor(Color.WHITE); textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The AR pill in either state, built from the same drawables and tints the flight screen's
     *  refreshArButton() uses, so an example here can't show a state the toolbar never renders. */
    private fun arPill(on: Boolean): View = TextView(this).apply {
        text = "AR"
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_ar_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) CONNECTED_GREEN else Color.WHITE)
        alpha = if (on) 1f else 0.45f
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TP2Guide"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // Same values the flight screen tints these with, so a state shown here is the state
        // the pilot will actually see.
        private val CONNECTED_GREEN = 0xFF4CAF50.toInt()
        private val DISCONNECTED_RED = 0xFFF44336.toInt()
        private val NO_FIX_GREY = 0xFFAAAAAA.toInt()
    }
}
