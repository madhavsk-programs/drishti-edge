# DRISHTI Monitor

An Android app for the people who run a walking programme — an NGO desk, a
field coordinator, whoever answers the phone when somebody presses SOS.

It replaces the React dashboard in `apps/dashboard/`. That one assumed a
laptop, a FastAPI service and a Wi-Fi network; this one assumes a phone in a
pocket, which is what a coordinator in the field actually has.

> **This build combines one live walking phone with sample programme data.**
> The live participant is pinned first; Call, foreground GPS and obstacle events
> are real. Every other person and all aggregate hazards remain hardcoded. The
> badge says *1 live + sample* so those two sources are never blurred.

---

## What it shows

| Screen | Question it answers |
|---|---|
| **People** | Who is out, and is anyone in trouble? Four counts, a search box, and one card per person — worst first. |
| **Alerts** | Who has asked for help? Oldest request first, with location, coordinates, waiting time, and one tap to the dialler. Below it, the last hour of safety events across the whole programme. |
| **Person** | One person in full: how to reach them, their emergency contact, and every safety event this session in order. |
| **Hazards** | What is broken in the street, ranked by how many *different* people have walked into it. |

### The four statuses

`Help required` → `Needs attention` → `No signal` → `Safe`, in that order of
urgency, which is also the order the wall sorts in.

Status is **derived, never stored**. `domain/Status.kt` computes it from
reported facts and the current time:

1. an open help request beats everything — acknowledging it does not end it;
2. no report for 90 s is `No signal`, whatever the last frame said;
3. an attention-grade event in the last 5 minutes, or a battery at or below
   15%, is `Needs attention`;
4. only then, `Safe`.

Silence is never read as safety. That is the one rule the whole app is arranged
around, and `StatusTest` pins every branch of it.

### Corroboration on the hazards screen

One report is one person's reading of a kerb in bad light and may be a detector
false positive. Two *different* people at the same place is a work order. The
screen keeps those visually distinct — a solid red pill versus a grey one — and
sorts confirmed above unconfirmed regardless of severity. A works department
that receives a list mixing the two learns to discount the list.

---

## What it deliberately does not do

- **No camera feed.** The network carries compact facts only—activity, battery,
  coordinates and an on-device safety verdict. Monitor never receives pixels.
- **No `CALL_PHONE`.** "Call" opens the dialler with the number filled in
  (`ACTION_DIAL`). The operator's own final tap is what places the call, so a
  mis-tap on a red card cannot ring anybody.
- **No dark theme.** The walking app is dark because its user never looks at
  the screen. This one is read in daylight next to paper, and the tinted status
  fills are built for a light ground. A dark palette nobody has looked at would
  be worse than not offering one.
- **No embedded map SDK or key.** The live participant's latest coordinates are
  shown as text and open in the phone's installed Maps app through a `geo:` URI.

---

## Design

Light, warm, and larger than an app of this kind usually is: 18sp body, 64dp
actions, 122dp count tiles, 20–26dp radii. It is read at arm's length, often by
someone standing up, often one-handed while holding a phone to their ear.

**Type** — three bundled OFL families in `res/font`, no downloadable-font
provider and no network, so a field phone with no SIM renders the same as a
demo bench:

- *Fraunces 144pt SuperSoft* for anything that names a thing — a person, a
  section, a count. A soft humanist serif; it makes the screen read like a duty
  sheet rather than a console.
- *Plus Jakarta Sans* for everything else, 400–800.
- *Bricolage Grotesque* ExtraBold for exactly one string — the wordmark in the
  masthead, set in black. Used once so it reads as a mark, not a third style.

All three are OFL; the licence text is bundled in
[`LICENSES-FONTS.md`](LICENSES-FONTS.md).

**Colour** — eight families in `ui/theme/Color.kt`, not one brand colour at
four opacities. Each status keeps its hue on every screen it appears on, and
each person keeps an avatar tint derived from a stable hash of their id. Status
is always stated in words as well as colour.

Every reusable piece lives in `ui/components/Kit.kt`; every string that names an
enum lives in `ui/Labels.kt`. If two screens ever disagree about what a state is
called, somebody wrote a string outside that file.

---

## Layout

```
data/        Models.kt         the facts a phone reports
             MonitorRepository the one interface the screens know about
             DemoMonitorRepository + DemoScript   sample data, re-anchored each tick
             MixedMonitorRepository + LiveWire    one access-key-protected live phone
domain/      Status.kt         status derivation, wall order, counts
             Hazards.kt        corroboration, works order, tally
             Elapsed.kt        relative time, locale-independent coordinates
ui/theme/    Color, Type, Theme
ui/components/  Kit, Fields, PersonCard
ui/screens/  Wall, Alerts, Person, Hazards
ui/          DashboardApp (shell), DashboardViewModel, Labels, Dialer
```

One activity, one view model, three screens, a sealed `Route`. No navigation
library and no DI framework: with a single data source both would be ceremony
around about twenty lines of wiring.

---

## Build

```bash
cd apps/dashboard-android && ./gradlew :app:assembleDebug
```

A separate Gradle build from `apps/android` on purpose — the walking app
carries an NDK pin, a CMake bootstrap and a llama.cpp tree, and none of that
should stand between a coordinator's laptop and a dashboard build. Versions
(AGP, Kotlin, Compose BOM, JDK 21) are matched to it so one toolchain serves
both.

```bash
./gradlew :app:testDebugUnitTest    # 32 tests
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

`minSdk 26`, arm64 or otherwise — nothing here touches the NPU, the camera or a
foreground service.

---

## Live feed

Run [`../coordinator/`](../coordinator/README.md) on the laptop. The current
event Wi-Fi default is `http://172.26.252.175:8000/`; override it at build time
with `-PdrishtiCoordinatorUrl=http://<laptop-ip>:8000/` when the network changes.

The same long random key must be present as `DRISHTI_ACCESS_TOKEN` in the
coordinator's ignored `.env` and as `drishti.monitorToken` in both Android
projects' ignored `local.properties`. Examples are committed; real identity,
phone number and key are not.

The walking app posts at most the newest envelope every two seconds. Monitor
polls at the same cadence and retains the last live fact when the laptop drops;
the existing 90-second rule then turns silence into `No signal`. Obstacle
events are generated only for non-clear safety decisions, on a changed reason
or after a 30-second cooldown, so one chair does not flood the timeline.
