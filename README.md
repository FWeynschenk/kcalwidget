# Kcal Balance

An Android home-screen widget for calorie balance, built on **Health Connect**. It shows
what you have eaten, what you are likely to burn by midnight, and how much room that
leaves — then checks its own arithmetic against your bathroom scale and corrects itself
when the two disagree.

Read-only: the app never writes health data, has no network permission, and nothing leaves
the phone.

`nl.flwe.kcalwidget` · minSdk 26 · targetSdk 36 · compileSdk 37 · Kotlin, Compose, Glance

---

## What it does

```
Left today                    14:32  ⟳
412 kcal
████████████░░░░░
In 1588      Out 2450     Goal 2000
Burn from tracker total
```

- **In** — `NutritionRecord` energy since the start of your day, from whichever app you log
  food in.
- **Out** — projected expenditure by midnight, not just what has been burned so far.
- **Goal** — what your weight goal allows you to eat today.
- Headline is `Goal − In`: green under budget, amber near it, red over. Over budget it
  becomes *"Move 230 kcal, about 50 min brisk walk"*.

Four widgets share one cached reading: **Minimal** (2×1, the number alone), **Balance**
(3×2, the default above), **Detailed** (4×3, adds burn-so-far, confidence, weight trend)
and **Trend** (4×3, a fortnight of daily balance with your weight line over it).

---

## The projection

Crediting the rest of the day with bare resting burn makes the morning budget far too
tight — almost every real day ends well above BMR. Extrapolating by clock time is no
better, because the hours before waking cost a fraction of an afternoon hour.

So the estimate starts at your own typical full day, and moves from there:

```
f        share of a normal day's burn usually done by now   (learned intraday curve)
surplus  burnedSoFar − f × typicalDay                       (how far ahead today is)
estimate typicalDay + min(surplus, 0)
floor    burnedSoFar + BMR/day × (1 − dayFraction)          (rest of today at rest)

projectedBurn = max(estimate, floor)
```

**The asymmetry is the whole design.** Running behind is believed immediately: a budget
that turns out too small can be handed back, while one that turns out too large has already
been eaten. Running ahead is not projected forward at all.

That second half is counter-intuitive but load-bearing. An afternoon walk is no promise the
day ends that much higher — the usual evening often does not happen, because the walk stood
in for it. Crediting it forward makes the budget spike and then bleed away for hours, which
is the worst possible shape. Nothing is lost by refusing to guess, because the floor already
carries the upside: as real activity accrues, "what is burned plus resting for the rest of
the day" rises on its own and overtakes the typical day exactly when the activity is large
enough to be certain of. A big day is still credited in full, just as it happens rather than
in advance, so the budget grows through the evening instead of shrinking.

A 300 kcal walk at midday followed by a quiet evening, projected hourly:

| crediting the surplus | at the walk | at 23:00 | swing |
|---|---|---|---|
| in full | 2900 | 2524 | 376 |
| weighted by `f` | 2750 | 2527 | 223 |
| not at all (current) | 2600 | 2524 | **76** |

All three land in the same place, because all three must. The remaining 76 is the day
genuinely ending below a typical one; everything above that was a promise being taken back.

Both branches converge on the truth: by the end of the day the floor is exactly what was
burned, and a shortfall against the typical day has been subtracted in full.

**The typical day is learned**, not assumed: `TdeeBaseline` takes the mean and the
cumulative *shape* of the last 14 complete days of `TotalCaloriesBurned`, normalising each
day before averaging so one big hike shifts the level without distorting the curve. Days
below 0.6× resting burn are dropped as "tracker not worn". Without enough history it falls
back to `BMR × 1.35`, and says so.

`burnedSoFar` prefers `TotalCaloriesBurned`, falling back to resting + active calories,
then steps, then resting alone. A tracker that has not synced reports a total far below
resting burn, which would invent a deficit, so totals under half of expected resting burn
are rejected in favour of the estimate. Which branch was used is always on screen.

---

## Weight, goals and calibration

**Trend, not the scale.** Ninety days of readings are smoothed with a gap-aware EMA (α 0.1,
aged by `1−(1−α)^days`), and the rate comes from a least-squares fit over the smoothed
series. BMI, goal progress and calibration all read the trend; a single reading is mostly
water.

**Goals are BMI milestones, in both directions.** Someone with obesity is offered the next
band down first, because a reachable step beats a distant ideal; someone underweight is
offered targets to gain towards. Picking one derives the direction and a sustainable rate.
A target below BMI 18.5 is flagged, a rate past 1% of body weight per week is flagged, and
a configurable minimum intake floor means the budget is never shown below it silently. BMI
ignores build and muscle, and the app says so.

Direction of travel comes from the **rate**, never from current-versus-target position:
position flips the instant you cross the target, which would make arriving indistinguishable
from never having set off.

**Calibration** is the interesting part. Over a few weeks, weight change and energy balance
have to agree; where they do not, an input is biased:

```
expectedDeltaKg = Σ(intake − burn) / 7700
observedDeltaKg = fitted weekly slope × spanDays / 7
biasKcalPerDay  = (observedDeltaKg − expectedDeltaKg) × 7700 / days
```

The observed change comes from the **fitted slope**, never from the difference between the
first and last smoothed weights: an EMA lags its input by a constant, and a series that
starts cold and ends warm carries that entire lag in its endpoint difference — worth around
200 kcal/day of invented bias at these settings. A slope is immune, because lagging a line
does not tilt it.

Nothing is reported unless 14+ complete days, 3+ weigh-ins spread over a fortnight, 80%+ of
days with food logged, and a bias above the 75 kcal/day noise floor all hold — and the UI
names whichever check failed rather than showing a number built on sand. It looks at the
last 28 days, not all history: judging someone who started logging a month ago against a
quarter of mostly empty days would fail the coverage check forever.

Attribution defaults to suspecting **burn** (`intakeTrust` 0.85), because a tracker's
estimate is the softer number. When a discrepancy is first found — and only then, since the
question means nothing before that — the app asks how accurate your food logging is, and
the answer re-splits the blame.

Corrections are multiplicative, capped at 20%, and eased in 25% of the gap per recheck, so a
budget never jumps on one fortnight's data. The check runs daily in the background.
`burnedSoFar` is never rewritten — it is what the tracker reported, and overwriting it would
hide the disagreement rather than surface it.

The Calibration screen draws both curves: what the calories say your weight should have
done, anchored to where you actually were, against the smoothed weight itself. They start
together, and the gap that opens is the whole of the disagreement.

---

## Refresh

Health Connect has no change notification, so the widget polls — but the mechanism that
matters is **unlock**. `provideGlance` runs once per Glance *session*, and a session lives
as long as the widget is visible: locking tears it down, unlocking builds a new one. The
staleness check at the top of `provideGlance` therefore runs at exactly the moment the home
screen is looked at. Measured end to end: session start to widget updated, 1.4 seconds.

Also: a 15-minute `RefreshWorker` (WorkManager's floor), `updatePeriodMillis` at 30 minutes,
the ⟳ button, and opening the app.

Two approaches were tried first and rejected on evidence, so they do not get reinvented:

- **A manifest receiver for `ACTION_USER_PRESENT`** — not delivered. Android's implicit
  broadcast restrictions cover it; verified on device, where Play Services received the
  broadcast and an identically declared receiver here did not.
- **A Health Connect change subscription** — there is none. `connect-client` 1.1.0 ships a
  `DataNotification` class with only `from(Intent)`, no action constant and no way to
  subscribe. 1.2.0-alpha06 is the same.

Note that `adb shell am broadcast` cannot test either path: `APPWIDGET_UPDATE` and
`USER_PRESENT` are protected broadcasts, so shell attempts fail with a `SecurityException`.
A real lock/unlock is the only test.

---

## Notes on Health Connect

The provider is slow in ways that are not uniform, and it fails quietly. Four rules came out
of making this work at all.

**Chunk long spans.** `aggregateGroupByPeriod` over 90 days of `TotalCaloriesBurned` does
not return — a tracker writes continuous records, so a quarter is hundreds of thousands of
rows to bucket. Measured on one device, same window, same call shape:

```
weights:  16ms
intake:   53ms
burn:     gave up after 12007ms
```

Fourteen days is a span it serves quickly, so longer spans are cut into pieces that size and
issued together, each with its own budget.

**Nothing unbounded, and one slow read must not sink the others.** Every query has a
timeout, and independent queries run concurrently. A shared all-or-nothing budget means one
slow query returns nothing at all instead of two thirds of the data.

**Reads past 30 days need `READ_HEALTH_DATA_HISTORY`.** Without it the provider refuses
rather than trimming, so the window is clamped when the permission is absent. Like
`READ_HEALTH_DATA_IN_BACKGROUND`, it is requested separately — an older provider rejects a
whole batch containing a permission it does not recognise.

**Failures must reach the screen.** `runCatching { }.getOrNull()` around a Health Connect
call discards the only evidence there is. Every read records what it asked for, how long it
took and what went wrong into `HistoryDiagnostics`, shown under "What was read" on the
Calibration screen. `runCatching` also catches `CancellationException`, which silently
breaks structured concurrency and disguises a timeout as an ordinary failure — rethrow it.

One more, for widgets: a Glance `ActionCallback` runs on a **broadcast receiver's** budget.
Doing a Health Connect read inline there gets the process killed for a background ANR,
taking any open screen with it. Widget actions enqueue the worker instead.

---

## Permissions

All read-only: `READ_NUTRITION`, `READ_TOTAL_CALORIES_BURNED`, `READ_ACTIVE_CALORIES_BURNED`,
`READ_BASAL_METABOLIC_RATE`, `READ_STEPS`, `READ_WEIGHT`, plus optional
`READ_HEALTH_DATA_IN_BACKGROUND` (keeps the widget current while the app is closed) and
`READ_HEALTH_DATA_HISTORY` (reads past 30 days). `POST_NOTIFICATIONS` is requested only if
you switch weigh-in notifications on; they are off by default.

Both Health Connect rationale entry points are declared: the
`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` filter for Android 13 and below, and the
`VIEW_PERMISSION_USAGE` / `HEALTH_PERMISSIONS` activity-alias for 14+.

---

## Layout

```
data/
  settings/       AppSettings (body, goal, sources, calculation, features, calibration)
  sources/        which app writes which metric, and a recommendation
  weight/         BMI bands, EMA trend, goal milestones, calibration
  history/        per-day series, diagnostics, weekly banking
  HealthRepository, Energetics, TdeeBaseline, DayWindow
ui/
  home/ history/ settings/ wizard/ components/ nav/
widget/
  four Glance widgets over one cached state, plus the bitmap trend chart
notify/
  channels and the daily reminder worker
```

Data sources are selectable **per metric**. The default is "all apps", which is right until
two apps write the same thing — Health Connect sums overlapping records, so two trackers
both logging a walk are counted twice. Burn, active calories and steps therefore carry a
single-writer recommendation; nutrition and weight do not.

"Today" is the device's local day by default, with a configurable boundary (e.g. 04:00) so a
late dinner lands on the day you ate it. Every time range goes through `DayWindow`, and
changing the boundary invalidates the learned curve, which is indexed in the same frame.

A setup wizard covers all of it on first run and can be re-run from Settings.

---

## Building

Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20, JDK 25. AGP 9 has built-in Kotlin support, so there
is no `org.jetbrains.kotlin.android` plugin — only `com.android.application` and the Compose
compiler plugin. There is no Java toolchain either: compilation runs on whichever JDK runs
Gradle and targets Java 17.

`local.properties` needs `sdk.dir`. Then:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

100 unit tests cover the parts worth trusting: the projection and its floors, BMI bands and
goal milestones in both directions, EMA smoothing and slope recovery, the calibration
measurement, every gating rule, attribution and easing, day-boundary arithmetic, and the
reminder rules.

`compileSdk` is 37 because androidx 1.19/2.11 requires it; `targetSdk` stays at 36 so the
app keeps the runtime behaviour it was written against. Lint's `OldTargetApi` warning is
that choice, not an oversight.

---

## Limitations

- Intake is whatever another app writes to Health Connect. If nothing logged food today, the
  widget reads zero and says so rather than pretending.
- The data-source picker shows package names when Android's package visibility rules stop
  the app resolving a display name. `QUERY_ALL_PACKAGES` would fix it and is not worth it.
- Glance 1.2 has no public day/night `ColorProvider`, so accent colours are resolved from the
  provider context per update rather than per recomposition.
- Calibration assumes the 7700 kcal/kg figure and that weight change is fat. Over a few weeks
  that is close enough; over a few days it is not, which is what the gating is for.

Not medical advice, and not a substitute for it.

---

## License

MIT. See [LICENSE](LICENSE).
