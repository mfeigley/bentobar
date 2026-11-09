# BentoBar: notes for working on the code

(Published by Meridian11 LLC as `com.meridian11.books.bentobar` since 1.7, a new app with a new signing key and its
own Play listing. Up to 1.6 it was `io.github.kuscher.bentobar`, and DiscoBar, `io.github.kuscher.discobar`, until
0.4.1.)

A status bar organiser for Googlebooks (Googlebook OS = Android 17 desktop). A
plain APK: no adb grants, root or system changes in the product (the user's hard requirement).

## This repo is public
The Play listing links here. Keep out of every file, commit message and release note: device serial numbers and
adb names, build numbers and codenames, what else is installed or open on the owner's devices, the names of his
private projects and paths into their repos, and where signing keys are backed up (say "backed up privately").
Name no other company's app as a model or a comparison, in the app or anywhere here.

## Layout
- `app/src/main/java/com/meridian11/books/bentobar/`
  - `bar/BarService.kt`: the AccessibilityService plus `BarController`. It scans the status bar,
    places the strip, hides it (fullscreen, keyguard, screen off, a shade covering the bar), runs
    menus, keep awake (a flag on the strip window) and colour sampling, and has adb `debug()` hooks.
  - `bar/StatusBarScan.kt`: finds the status bar window (TYPE_SYSTEM at y=0) and the free area
    (the `DesktopStatusBarSpacer` node on Googlebook OS, else the widest gap).
  - `bar/BarPixels.kt`: the status bar's text and background color from the pixels around its clock
    (pure, unit-tested); `bar/Contrast.kt` then holds every strip color to 4.5:1.
  - `bar/BarNeighbours.kt`: which app windows make the bar look different (one against its lower
    edge, one under it), remembered from one window list to the next; a change asks for a new color
    reading (pure, unit-tested).
  - `bar/ColorWatch.kt`: when the bar's colors are read after a cause, and when the readings stop
    (pure, unit-tested).
  - `bar/Overlay.kt`: a Compose host in a TYPE_ACCESSIBILITY_OVERLAY window (the strip, the tooltip and a popup's
    shadow).
  - `bar/MenuWindow.kt`: a popup's window (1.3, docs/design/1.3): a `ComponentDialog` of type
    TYPE_ACCESSIBILITY_OVERLAY, because only a `Window` can blur what is behind it. The window is exactly the card;
    before each frame its root view is cut to the glass as far as it shows (bottom edge only), since the platform
    blurs exactly the root view's rectangle (and Compose places input by its top-left corner, which never moves).
    The shadow lies outside the card, which that cut would clip, so it is drawn in a second, untouchable `Overlay`
    just below (`MenuShadow`). One clock drives the opening, the blocks, the fold and the turn-round
    (`ui/MenuMotion.kt`, pure and unit-tested); it starts once the window's first frame is committed and frames come
    on time, and each frame's values go to Compose at once (`Snapshot.sendApplyNotifications`) so the blur and the
    drawn glass move together. Closing passes touches through at once; a timer removes the window regardless.
  - `ui/Glass.kt`: the glass's per-frame state, its look (veil, rim, hairline; a solid card where the platform
    has no blur), the drawn shadow, and `DropColumn`, in which `MenuCard`'s children drop into place as blocks.
  - `bar/BarUi.kt`: the strip (chevron, FitRow, items, clicks/right-clicks/wheel), and the slider an
    item can have in its text's place (`ItemState.slider`: `VolumeTrack` draws it, `Modifier.slides`
    takes the pointer in its zone and consumes the press, so `clicks` on the item around it sees
    neither a click nor a drag). Its arithmetic and the rules of a press (`SliderGesture`: a click, a
    drag, a finger's tap, swipe and long hold, a press that is taken away) are in `bar/SliderMath.kt`,
    pure and unit-tested; change the rules there, not in the pointer loop.
    The route line an item can have in its icon's place is the same line with a plane on it and no
    pointer (`ItemState.route`: `RouteTrack` draws it, `SliderMath.route` says where its parts stand,
    `StripLook.route` what color they take). The Flight item has one from its countdown until an hour
    after landing (`FlightText.bar` says when), colored by `FlightRules.stands`; shown as text alone
    it has none (`Display.line`), and its words keep their tones.
    The hover is one highlight for the whole strip, not a box per item (1.3): `bar/StripHighlight.kt`, pure and
    unit-tested, says which item the pointer is over (gaps split at their middles, ‹ one more item), carries the pill
    there like Booklight's rubber-band rows, and says when it comes and goes (an open popup's item, a held slider, a
    drag, the animator scale). The strip reads the pointer for it on the Initial pass without consuming anything, runs
    its frames only while it moves, and draws it in the Row's `drawBehind`; change its rules there, not in the strip.
  - `bar/Menus.kt`: the popup's surface (`MenuSurface`: glass, clip to the shown glass, text colour) and its shadow
    (`MenuShadow`), the right-click item menu and the ‹ menu.
  - `items/`: `ItemType` + `ItemState`, `Items` registry + `Ticker` (1 Hz while anything is
    visible), `Env` (samplers, launch helpers), `Timers`, `Calendar`, `Notify` (channels,
    glyph icons, `Chips` for the Live Update chip), `TextRules` (text from a layout or a reply as one line,
    counted as a reader counts it), and the item types: several each in
    `SystemItems.kt`, `TimeItems.kt` and `ToolItems.kt`, the newer ones in files of their own
    (`CpuItem`, `ClockItem`, `SoundItem`, `MediaItem`, `DevicesItem`, `HeatItem`, `WeatherItem`,
    `FlightItem`, `ShortcutItem` with its pure `ShortcutRules`, `StocksItem`). `WeatherHere` is Weather's My location
    (what it does is the pure `HereKeeper`, on `HereRules`): Android's approximate location, in memory
    only, rounded to 0.1° (`WeatherRules.nearby`, in `WeatherLoad.place`) before anything is asked or
    held under it; its reading is never kept on the device, and the layout holds `where=here` and never
    a place. A type gets `onLive()`,
    `sample(now)` once a second and `onIdle()` from the `Ticker`: listeners and polls hang on those,
    so none exists while the bar is hidden, the screen is off or no such item is outside Off.
  - `items/Refresher.kt` is `Calendar`'s way of loading as one class (a background thread, one load
    per key, an immutable snapshot, a generation counter); `items/Ask.kt` is the same for one
    question at a time (a search). Both are pure Kotlin; `items/Background.kt` wires them to the app.
    `state()` calls `want(key)` and reads `peek(key)`: nothing is scheduled, so nothing loads while
    no item is sampled.
  - `items/NowPlaying.kt`: the media players' sessions as one immutable value, for the Now playing
    item, alive only while such an item is. `items/MediaAccess.kt` is the entry in Android's
    "Notification access" list that Android wants for it: it asks for no notification type and is
    not bound by Android by itself (see the manifest's comment). Its pure rules are in
    `items/NowPlayingRules.kt`.
  - `net/`: the only code that opens a connection (pure Kotlin). `Host` is an enum of the hosts BentoBar may ask; a `Request` names one of them, a path and a query, so no call takes an
    address. `Http.get` refuses unless the service is switched on, an item that uses it is outside
    Off and something that shows items is on screen, and never runs on the main thread. A query can
    hold a key: a `Request` prints as host and path only, and no exception leaves `HttpTransport`.
  - `data/`: `Model.kt` (`BarConfig`, `ItemConfig`, `Section`…) and `Store` (JSON in
    SharedPreferences, a process-wide StateFlow shared by the service and settings). `Online.kt`
    holds what belongs to one install and never travels: whether each online service is switched on
    and the user's own key for one, in a file in Android's no-backup directory (so in no backup, no
    device transfer and no copied layout). `Kept.kt` keeps small texts there too (a service's last
    answer, as the app's own model, never the reply as it came).
  - `ui/`: `MainActivity` (nav rail), `BarPage` (preview, sections, item detail), `Reorder` (drag
    and drop across the section cards: the gesture sits on the container, a copy of the row floats
    above the cards, the Store changes once on the drop), `Pages` (Add, Look, Setup, About),
    `Disclosure` (the accessibility disclosure and consent Google Play requires, and the one list of
    what the service does that Setup shows too), `Theme`, `MenuKit` and `Controls` (shared UI pieces).
  - "Show when…": a type's `trigger` (in `ItemType.kt`) words its pop-out rule, and its `Threshold`
    is the one source of the option key and default for both `state()` and the settings slider.
  - `tile/Tiles.kt`: the BentoBar, Keep awake and Timer tiles.
  - `util/`: `Sym.kt` (generated), `Ui.kt` (fonts, `SymIcon`), `Fmt.kt`, `Dates.kt` (locale-aware dates
    and times from skeletons), `Now.kt` (the clock for ages and countdowns, which a debug build's
    test hook can move), `Units.kt` (temperatures in the user's unit), `DebugReceiver.kt`.
- Text: every user-facing string is a resource. `res/values/strings.xml` is US English (the default);
  `res/values-en-rGB/strings.xml` holds only the strings whose British spelling differs. Item titles and
  blurbs are `@StringRes` ids on `ItemType`; non-Compose code uses `Env.str`/`Env.plural`. Counts use
  `<plurals>`, values use positional format args. `res/xml/locales_config.xml` lists the languages.
  A newer feature's text is in a file of its own, `res/values/strings_<feature>.xml`, where every name
  starts with `<feature>_`, `item_<feature>_` or `trigger_<feature>`; its British spellings go in
  `values-en-rGB/strings_<feature>.xml` with identical copies in en-rAU, en-rNZ, en-rIE, en-rIN and
  en-rZA. `LocaleCopiesTest` checks every `strings*.xml`.
- `tools/logo.py`: draws the icon (a bento box seen from above: a status bar compartment with a ‹
  and dots cut out, over three item compartments; rice, salmon, tamago and edamame on ink blue):
  launcher foreground/monochrome/background, `ic_bentobar` (24 dp, app header, ‹ menu) and
  `ic_tile_bar` (the QS tile), plus docs/images/icon.png for the README. No font needed.
- `tools/icons.py`: subsets Material Symbols Rounded into `assets/fonts` (outlined and filled),
  writes `Sym.kt`, and exports some glyphs as vector drawables (`res/drawable/sym_*.xml`, for
  tiles and notifications). Add an icon name there, then rerun it.
- `tools/readme_images.py`: composes docs/images/*.png (README) from raw captures in
  ~/.cache/bentobar/shots (see its docstring). Capture with a **demo layout** (`./bento debug cfg` to
  save the user's, `./bento debug import <base64>` to load the demo and later restore theirs) so no
  calendar titles or other personal data show, and with `./bento winshot TITLE|app FILE`, which
  saves a PNG of one of BentoBar's own windows (no pointer, no other apps; written to the debug
  app's cache and pulled with run-as, since a broadcast result can't carry a big image).
- `docs/research/`: the device findings (probe results) and the official-docs research, with URLs.
- `probe/`: the throwaway feasibility probe (Gradle-free build). Not part of the app.

## Dev loop
- `./bento app` builds the debug APK, installs it, enables the service and opens settings.
- `./bento debug dump|open TYPE|ctx TYPE|chevron|barmenu|hover on|off|scroll TYPE N|timer MIN|awake [MIN|off]|bar on|off|finish|reset|add TYPE [section]|remove ID|set ID k=v|look KEY VALUE|windows`.
  The receiver (`src/debug`, debug builds only) is guarded by DUMP, so only adb can call it.
  `solid on|off`: popups as if the platform had no blur (the solid card). `look switch on|off`: "Switch popups on hover"; `look animations on|off`: "No animations". Each frame of a popup's opening and its
  blur region go to the log with `adb shell setprop log.tag.BentoBarMotion DEBUG`.
  On a test device with a released BentoBar, `./gradlew assembleDebug -PreleaseSignedDebug` signs the debug build
  with the release key (from `~/.config/bentobar`): it installs over the release, same version code, and keeps its
  layout and its accessibility switch.
  Also `now +3h|+90m|off` (moves the clock everything newer reads, `util/Now`), `net [reset]` (requests
  sent per host, and whether each service may be asked right now), `online weather|flights|stocks on|off`,
  and `TYPE …` or `item TYPE …`, which go to that item type's own `debug(args)`. What a hook prints
  is logged: no hook takes or prints a key, and a type marked `discreet` prints no title (`state media`
  and `state flight` print the text's length).
  The types' own hooks stage what a test can't make happen: `media stage playing|paused|none|notitle|two|
  live|noaccess|starting|long|wide|emoji|rtl|off`; `devices stage mouse=15 keyboard=40c stylus=unknown`
  and `devices off`; `heat stage 0..6`, `heat level 0.84`, `heat temp 41.3`, `heat off`; `weather stage
  clear|rain-soon|rain-this-hour|rain-later|raining|storm|snow|old|error|slow-down|offline|offline-new|no-answer|loading`, `weather search …`,
  `weather fail …`, `weather off`; `flight show NAME [TURN]` (`flight show` lists the names; every look of the
  route line has one, with `old` after a name for an answer over an hour old and `now +61m` for a
  cancellation or a diversion after its alert; bare `flight` then says which line the bar has), `flight off`;
  `flight show several` and `flight show several-next` (the flights of a number that flies three times a
  day, to choose from in the item's menu: tomorrow's, and the next within a day; the bar shows the number
  meanwhile, closing the menu drops them as it does the real ones, and `several 2` is the second of them,
  followed; bare `flight` prints the question and the rows);
  `sound fixed on|off`. Each type's bare name prints what it knows (`media`, `devices`, `heat`, `flight`).
  For color tests a debug build has a window that lies under the bar and turns its icons dark or light
  on command, with no event of any kind: `adb shell am start -n com.meridian11.books.bentobar/.util.BarFlipActivity
  --ez dark true` (now), `--ez dark false --ei after 1500` (in 1.5 s), `--ez dark true --ei unlock 3000`
  (light from screen off until 3 s after the unlock: a bar that keeps the lock screen's look),
  `--ez close true` (closes it: on a desktop the Back key doesn't). On a Googlebook it only reaches the
  bar from full screen (the keyboard's full-screen key, sent to it while it has the focus).
- `./bento shot`, `./bento menushot` and `./bento appshot` capture the status bar, the open menu and
  the settings window. A menu's crop is its card; its shadow is in the window "BentoBar menu shadow" below it.
  Behind the glass other windows show through, blurred: don't publish captures with private windows behind.
- Release (`docs/RELEASING.md`): bump `versionCode` and `versionName`, add `docs/release-notes/<version>.md`
  and Play's "What's new" (`release-notes.txt` in the store kit, 500 characters at most; the store kit is kept
  privately, outside this repo), commit, push, then `git tag v<version> && git push origin v<version>`. GitHub
  Actions (`.github/workflows/release.yml`) builds, signs and publishes: `BentoBar.apk` + `SHA256SUMS` as the
  GitHub release, the bundle as a draft on Google Play's closed-testing track. Someone still presses
  "Send for review" in the Play Console. "Run workflow" on the Actions tab is a dry run.
- The release key (alias `bentobar`, cert SHA-256 AA:07:4B:07:…:BD:90:CE:93, CN=BentoBar, O=Meridian11 LLC; made
  2026-11-03 for 1.7, the one Google Play uses too) lives in the secrets of the GitHub environment
  `release`; you never need the key file. The keystore and its password are backed up privately,
  outside the repo.
  On a machine that has `~/.config/bentobar/keystore.jks` and `keystore.pass`, `./gradlew :app:assembleRelease`
  still signs with it; without them the release build is unsigned. A debug and a release install
  can't replace each other (different keys), so uninstall first.
- adb uses VSCodeBook's Unix socket (`~/.config/vscodebook/android.env`), never tcp:5037.

## Things learned the hard way (see docs/research/device-findings.md)
- **The status bar window can be opaque.** On the HP it's glyphs on transparent; on the Acer
  Googlebook 14 it's white glyphs on its own black while an app is maximized, and see-through over
  the wallpaper (like the HP) otherwise. The colour sampler tells background and text
  apart (`barColors`), decides dark/light from the background, and `bar/Contrast.kt` holds every
  strip colour to 4.5:1, with pure black as the last fallback for mid grays where neither white nor
  near-black reaches it (`ContrastTest.everyOpaqueBarReaches45` sweeps every gray and a color grid).
  Re-check on any new device: `look fg=… bg=… contrast=…` in the log.
- **The bar changes its look without telling anyone.** On two Googlebooks the status bar is solid
  black while a window is maximized and see-through otherwise, in the same window, with no event. So
  `check()` notes which app windows sit against the bar's lower edge and asks for color readings
  when that changes (screenshots of the bar window: one at once, one a second later).
  The text color is the glyphs' cores, not the average of everything that stands out: blended edges
  made it #EFEFEF beside the system's white. A reading with nothing opaque (the bar caught fading)
  keeps the last colors and is retried.
  A third look: on a Googlebook that keeps the bar when an app goes full screen (the keyboard's
  full-screen key), the app's window lies under the bar, which then takes that app's light or dark
  icons (black on a light app, and the strip stayed white). So the window
  under the bar counts too, by which window it is (`bar/BarNeighbours.kt`).
- **The bar changes late, and a reading that matches the last one proves nothing.** After an unlock
  the strip stayed white: the bar kept the lock screen's light icons after the strip was back, the
  reading 250 ms in and the one 800 ms later both saw them, and nothing asked again. So a cause is
  followed by readings spread over the time the bar may take, each taken whatever the one before
  showed (`bar/ColorWatch.kt`): two after a change of windows or of the wallpaper, six over ten
  seconds when the strip comes back on screen (an unlock, a wake, a full-screen app or a panel gone)
  or the theme or the display changed; a look that still differed at the last one is read once more.
  Every reading is logged (`read text=… (same|changed|nothing), next in … ms`). No Googlebook could be
  unlocked from a test, so this was reproduced with `BarFlipActivity` on an emulator (the unlock hook
  above): 0.9 ends white there, this ends dark.
  More readings per cause must not become many readings when a cause keeps coming (a live wallpaper
  reporting colours every second, an app that cycles its screens, something that hides and shows the
  strip again and again): after a dozen readings within a minute a cause's first reading waits two
  seconds behind the last one and what follows it comes four seconds later, where the next cause of
  the stream takes its place. That holds every such stream to one reading in two seconds, which is
  what 0.9 allowed a stream of windows, and the last cause still gets its readings. The watch is told
  when a screenshot is asked for, not when its answer comes: a cause in between is not served by a
  screenshot taken before it, and two are never asked for within 400 ms (Android refuses the second).
- **The window list can end early.** With a dialog on top, or some apps' own windows, the
  accessibility window list holds that window and nothing below it: no home screen, no other app.
  A window missing from the list has not left. `BarNeighbours` reads only what is listed: the home
  screen or a maximized window coming and going with every dialog would otherwise cost a screenshot
  of the bar each time, for a bar that never changed (see Play Protect below). Readings the windows
  ask for also keep two seconds apart (`BarNeighbours.wait`).
  One case is left on purpose: a full-screen app that turns its own status bar icons from dark to
  light in the same window (a light page, then a dark one) says so with no event, and the strip keeps
  the old color until the next change of windows. Only a timer would catch it.
- **Name the weight in every text style that uses `Fonts.bar`.** Compose asks the typeface for the
  style's weight, 400 when it names none, whatever weight the typeface was created with: the strip
  drew regular text beside the system's semibold clock. The status bar's style is the family
  `variable-label-large-emphasized` (Google Sans Flex, weight 600); `Fonts.barWeight` carries it.
- **Google Play wants a prominent disclosure with consent** before an app that isn't an
  accessibility tool sends anyone to turn its service on: in the app's normal use, saying what the
  API is used for and what happens to what it reads, with two buttons (agree and decline); a single
  button, a switch or closing the dialog don't count. `ui/Disclosure.kt` is that screen. It comes up
  on first opening and from Setup › Turn on until the answer is Agree (`Store.consent`, kept outside
  the layout so Copy settings can't carry it). The video in Play's Accessibility declaration has to
  show it.
- **Settings state is observed, not read while drawing** (`ui/SetupState.kt`, `bar/BarStatus.kt`).
  In desktop windowing, Settings opens in its own window and BentoBar's stays resumed, so onResume
  alone misses changes; and with strong skipping (Kotlin 2.x) a composable reading Android state
  inside isn't redrawn when its parameters are unchanged.
- **Going online is six items' business, and each install's own decision.** Weather, Air quality, Exchange
  rates, Sports scores, Flight and Stocks ask a service; nothing else does, and nothing the accessibility service or any other
  item reads can get there (`net/` is the only way out, and only those six types name a service in
  `ItemType.online`).
  A service is asked only after its item was set up on this install: `data/Online.kt` records that
  outside the layout, so a pasted layout or a restored backup turns nothing on. Off (Setup › Online
  services) stops requests at once and deletes what was fetched (`ItemType.forgetFetched`).
  The one location permission is `ACCESS_COARSE_LOCATION`, for Weather's My location, asked for only when
  an item chooses it (the exported `MainActivity` asks it only for a layout with one: `HereRules.mayAsk`).
  Where the device is is kept only while such an item is outside Off and the switch is on
  (`HereRules.wanted`; else it is forgotten, with an ask on its way), and Android is asked only while
  such an item is sampled (`TickRules.needed`, as `Ticker.sampled`): on screen, waiting in Hidden for its
  Show when rule in any hidden mode (which needs the forecast where the device is), or while the settings window is open;
  else an ask on its way is called off. Then when the fix is half an hour old, and without one after 1, 2,
  5 and 15 minutes, then every half hour (asks that find nothing new while a fix is still good wait the
  same way).
  A fix is good for 35 minutes, so after a night yesterday's place is never asked about (`HereRules` and
  `HereKeeper`, pure and unit-tested; `WeatherHere` is the keeper on Android's location service).
  `ManifestTest` pins the permissions, the backup rules, cleartext off and the listener's entry;
  `HttpTest` and `HttpTransportTest` pin the hosts and what a request may carry; `OneWayOutTest` names
  the files that may ask a service (`items/Weather*`, `Air*`, `Fx*`, `Sports*`, `Flight*`, `AirLabs*`, `Stocks*`, `Finnhub*`) or read a key.
- **US stocks** (`StocksItem`, type "stocks", rules in `StocksRules`, requests and what is kept in `StocksLoad`, replies in
  `Finnhub`): US stocks only (Finnhub's free plan), with the user's own key; one quote request per stock,
  every 2 minutes while the market is open (`Market`: weekday hours in New York; a holiday is seen in quotes
  that keep yesterday's time), once after the close. The bar takes turns (`ItemState.turns` sizes its place
  for the widest). `./bento debug stocks stage up|down|mixed|big|closed|loading|offline|refused|nokey|none`.
- **A flight service's reply repeats the key it was asked with**, and the platform puts addresses
  into exception messages. So: never keep or log a reply as it came, never log or rethrow what a
  request threw, and keep a key out of every state, description and debug line. Its count of lookups
  left (`request.key.limits_total`) lags: the same figure after three lookups in a row, so the app
  says "about".
- **A flight number is not one flight a day.** UA 1227 flies Orlando to Newark, Newark to San Francisco
  and San Francisco to Portland every day, and the service's one-flight question answers with whichever
  is nearest to now. A flight is its number, the day it leaves and the airport it leaves from
  (`FlightRules.same`), and what is kept of one names all three, so that it is found again
  (`AirLabs.lookup` with an airport). A press of Track asks for the timetable too, which is what says how
  often a number flies (`AirLabs.candidates`: two lookups where there was one), and the menu asks which
  flight where there are several. "The next flight" is one for each route: a rule worded as "every line's
  next departure within 24 hours" asks about every daily flight while it is in the air (tomorrow's is
  less than a day off), so the flight the service answers with stands for its route, and a route with a
  timetable line for each kind of day counts once.
- **A weather service's hourly chance of rain is for the hour that ends at its time** (Open-Meteo:
  "preceding hour"), while the temperature and the weather code are of that instant. The rain rule
  names the hour the chance is for, and an hour's cell takes its chance from the entry after it.
- **Least privilege** (user feedback): the accessibility config subscribes only to
  `typeWindowsChanged`. No content events, key filtering or motion events. Code reads only the
  status bar window. Don't add broader access for nice-to-haves.
- `notificationTimeout` must stay 0. With 200, Android merges window events; our own strip's
  resize could swallow the status bar's "hidden" event.
- Without content events, the node cache goes stale: `clearCache()` before a full scan. Every 2 s
  a light check refreshes only the spacer node; a full scan runs every 30 s or on change.
- The status bar hides about 1.9 s after an app goes fullscreen (the system's timing); BentoBar
  follows within about 100 ms of the window event.
- Live Update chips show only ONE per app, drop text over about 7 characters, and are hidden while
  the posting app is "visible" (uid TOP). **BentoBar's own settings window open means no chip.**
  With only the accessibility service running (BFGS) the chip shows.
- The Agent-task status bar API is in the platform-37.2 SDK stubs but not on the device (37.1),
  and it needs the assistant role anyway. We compile against 37.2 stubs: check each new API on
  the device.
- `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` isn't public; we open Accessibility settings with the
  `:settings:fragment_args_key` highlight extras.
- Compose in the strip: `ItemState`, `ItemConfig` and `StripEntry` are `@Immutable` so unchanged
  items skip recomposition; items with a `widthKey` (ticking numbers) sit in a fixed-width slot sized for their widest
  reading (`Fmt.widthTemplate`: every number becomes 888, clock digits keep their shape), so neighbors never move.
  The release build uses about 0.8% of one core with network speed on (debug builds are 3–4×
  slower; measure release).
- **Window width:** never WRAP_CONTENT for overlay windows. ViewRootImpl first measures a WRAP_CONTENT
  window at `config_prefDialogWidth` (580 dp here) and Compose never reports MEASURED_STATE_TOO_SMALL,
  so a wider strip was cut off and pushed under the chevron. `MeasuredStrip` reports the natural
  width and the controller sets it exactly; menus get their exact width too.
- **CPU load:** /proc/stat, /proc/loadavg, /proc/uptime and /proc/pressure are denied to apps, and
  `SystemHealthManager.getCpuHeadroom()` throws UnsupportedOperationException here. Readable:
  `/sys/devices/system/cpu/cpuN/cpuidle/stateM/time` (idle residency, µs), cpufreq
  `scaling_cur_freq`, and `/sys/class/kgsl/kgsl-3d0/gpubusy` (Adreno). Load = 1 − Δidle/Δwall per
  core; it matched `top` within ~1 point under load (reads a few points high at idle).
  `./bento debug cpuprobe` checks what's readable on a new device.
- **CPU cost:** measure release builds after `cmd package compile -m speed -f` (adb installs are
  only verified, so JIT dominates at first). The big costs were full node-tree scans on every
  window event (title changes included) and sampling folded items; now ~0.5% of one core with CPU
  and network ticking. The manifest is `profileable` by the shell for simpleperf.
- **What the strip draws and what the ticker samples are one rule** (`BarConfig.couldShow`, built on
  `shows`). Two separate predicates drifted when Show everything became the default: hidden items were
  drawn but never sampled, so a clock froze (`HiddenModeTest.everythingDrawnIsSampled`).
- **Time:** timers and keep awake store wall-clock times (they survive a restart) plus a `ClockAnchor`
  (elapsed realtime and the boot count), so a clock change moves the deadline with it instead of
  ending it early; the timer alarm is `ELAPSED_REALTIME_WAKEUP`, armed again in `Timers.init` (alarms
  don't survive a reboot). Live Update calls (`setRequestPromotedOngoing`, `setShortCriticalText`) are
  API 36.1: behind `Notify.liveUpdates`, since minSdk is 34.
- **Notification actions launch activities directly** (`PendingIntent.getActivity` for the chip's Join
  and Open). A broadcast receiver that then starts an activity is a notification trampoline, blocked
  for targetSdk 31+, and the chip shows exactly when the accessibility service (whose binding allows
  background starts) may be off.
- **Keyboard:** the "BentoBar menu" launcher entry (`ui/BarMenuActivity`, no window) opens the ‹ menu
  listing every item, focused. A focusable accessibility overlay does take key focus after the
  activity finishes (checked on the Acer). Users bind it in the Shortcut Helper; on the Acer, Action
  with B, C, E, F, P and U launch apps and can't be reassigned, and A, G, H, I, L, N, Q, S, V and W
  are system shortcuts; D, J, K, M, O, R, T, X, Y and Z are free.
- **Play Protect's live threat detection** (on-device, Android 17) flagged BentoBar on the Acer as
  "App displays over other apps" (`dumpsys safety_center`, source `GoogleAppProtectionService`,
  `SuspiciousAppIssueType`). It looks for accessibility services that keep imperceptible content on
  screen, and for screen capture. So: no invisible or 1×1 overlays (keep awake is
  FLAG_KEEP_SCREEN_ON on the visible strip, a screen wake lock only while the strip is hidden or
  empty, never on the lock screen; an empty strip's window is made invisible), status bar
  screenshots only on a change and only while the strip shows (never on a timer), every overlay visible and there because the user asked for it, and no adb hooks in release.
- StudioSnap's helper (`~/studiosnap/ss enable`) used to overwrite the whole
  `enabled_accessibility_services` list and switch BentoBar off; fixed there on 2026-09-28. Both
  helpers now add or remove only their own entry (short or full component form).
