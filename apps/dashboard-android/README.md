# DRISHTI Monitor

An Android app for the people who run a walking programme — an NGO desk, a
field coordinator, whoever answers the phone when somebody presses SOS.

It replaces the React dashboard in `apps/dashboard/`. That one assumed a
laptop, a FastAPI service and a Wi-Fi network; this one assumes a phone in a
pocket, which is what a coordinator in the field actually has.

> **This build has no backend and no connection to the walking app.** Everything
> on screen is sample data, and the masthead says so in every screenshot. The
> seam where a real feed arrives is one interface — see *Wiring a real feed*
> below.

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

- **No `INTERNET` permission.** There is nothing to talk to, and a manifest
  that claims otherwise is a manifest that lies.
- **No `CALL_PHONE`.** "Call" opens the dialler with the number filled in
  (`ACTION_DIAL`). The operator's own final tap is what places the call, so a
  mis-tap on a red card cannot ring anybody.
- **No dark theme.** The walking app is dark because its user never looks at
  the screen. This one is read in daylight next to paper, and the tinted status
  fills are built for a light ground. A dark palette nobody has looked at would
  be worse than not offering one.
- **No location on a map.** Coordinates are shown as text because there is no
  map dependency and no key; the label ("Adyar Signal, north-east corner") is
  what an operator repeats on the phone anyway.

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
./gradlew :app:testDebugUnitTest    # 28 tests
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

`minSdk 26`, arm64 or otherwise — nothing here touches the NPU, the camera or a
foreground service.

---

## Wiring a real feed

Implement `data/MonitorRepository`:

```kotlin
interface MonitorRepository {
    val snapshot: StateFlow<DeskSnapshot>
    fun acknowledgeHelp(personId: String, operatorName: String)
    fun clearHelp(personId: String)
    fun raiseDeskCheck(personId: String, operatorName: String)
    fun setHazardStatus(hazardId: String, status: HazardStatus, assignedTo: String?)
}
```

and change the one line in `MainActivity.onCreate` that installs the demo one.
No screen changes, because no screen knows where a `DeskSnapshot` came from.
Set `DeskSnapshot.source = LIVE` and the masthead badge stops saying *Sample
data* — which is the only thing that should ever make it stop saying that.

Two things a real implementation has to decide that this one dodges:

- **Writes return `Unit`.** A networked one needs failure back. Leaving that
  generality in now would be a guess at its shape.
- **The walking app sends nothing today.** It has no telemetry client since the
  network DTOs were deleted; something has to put a `DeskSnapshot` on a wire
  before any of this is live.
