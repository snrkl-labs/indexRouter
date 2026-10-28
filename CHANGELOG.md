# Changelog

## v0.17.0 — 2026-10-28

The first public release: signed, published on GitHub, and installable and
updatable through Obtainium.

### Changed
- **The app is now called indexRouter.** The application ID is unchanged.
- **Releases are signed with a dedicated release key.** Earlier builds were
  debug-signed, so Android will not update one in place: uninstall it first.
  The routing table and settings come back from the vault config file when the
  vault folder is granted again; the folder grant, the webhook secret and the
  routing log do not survive the uninstall.
- **The voice input method takes the app's name** in the system keyboard list,
  rather than restating it in a label of its own.

### Added
- **Licensed GPL-3.0-or-later.**

## v0.16.0 — 2026-09-23

The two unnumbered backlog items: the keyword that aimed a phrase stops
following it around, and the routing log stops being the one place a transcript
lives forever.

### Added
- **A strip-keyword switch on every routing rule.** It lives in the expanded
  card and is hidden when the card is rolled up, alongside the note that it
  only strips where the keyword *opens* the phrase. On by default, because that
  is what every rule was already doing.
  - Written through to the config as `strip-keyword:` per rule, so a rule can be
    changed by hand as well as by tapping.
- **A keep-for setting for the routing log**, defaulting to **2 hours**. Set it
  to zero for "keep until cleared". Pruning runs where a recording arrives and
  where the app is opened — the two moments the log grows or is looked at —
  rather than on a timer, because a background job whose only purpose is
  deleting things is more machinery than this needs.
- **A clear button on the routing log**, with a confirmation that says what
  goes and what does not. It warns that a dictation waiting for the keyboard is
  held here and nowhere else, so clearing now leaves the mic key nothing to
  offer.
- **A marker row where content was cleared** — `----- cleared on <date> -----`.
  Deliberately identical whether the clearing was manual or the keep-for rule
  doing its work: the log should record that content went and when, not the
  user's habits. It is not re-routable, and holds nothing that was said.
- **A detailed-content-logging switch**, off by default. With it off, a
  notification says which route fired and where the note landed, but does not
  repeat back what was said. The alternative to quoting is not silence — it is
  simply that the words are not put where whoever picks the phone up reads them.

### Fixed
- **Keywords were still appearing in filed notes.** The body was stripped and
  the filename was not, because the name is derived from the transcript on a
  separate path. Both go through the same stripper now.
- **Clearing the log twice dated the second clearing to the first.** Clearing
  removed the content but spared every marker, so the stale marker became the
  newest row, and the guard that stops markers stacking on an empty log skipped
  the new one. The log then told you it had been cleared at a time that was no
  longer true. Old markers now go with the content they described, and one
  fresh marker is left in their place.

### Notes
- `LogKeeper` reads its retention through a lambda rather than holding a
  `SettingsStore`, so it carries no Android types and its rules are covered by
  plain JVM tests — seven of them, including the restamping case above. 143
  unit tests, 51 automated emulator cases, 0 failures, 0 skips.
- Two cases were added for stripping and four for clearing. Two harness bugs
  went with them: notifications were being matched by the run stamp in their
  text, which stops working the moment a notification can withhold what was
  said, and a UI case was tapping a fixed coordinate that a passing heads-up
  notification could sit over. Both are fixed in the harness.
- Database schema v5 (`strip-keyword`, and the log's `keywordStripped` and
  `isMarker`), migrated in place. Existing rules come through with stripping on.

## v0.15.0 — 2026-09-22

A Dictation route, ephemeral, and the only thing the keyboard sees.

### Added
- **A Dictation action type.** It writes **nothing** — no note, no task, and no
  recording either. The text is held in the routing log for the keyboard's mic
  key to collect, and that is all.
  - The recording is skipped deliberately. Every other route saves its audio
    first so a failed write is still recoverable by ear; keeping it here would
    leave an m4a in the vault for the one route whose point is leaving no trace.
    That trades "recoverable by ear" for "leaves nothing behind", which is what
    was asked for, and it is a one-line reversal.
  - No folder and no tags, so the collapsed card hides the folder row for it.
  - It notifies by default, unlike the other routes: it leaves nothing behind,
    so without one there is no sign it happened — and seeing what was actually
    transcribed *before* pasting it is worth more here than anywhere else.

### Changed
- **The keyboard's voice queue now sees dictations only.** It used to offer the
  most recent transcript of *any* recording, so a spoken shopping list or a
  private note could be committed into whatever field had focus — a work email,
  say. Only what was deliberately marked for typing is offered now. That is a
  safety property, not tidiness.

### Fixed
- **The mic key would have committed the keyword too.** The routing log keeps
  the raw transcript, because that is what traceability means, so "Dictation
  buy the tickets" would have gone into the field whole. The keyword is now
  stripped on the way out, using the keyword the log recorded as having fired.
  Found by testing the route end to end, not by reading it.

### Notes
- Keyword stripping moved into `KeywordStripper`, shared by the routing
  pipeline and the voice input method, and now has seven tests of its own —
  including that a recording which was *only* the keyword keeps its text rather
  than silently becoming an empty string.
- **Existing installs get nothing automatically.** The bundled defaults apply
  only to a fresh install or a reset, so add a Dictation rule and pick its
  keyword. A fresh install now ships one on `dictation`.
- 125 unit tests, 43 regression cases, none skipped.

## v0.14.0 — 2026-09-18

The calendar route hands the spoken phrase to a language parser
instead of a bare title.

### Added
- **A calendar mode.** `share-to` sends the whole phrase as plain text to one
  named app — prefilled `org.onekash.kashcal` — whose own parser turns it into a
  title, a date and a time. `insert` is the previous standard `ACTION_INSERT`,
  kept for anyone without that app. **`share-to` is the default**, and it falls
  back to `insert` when the named app is not installed, so nothing stops
  routing.
  - "Calendar lunch with Sinclair tomorrow at noon." now arrives as 🍽️ *lunch
    with Sinclair*, **Tomorrow 12:00**, instead of a title in the next free slot.
  - The package is **named in code rather than left to a chooser**, and that is a
    safety property rather than a convenience: `ACTION_SEND` `text/plain` is
    claimed by every messaging app, so an implicit send would put a share sheet
    of chat apps in front of a private voice note — one stray tap from sending a
    thought to a contact. Naming it also means no chooser at all, so nothing has
    to be set as a default by hand.
  - Settings shows whether the named app is actually installed, because a missing
    one means a silent fall back to the plain insert.

### Fixed
- **A trailing full stop was costing every spoken appointment its time.** The
  transcriber ends each phrase with one, and it defeats the receiving parser's
  *time* reading while leaving the date intact — measured: `…tomorrow at noon.`
  gives "Tomorrow, all day" with `noon.` left over as text, where
  `…tomorrow at noon` gives "Tomorrow, 12:00". So a lunch became an all-day
  event for one character nobody said. Trailing sentence punctuation is now
  removed before sending; punctuation inside the phrase is left alone.

### Notes
- The save is still a human tap. `ACTION_SEND` returns no result, and KashCal
  has no auto-save flag, no extras read from the incoming intent and no exported
  write path — checked in its source.
- 118 unit tests, 42 regression cases, none skipped. The new regression case
  pins the punctuation fix by asserting the event did **not** come out all-day.

## v0.13.0 — 2026-09-18

Collapsed rule cards down to two lines.

### Changed
- **A collapsed card is two lines.** Handle, type, priority and the chevron on
  the first; the keyword chips and the enable toggle sharing the second. Card
  pitch measured at **516px before, 369px after** — about a third shorter, so a
  handful of rules is a list again.
- **The folder row only exists where a folder means something**, and only when
  the card is open. Timer and calendar write no file and were showing a disabled
  empty control that said nothing and cost a line.
- **The keyword chips are fitted, not counted.** As many as actually fit up to
  the toggle, then a `+N` for the rest. Measured rather than a fixed three,
  because chip widths vary with the words in them — a fixed number both wasted
  the line on a rule with one keyword and wrapped on a rule with three long
  ones. The `+N` chip is measured for the count it will really show, since `+1`
  is narrower than `+12`.
- **The toggle moved to the chip row** when collapsed, which is what
  freed the folder row to disappear. Expanded, it has a labelled row of its own
  alongside **More…**.
- The priority field is narrower — it holds two digits nearly always.

### Fixed
- **Rule tags displayed without their hash until the rule was next saved.** They
  are canonicalised on the way out of the database as well as in, so rows
  written before tags had one form are shown and used canonically without anyone
  having to touch each rule.

### Notes
- 107 unit tests, 41 regression cases, none skipped.

## v0.12.0 — 2026-09-18

Decisions on tags and keyword stripping, and the calendar route finally
under test.

### Changed
- **A tag is now one thing: `#tag`, whether or not the hash was typed.** It was a
  coin toss before — the field took either form and said nothing about which was
  meant, so the same tag could sit in the config two ways. One definition
  (`routing.Tags`) decides it, and the field shows the canonical form.
- **Notes carry their tags twice, deliberately**: bare in the frontmatter, where
  a hash is not how Obsidian wants a tag, and **hashed inline in the body**,
  which is what makes them match a plain-text search as well as a `tag:` search.
  Frontmatter alone is findable one way only.
- **Timer and calendar routes strip the matched keyword**, as tasks already did.
  "Alarm for ten minutes" labels the timer *for ten minutes*; "Calendar lunch
  with Sinclair" titles the event *lunch with Sinclair*. **Notes keep theirs**,
  which also leaves the filename convention untouched.
- The routing log no longer records a bare `SUCCESS` for timer and calendar. It
  says *opened the handling app — not confirmed saved* or *notification posted —
  needs a tap to fire*, because for these two routes the log row is the only
  record there is, and firing an intent is all the app can honestly know.

### Fixed
- **The `+ add` chip showed two plus signs** — it carried an icon and the
  literal text "+ add".
- **The calendar intent set `data` and then `type`, which cleared the data.**
  `setData()` clears the type and `setType()` clears the data; it needs
  `setDataAndType`. Introduced and caught in the same session, by testing.

### Notes — the calendar route is now testable, which took some finding
- **The AOSP emulator's own Calendar app is broken**: it declares
  `com.android.calendar.event.EditEventActivity` and does not contain the class,
  so it crashes on launch and is force-finished. Every calendar attempt against
  it looks like a defect in this app and is not.
- Testing now uses **KashCal**, the calendar app on the target handset, with a
  local calendar created straight through the provider. Proven end to end: the route
  opens the editor pre-filled, the keyword is stripped from the title, the full
  transcript is in the description, and a saved event appears in the calendar.
- **A provider query is not a valid assertion.** KashCal keeps its own store, so
  `content://com.android.calendar/events` proves nothing in general — Etar, being
  AOSP-derived, does use the provider, which is what made this look assertable.
  The app's contract ends at handing a filled event to the calendar app; the save
  is a manual case.
- 107 unit tests, 41 regression cases, none skipped. Four new cases cover the
  calendar route, which until now had none at all.

## v0.11.0 — 2026-09-18

### Fixed
- **Timer and calendar notifications all arrived on one channel**, shared with
  failure alerts, so a sound could not be mapped per action for exactly the two
  routes whose notification is the one you have to act on. Their notification is
  the *tap-to-fire* one — the fallback for when Android blocks the app from
  starting an activity from the background — and it was hardcoded to the alerts
  channel. Each now has its own: **Timer: tap to fire** and **Calendar: tap to
  fire**, in the same Routes group as the rest.
  - Deliberately *separate* channels from the per-route confirmations rather
    than reusing them, because these are functional, not informational: muting a
    "tell me when this route fires" preference must never take away the tap that
    actually starts the timer. So they are always on, and the description says
    why.

### Added
- **Drag handles on the rule cards.** Long-press a handle and drag; on release
  the priorities renumber to 10, 20, 30… in the order the cards now sit. Gaps of
  ten on purpose — the numbers exist so the routing table can be reordered by
  hand in Obsidian, and consecutive numbers would leave no room to slot a rule
  between two others. The handle is just the convenient way to do it on the
  phone.
  - A handle rather than a long-press on the card, because the card is covered
    in controls now: dropdowns, a switch and a swipe gesture that a whole-card
    long-press would fight.
  - Positions come from the list's own layout rather than an assumed row height.
    The cards genuinely differ — an expanded one is about three times a
    collapsed one — so arithmetic on a fixed size would drop a card in the wrong
    place exactly when the list was most interesting.
  - One write per drag, not one per card crossed.

### Changed
- **The settings screen uses the rules screen's card idiom**: grouped cards, a
  bold `Label:` and the value in a white pill. The vault takes the outlined card
  the rules screen uses for the things that are not choices — it is granted, not
  configured. The card vocabulary is now shared code rather than a private copy
  per screen, which is how two screens drift into looking almost the same.

### Notes
- 98 unit tests, 37 regression cases, none skipped. Two new cases pin the
  notification fix: the tap-to-fire lands on the route's own channel, and
  nothing lands on the shared alerts channel.

## v0.10.0 — 2026-09-18

The routing rules screen rebuilt to a new mock-up.

### Changed
- **Rules are cards, edited in place.** Type, priority, folder and the enable
  switch are on the card and are also the controls that change it; there is no
  editor dialog and no Save, because every edit writes through to the vault as
  it is made. Expanding a card opens the keyword panel, where the whole set is
  visible and editable rather than counted; collapsed, it shows three chips and
  a `+N`.
- **The folder is a dropdown** of the folders already under the base folder,
  with **Choose / create…** handing over to the system picker. It shows the
  resolved path — `_RingRing/blogPostIdeas` — while still storing a single
  name, which is what keeps the old `_RingRing/_RingRing/` nesting bug
  impossible. *(Display the path, store the segment.)*
- **The default route and the recordings folder are visually distinct** — white
  with a dark border, against the rules' filled cards — because neither is a
  rule: one is what happens when nothing matched, the other happens whatever
  matched, so neither has a keyword, a priority or a place in the order. The
  recordings folder is now editable here as well as in settings.
- **Swipe a card sideways to reveal Delete.** Hand-rolled rather than using the
  Material swipe-to-dismiss container, because dismissal is the wrong model: the
  card stays put with the action revealed until it is tapped or the swipe is
  undone. A container that deleted on a completed swipe would lose a rule to a
  stray gesture, and a rule is not cheap to retype.
- **A disabled rule dims whole**, chips included — it is still in the chain and
  still in its place in the order, and a half-dimmed card reads as a rendering
  fault.
- The add button is a centred floating button, as drawn.

### Notes
- Match type, map inclusion and the per-field explanations sit behind **More…**
  rather than on the card. A card has less room than a dialog, not more, so
  inlining everything would have cost the guidance added in v0.6.0 for exactly
  that reason.
- The settings screen keeps its current shape this pass:
  rules first, on the handset, then settings once the idiom has proven itself.
- 98 unit tests, 36 regression cases, none skipped. Section B of the regression
  checklist is rewritten for the card screen, including the two cases that
  matter most: every edit writes through, and a swipe never deletes on its own.
- The harness now waits for the receiver to be *stably* up before it starts.
  Reinstalling kills it and `MY_PACKAGE_REPLACED` restarts it a moment later, so
  a run begun straight after an install could answer `/health` and still refuse
  the first webhook at the socket — which surfaced as three unrelated failures
  and cost a re-run to identify.

## v0.9.1 — 2026-09-18

Two defects behind "the settings pages do not operate", both measured before
being touched. The settings restyle is not in this release.

### Fixed
- **Nothing re-read its state when a screen came back to the foreground.** The
  view-based screens refreshed in `onResume`; the Compose rebuild used a
  one-shot effect instead, and losing that was a regression. It matters more in
  this app than in most, because **almost every fix it asks for happens in
  Android's own settings** — Storage Scopes, all-files access, "Display over
  other apps", enabling the input method. So the shape of every fix is "leave,
  change something, come back", and the app then reported the state from before
  the fix. Enabling the voice input method and returning to a screen still
  saying "✗ Not enabled" is exactly that, and is a large part of why the
  dictation feature was not testing well.
- **Every settings change cost 440ms of blocked screen**: 198ms writing the
  whole config to the vault, then 242ms of which 220ms re-probed every vault
  folder — and proving a folder is writable means writing and deleting a file in
  it. Neither has anything to do with a notification toggle; five switches was
  over two seconds of unresponsive screen. The probe now runs only when
  something that changes folder reachability changes, and the vault mirror runs
  in the background instead of being read back before the switch is allowed to
  move. A toggle is now immediate.

### Added
- Timing instrumentation on the settings screen, under the same
  `IndexRouterPerf` tag as the rest — this is the second time a screen has been
  slow for a reason nobody could see without it.

## v0.9.0 — 2026-09-18

The keyboard's mic key now returns the ring's last transcription.

### Added
- **A voice input method**, so tapping the mic key on the keyboard commits what
  was last dictated to the ring instead of listening for anything. Off until it
  is enabled once in Android's settings, and configurable: how many recent
  transcriptions to keep, and how old the newest may be and still be committed.
- **A reachability check in settings**, because this feature has three separate
  ways to be silently unreachable and they look identical from outside — the key
  just does nothing. It asks the system the same question the keyboard asks and
  says which of the three applies: not enabled, enabled but not listed as a
  voice input method, or listed behind another one that wins.

### Notes
- **The mechanism is neither of the two the issue expected**, and reading
  HeliBoard's source rather than assuming is what found it. Its mic key does not
  fire `ACTION_RECOGNIZE_SPEECH` and does not bind a `RecognitionService`:
  `KeyCode.VOICE_INPUT` calls `switchToShortcutIme()`, which reads
  `InputMethodManager.shortcutInputMethodsAndSubtypes` and switches the active
  input method to an enabled IME carrying a subtype with `mode="voice"`.
- That makes the feature **cheaper and less invasive than feared**. It is not
  the system recogniser, so ordinary voice typing everywhere else is untouched;
  no chooser is needed, and no fork of the keyboard. The IME hands the keyboard
  straight back once it has committed, so the switch is invisible in use.
- The age cutoff is doing real work rather than being a nicety: without it,
  tapping the mic key hours later commits that morning's words into whatever
  field happens to be focused. Eight unit tests cover it, including the
  inclusive boundary and a clock that has gone backwards.
- **Refusals hand the keyboard back too**, after long enough to read the reason.
  This is not a keyboard, so leaving it up would strand whoever tapped the key
  with no way to type — found while testing the refusal path, not in review.
- 98 unit tests and 36 regression cases, none skipped.

### Still needs the handset
- **HeliBoard actually switching to it.** Everything above was proven on the
  emulator — the IME registers, the system lists it as a voice shortcut, it
  commits the last transcription into a focused field, and it refuses a stale
  one — but HeliBoard is not installed there, so the final link is the one thing
  not yet exercised.
- Transcribo, if still installed and enabled, is a competitor for the same key.
  The settings check names it if it is winning.

## v0.8.0 — 2026-09-18

The last three backlog requests, which turned out to be one piece of work.

### Changed
- **The interface is Compose and Material 3.** Three activities became one, with
  a navigation graph and a screen-and-view-model per feature, following the
  pattern already established in `NFCBusinessCard`. Dynamic colour is used where
  the platform offers it — always, on the target Pixel — with a fallback palette
  seeded from the app's own launcher icon, so it looks like itself either way.
- **There is a settings screen**, which there was not before: settings lived as
  dialogs behind ten buttons, which made the first screen a menu rather than an
  answer to "is it working?". Everything that configures rather than reports has
  moved there, grouped by what it affects, with every current value visible
  without opening anything — a setting whose value is only discoverable by
  tapping is half a setting. The status screen is down to six actions, and its
  card colours only the lines that are wrong.
- **Several settings became reachable that never were**, because a settings
  screen gave them somewhere to live: the reserved folder names, date-filing
  depth, task tags, the created-date signifier, the server port and the map
  backup retention were all config-file-only before.
- **Keywords are chips.** A comma or the keyboard's Done commits one, each has
  its own remove, and text left uncommitted when the editor is saved is taken as
  a final keyword rather than dropped silently.

### Fixed
- **A tapped route notification did not open the log when the app was already
  running.** The screen to open was read in `onCreate`, which a running activity
  does not re-run, so the tap landed on whatever was already open. It is now
  handled on the new intent as well, and navigated on top of the main screen so
  Back returns to the app rather than leaving it.

### Notes
- The view-based layouts, the Material Components views library and the
  AppCompat dependency are all gone. `Theme.App` now only has to get the window
  right before the first frame.
- 90 unit tests and 36 regression cases, none skipped — the routing pipeline is
  untouched by this change, and the regression run against the Compose build
  proves it.

## v0.7.0 — 2026-09-18

Three of the backlog requests from the issue log, and one defect found while
proving them.

### Added
- **A notification channel per route**, and a setting for which routes announce
  themselves. A sound is a property of a channel on Android 8+ and cannot be set
  per notification, so "a different alert per route" is only expressible as a
  channel per route — the sound for each is chosen in Android's own notification
  settings, and the app decides only whether a route speaks at all. The routes
  that succeed default to **off**: a notification per voice note undoes the point
  of an app whose value is that a note files itself without the phone being
  touched. No match stays on, because it is waiting for a decision. Failures are
  not a preference and are not listed.
- **An optional stream of processing milestones**, off by default: received,
  classified, recording saved, dispatched, logged, plus rejected webhooks — which
  never reach the pipeline and so could not be reported anywhere before. Its own
  low-importance channel, grouped so a burst collapses rather than buries. On a
  handset there is no logcat to watch, which is the whole reason for it.

### Changed
- **The config moved out of the frontmatter into a fenced `yaml` block** in the
  note body, and the format version is now `00.04.00` rather than a bare integer.
  Obsidian renders frontmatter through its properties editor — a table of typed
  rows, no comments, awkward for nested lists — which is the least hand-editable
  surface it has, and hand-editing this file is the entire reason it lives in the
  vault. A fenced block is plain text. **An existing file is read and converted
  on the next write; nobody re-enters their rules.**
- Rules no longer carry a `keyword: null` line each. It is compatibility
  scaffolding for pre-`00.03.00` files, still read, no longer written.

### Fixed
- **The milestone stream silenced everything else, including failure alerts.**
  Android refuses every notification from an app once 50 are live, and the
  refusal is a line in logcat — not an exception, not a return value. Ids came
  from counters that only ever climbed, so four recordings with the stream on was
  enough to reach the cap, after which a route that had worked perfectly well was
  thrown away by the framework. Every category now draws ids from a fixed-size
  ring and replaces its own oldest: 46 slots in total against a cap of 50.
  Verified with twelve recordings and the stream on — 28 live, zero refusals,
  where the old build would have jammed. The routing log row is written either
  way, so nothing was ever actually lost; what was at risk was being *told*.
- Two concurrent recordings could take the same `PendingIntent` request code, so
  tapping the first tap-to-start notification would fire the second one's timer.

### Notes
- Three cases in the regression harness were asserting nothing: `posted_on`
  filtered `dumpsys notification` through a section header Android does not
  print, so it counted zero every time and read as "the disabled routes stayed
  silent". Same mistake as the hardcoded folder names before it — assuming the
  shape of the thing being read. Fixed, and the counts are now baselined before
  each run so they measure the run rather than the shade.
- 90 unit tests, 36 regression cases, none skipped.

## v0.6.0 — 2026-08-22

### Fixed
- **Tasks kept going to `tasks` after the folders were renamed.** Not a hardcoded
  string: the old names lived in the install's own settings and config file, and
  nothing distinguished an old *default* from a deliberate *choice*. A one-time
  upgrade renames the three reserved folders where they still match the old
  defaults, rewrites the config so a reload cannot undo it, and says so.
  Anything already filed stays where it is.
- **The interface stalled while recordings were processed.** Measured before
  touching anything: handling a recording cost 28–67ms, while rendering the
  status screen cost **1091ms** — 453ms probing folder access, 346ms re-reading
  the rules, both on the same dispatcher the recordings were using. Rules are now
  cached and invalidated on write; folder access is cached briefly and refreshed
  off the render path. Render fell to 37–107ms, and held at 50ms during a burst
  of six concurrent recordings.

### Added
- **An (i) beside every field in the rule editor**, opening the real explanation
  — including that keywords are comma-separated, which was the one thing a user
  needed to know and the field was too narrow to show.
- **Timing instrumentation**, kept rather than removed: stage timings under the
  `IndexRouterPerf` log tag, and each recording's total duration on its routing
  log row, so a future stall can be quantified after the fact.

### Notes
- The folder-access probe still writes and deletes a file to prove writability —
  that is the only honest check, since Android answers `canWrite()` from metadata
  that says yes while the write fails. It is now cached for 30 seconds rather
  than repeated on every render: long enough to stop the stall, short enough that
  a revoked Storage Scope still surfaces while the app is open.

## v0.5.0 — 2026-08-21

Builds out the twelve items from the first real-use testing session.

### Added
- **A rule can carry several keywords** — "blog idea", "blog post idea",
  "content idea" are one rule, not three. Which one fired is recorded, and the
  phrase actually spoken is the one stripped from a task.
- **The status bar icon**, hand-converted to a vector drawable and verified
  pixel-for-pixel against the source.
- **Dated filing** for the inbox and recordings (`2026/08/21`), configurable
  none/month/day, so neither folder becomes a flat wall of files.
- **Query samples appear when a folder is created**, carry a warning that they
  are generated, and show the queries running as well as quoted.
- **Generated maps carry `displayIcon`** for the reserved folders.

### Changed
- **The base folder is picked, not typed**, using the system chooser — which can
  create the folder, since on a first setup it usually does not exist.
- **Reserved folders are `_Tasks`, `_Inbox`, `_Audio`**, sorting above your own.
- **Task rules carry their own tags**, so "work task" and "home task" can differ,
  falling back to the setting when a rule defines none.
- **The rules list reads as the whole chain**: rules by priority, then the
  default, then recordings, marked as always running.
- **The map rebuild is one run with two options**, rather than two buttons.
- The editor's enabled tickbox is now the switch the list already used.

### Fixed
- A folder-created hook derived its name from the written path, which under
  dated filing produced a folder called `21`.

### Notes
- Database migrates to version 3 (the keyword column becomes a set; the unique
  index goes, since uniqueness now spans every rule's keywords and is checked
  where it can be reported rather than thrown).
- Config is version 3, but **a v2 file is read rather than refused** — a single
  keyword widens to a one-item list, so nobody rebuilds their rules again.
- 68 unit tests, 28 regression cases, none skipped.

## v0.4.1 — 2026-08-21

### Fixed
- **Renaming a rule onto an existing keyword crashed the app.** Two rules for
  one word are meaningless — the first match wins — so the keyword is uniquely
  indexed, but that index was enforced only by SQLite, which enforces by
  throwing. The clash is now refused in the dialog, which stays open with the
  edit intact.

### Changed
- **The base folder is asked for on first setup**, right after the vault is
  granted, instead of silently defaulting to a name guessed at someone else's
  vault. It is editable afterwards from the main screen.
- **Only three routes ship as defaults**: `task`, `calendar` and `alarm`, plus
  the fallback. Everything else — where blog ideas or shopping lists go — is
  yours to define, since only you know the folders.
- **Fixed folders are shown where they apply.** A task rule displays
  `${baseDir}/tasks` in its folder field rather than an empty disabled box, and
  the default route shows where unmatched recordings land.
- Removed a stale line claiming tasks go into the daily note. They have not
  since v0.2.0.

## v0.4.0 — 2026-08-21

First build with the real icon, and the first cut behind a full regression pass.

### Added
- **The launcher icon.** Adaptive background, foreground and monochrome layers
  at all five densities, generated from a single layered SVG by
  a split-and-render script. Verified in a
  real launcher rather than only as PNGs.
- **A guide-leak check.** `check-icon-guides.py` fails the build if the
  designer's magenta guides survive into a rendered layer — stripping by group
  name is only as good as the names.
- **A regression harness and checklist.** The harness asserts 24
  cases and exits non-zero; the checklist records why each
  exists and which came from something actually breaking.

### Fixed
- **The app reported a healthy vault while every note failed to save.** The
  access check trusted `isDirectory()` and `canWrite()`, which Android answers
  from file metadata — they succeed even when `open()` returns EPERM for an app
  with no storage permission. It now proves writability by writing a probe file,
  so a revoked permission or a missing Storage Scope is reported plainly instead
  of being discovered as missing notes.

### Notes
- Fifteen tests had never run: two test files sat at a stray path, committed but
  outside the source set. Recovered; the suite is 66 tests, all green, and the
  checklist now says to check which classes ran rather than trusting the count.

## v0.3.2 — 2026-08-21

### Changed
- **The daily-note task query is scoped to the app's folder, not matched by a
  tag.** Daily notes written by v0.1.x still hold inline task lines carrying
  `#index`, so a tag-matched query listed those a second time — in the very
  section meant to replace them. A folder-scoped query cannot double-count,
  because the app no longer writes anywhere near a daily note.
- **The app now generates that block itself.** *Rebuild MoC tree → Write query
  files too* writes `tasks queries.md` into the task folder containing the query
  with the current base folder already filled in. A baked-in path would otherwise
  go stale and fail silently the moment `base-dir` changed, and the app cannot
  edit a template that lives outside its own tree — so it hands you a correct one
  to paste instead.

## v0.3.1 — 2026-08-21

### Fixed
- **A config file from before the folder change was replaced silently.** It was
  correctly refused and backed up, but nothing said so — the notification the
  v0.2.0 notes described was never actually raised, and granting the vault
  rewrote the file with no message. Both now report what happened: an alert
  naming the file, and a message on the grant itself. A capture tool quietly
  swapping your configuration is exactly the failure this app is built to avoid.

## v0.3.0 — 2026-08-21

Re-route and Map of Content.

### Added
- **Re-route.** Tap any entry in the routing log to send it somewhere else. The
  classifier is deliberately not re-run — the manual choice is the authority —
  and the file is moved and reformatted in place: a note becoming a task gains a
  task line, and the recording embed is left alone since it is a name-based
  wikilink that survives the move.
- **Manual corrections are recorded, not hidden.** `routed-by` becomes `manual`
  while `rerouted-from` preserves whatever the classifier originally chose, and
  the log row is flagged rather than overwritten. That is what makes "what does
  the classifier keep getting wrong" answerable later.
- **Rebuild MoC tree.** One button, one pass: an index note for every included
  folder plus an index-of-indexes at the base, with each child linking back up.
  Listings are Dataview queries, so they stay correct as recordings arrive
  without the app rewriting anything.
- **Timestamped MoC backups**, kept in `.backups` where Obsidian ignores them,
  with per-map retention (default 5, set in the rebuild dialog) pruning oldest
  first.
- **Query pro-forma export**: ready-to-paste Dataview blocks written beside the
  content they query, so a view can be dropped into any note later without the
  app being open.

### Notes
- Timer and calendar entries cannot be re-routed: they write no file, so there
  is nothing to move, and re-firing one hours later would be worse than useless.
- `Audio/` is excluded from the map by design — it holds recordings referenced
  from notes, not content anyone browses through an index.

## v0.2.0 — 2026-08-21

Folder schema, tasks as files, rule-list rework.

### Changed
- **One root for everything.** Every path the app writes now resolves under a
  single `base-dir`, with reserved `tasks/`, `inbox/` and `Audio/` beneath it. A
  rule's `folder` is a single name under that root rather than an arbitrary vault
  path, so a rule can no longer point outside the app's own tree.
- **Tasks are standalone files, and the daily note is never touched again.**
  Inserting a line into the live daily note lost races against Obsidian mobile
  sync — that file is edited by other devices and sync only runs while Obsidian
  is open. Each task is now its own file under `${baseDir}/tasks`: a single
  atomic write with nothing to merge against.
- **The daily note pulls tasks in with a query** instead. Added to the vault's
  daily-note template, matching on the `#index` tag rather than a folder path, so
  moving `base-dir` cannot silently empty the section.
- **Rule list**: enable/disable is a switch on each row, rules display in the
  order the classifier evaluates them (priority, then insertion), and the default
  route is pinned last as "what happens if nothing above matched".

### Added
- **Reset config to defaults** (long-press Export), since deleting the vault
  config file alone would not clear the rules held on the phone.
- Config files now carry `version: 2`. A v1 file is refused with a notification
  rather than reinterpreted, because its `folder` values were vault-root paths
  whose meaning has changed — quietly re-reading them would have written to the
  wrong place, or nested a folder inside itself.

### Fixed
- The vault-grant check demanded a folder that the app itself creates, so it
  rejected every clean install into an empty vault.

### Notes
- Database schema moves to version 2 (two added columns, for the re-route and
  MoC work landing next). Migration is written, not destructive — the routing log
  survives the upgrade. Schema JSON is now version-controlled.

## v0.1.3 — 2026-08-18

Rule editor rework, and file-access guidance corrected.

### Changed
- **Every field carries a label** beside it instead of relying on hint text,
  which vanished the moment a value was present and made an existing rule
  unreadable.
- **Fields that do not apply are disabled**, not left editable and ignored: a
  timer or calendar rule writes nothing to the vault, so folder and tags grey
  out; a task rule takes its folder and tags from settings.
- **Type names are Title Case** — File, Task, Timer, Calendar. The stored values
  stay lowercase, so hand-edited config files are unaffected.
- **The dialog is titled for what it does** — Add rule / Edit rule / Default
  route — rather than "Save".
- **The rules list opens with the Default route**, where a recording goes when
  nothing matches. It has no keyword or priority, only a folder. Stored as a
  setting rather than a keyword-less rule so the classifier cannot match it.
- **The folder field has a picker.** It launches the system folder chooser and
  stores the path vault-relative; a folder outside the vault is refused rather
  than saved as something that could never be written to.
- **File access now points at Storage Scopes first.** Storage Scopes is only
  offered while the app holds no storage permission, so the old flow — a button
  straight to "All files access" — was steering toward the one grant that
  removes the option.

### Removed
- The unsigned release APK is no longer built or published. Nothing installs it,
  so it was a wasted build. Debug builds only until there is a release to ship.

## v0.1.2 — 2026-08-18

Vault access reworked around GrapheneOS Storage Scopes.

### Changed
- **Vault access is now filesystem paths, not a SAF tree grant.** The folder
  picker is kept only as a way to point at the vault visually; what gets stored
  is the path. Storage Scopes then confines the app to the folders granted — the
  app's own `_RingRing`, plus whichever folders the routing rules target. SAF
  remains as a fallback for a vault that is not on primary storage.
- **Per-folder access is reported individually.** Vault access lists every folder
  the current rules need and why — `_RingRing/Audio` for recordings, `_Daily
  Notes` for tasks, each file rule's own folder — and marks the ones without
  access. Saving a rule whose folder is unreachable says so on the spot rather
  than failing at 3am. Failure notifications name the folder to add.

### Fixed
- **Granting a subfolder silently nested everything inside it.** Granting
  `_RingRing` made the app write to `_RingRing/_RingRing/Inbox`, and left
  `_Daily Notes` unreachable so tasks could never be filed. The grant is now
  checked before anything is written, with both problems named.

## v0.1.1 — 2026-08-18

First build. Receives Pebble Index 01 voice-note webhooks on loopback, classifies
the transcript against a keyword routing table, and files the result into the
Obsidian vault, a timer, or the calendar.

### Added
- **Webhook receiver** — Ktor CIO server bound to `127.0.0.1` inside a
  `specialUse` foreground service. Shared-secret auth on every request; loopback
  is not a trust boundary on a shared device. Parses the captured multipart
  payload shape (`client`, `recordedAt`, `transcription`, `audio`).
- **Routing** — keyword classifier with explicit priority, so a sentence carrying
  two keywords always routes the same way. Whole-word matching by default;
  "multitasking" does not fire the "task" rule.
- **Actions** — vault note with the Dataview frontmatter contract; task inserted
  under the daily note's own heading; `ACTION_SET_TIMER` at Alarm Rock;
  `ACTION_INSERT` for calendar; and a catch-all that files to the inbox and
  notifies. Recordings are saved to the vault alongside the note.
- **Config** — Room is authoritative at runtime, written through to a Markdown
  file in the vault that can be hand-edited in Obsidian, read back only on reload
  or app-open. That file doubles as the config backup across a reinstall. The
  previous version is kept in a `.backups` dot-folder Obsidian ignores.
- **Traceability** — one log row per webhook whatever the outcome, with an
  in-app viewer. For timer and calendar routes this is the only record the note
  ever existed.
- **Pebble app setup** — webhook URL and Authorization Header shown together,
  each copyable, secret generated on first open.

### Notes
- Identity is confined to `indexrouter.applicationId` and `app_name`; the app is
  still working-titled and a rename is those two values.
- Background activity starts are blocked since Android 10 and a foreground
  service does not exempt them, so timer and calendar either fire under the
  overlay exemption or degrade to a one-tap notification. Never a silent drop.
- Emulator-verified: auth, multipart, all routes, vault writes through a real SAF
  grant, config hand-edit and reload. Not yet device-verified: real webhook
  delivery from the Pebble app, GrapheneOS Network permission and auto-revoke,
  and Alarm Rock's headless timer behaviour.
