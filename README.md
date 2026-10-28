**Note:** Built 100% with AI. I used AI to build this for me, and it is shared here in case it is useful to someone else.

# indexRouter

An Android companion for the **Pebble Index 01** ring. Double-click-hold the
ring, say what you want, and indexRouter files it where it belongs: a note in
your Obsidian vault, a task, a timer, a calendar event, or text for your
keyboard to type.

It works through the Pebble app's **webhook** feature. The Pebble app records
and transcribes; indexRouter receives the result on the phone itself, matches
the first words against a keyword routing table you edit, and acts on it.

**Everything stays on the phone.** The receiver binds `127.0.0.1` only, every
request must carry a shared secret, and nothing is sent anywhere else.

## What it can do with a recording

Each rule in the routing table pairs one or more keywords with an action:

| Action | What happens |
|---|---|
| **File** | A new note in a folder of your vault, with tags, Dataview-friendly frontmatter, and the recording saved alongside it. |
| **Task** | A Tasks-plugin task, one file per task, pulled into your daily note by a query rather than written into it — so a capture never races your vault's sync. |
| **Timer** | Starts a timer through the standard Android `SET_TIMER` intent, labelled with what you said. |
| **Calendar** | Opens a new event, either built from the phrase or handed to a calendar app that can parse "lunch tomorrow at noon" itself. |
| **Dictation** | Writes nothing at all. The text waits, briefly, for your keyboard's mic key to type it into whatever field has focus. |

Anything that matches no rule still becomes a note, with a notification, so a
recording is never lost silently.

### Also

- **Re-route** an item from the log if it landed in the wrong place. The file is
  moved and reformatted; the classifier is not second-guessed.
- **Keyword stripping**, per rule: "Task buy milk" files as *buy milk*.
- **Maps of Content** for each folder, generated on demand as live Dataview
  queries, with timestamped backups of anything they overwrite.
- **A routing log** of every recording and what happened to it. It keeps
  content for 2 hours by default, can be cleared, and notifications leave out
  what was said unless you turn detailed logging on.
- **A notification channel per route**, so each can have its own sound.
- **The routing table lives in your vault** as a Markdown file you can edit by
  hand, which also makes it the backup: reinstall, grant the folder, and the
  rules come back.

## Requirements

- Android 10 or later.
- A **Pebble Index 01** and the Pebble app, with its webhook feature.
- An **Obsidian vault** in ordinary shared storage, not Obsidian's app-private
  folder.
- Optional: a clock app that handles `SET_TIMER`, a calendar app, and a keyboard
  whose mic key can switch to another voice input method (HeliBoard does).

## Install

### With Obtainium (recommended)

Add this repository as an app source:

```
https://github.com/snrkl-labs/indexRouter
```

Each release carries exactly one APK, so no filter is needed. Obtainium will
then offer each new release as an update.

### By hand

Download the APK from the [latest release](https://github.com/snrkl-labs/indexRouter/releases/latest).

### Verifying the download

Releases are signed with this certificate:

```
SHA-256: 16:EB:02:C8:4A:DC:E3:14:81:D1:D1:B3:82:E9:D4:D6:EA:82:02:38:D9:D5:71:3B:9B:39:44:49:CA:8B:29:40
```

Check it with `apksigner verify --print-certs indexrouter-vX.Y.Z.apk`. Each
release also lists the APK's own SHA-256.

**Upgrading from a build before 0.17.0:** those were debug-signed, and Android
will not update across signing keys. Uninstall the old build first.

## Setup

1. **Grant vault access.** Open the app and grant the folder it writes into —
   `_RingRing` by default — plus any folders your file rules target. On
   GrapheneOS this is answered with Storage Scopes, and the app sees only the
   folders you grant.
2. **Generate a webhook secret** in the app. It is stored encrypted under an
   Android Keystore key, and shown so you can copy it.
3. **In the Pebble app:** Settings → Index → Webhooks. Set the trigger to
   double-click-hold, paste `http://127.0.0.1:8787/webhook` as the URL, and add
   an `Authorization` header with the secret as its value.
4. **On GrapheneOS**, grant both apps the Network permission: it governs
   loopback traffic too.
5. **For timers and calendar events to fire directly**, grant "Display over
   other apps". Without it, Android blocks the app starting them from the
   background and each one arrives as a notification to tap.
6. **For ring dictation**, enable indexRouter as an input method in Android's
   on-screen keyboard settings. Your keyboard's mic key then commits the latest
   dictation instead of listening.

Then edit the routing table to suit.

## Build from source

```bash
cd android
./gradlew assembleDebug          # debug build
./gradlew testDebugUnitTest      # unit tests, no device needed
./gradlew assembleRelease        # unsigned release APK
```

Identity is confined to two values, so a fork can rename the app in one small
change: `indexrouter.applicationId` in `android/gradle.properties` and
`app_name` in `android/app/src/main/res/values/strings.xml`.

## License

Copyright © snrkl-labs. Licensed under the
[GNU General Public License v3.0 or later](LICENSE).
