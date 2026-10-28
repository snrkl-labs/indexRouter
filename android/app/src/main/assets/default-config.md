---
version: "00.04.00"
---

# Routing config — bundled defaults

Used only when the vault config file cannot be read at all, or after a reset. The
app raises a notification when it falls back here rather than degrading quietly.

Only the routes that need no folder of their own are shipped as defaults:
`task`, `dictation`, `calendar` and `alarm`. A **dictation** writes nothing at
all — not a note, not even the recording — and is the only route the keyboard's
mic key can reach, so what gets typed into a text field is always something you
marked for typing. Everything else — where blog ideas, shopping
lists and the rest go — is yours to define, because only you know the folders.

```yaml
settings:
  base-dir: "_RingRing"
  task-dir: "_Tasks"
  fallback-dir: "_Inbox"
  audio-dir: "_Audio"
  task-tags: ["#task", "#index"]
  task-created-date: true
  server-port: 8787
  moc-backup-retention: 5
  date-filing: "day"
  notifications:
    file: false
    task: false
    timer: false
    calendar: false
    fallback: true
    dictation: true
    debug-stream: false
routing:
  - keywords: ["task"]
    action: "task"
    folder: null
    tags: []
    match-type: "contains_word"
    priority: 10
    enabled: true
    include-in-moc: true
  - keywords: ["calendar"]
    action: "calendar"
    folder: null
    tags: []
    match-type: "contains_word"
    priority: 20
    enabled: true
    include-in-moc: false
  - keywords: ["dictation"]
    action: "dictation"
    folder: null
    tags: []
    match-type: "contains_word"
    priority: 15
    enabled: true
    include-in-moc: false
  - keywords: ["alarm"]
    action: "timer"
    folder: null
    tags: []
    match-type: "contains_word"
    priority: 30
    enabled: true
    include-in-moc: false
```

Every `folder` is a single name resolved under `base-dir`. `task-dir` and
`fallback-dir` are reserved and always exist; the fallback catches whatever
matches no rule and is configured on the Default route, not as a rule.

`notifications` decides which routes announce themselves when they fire. Each
route has its own notification channel, so the *sound* is chosen in Android's
notification settings for this app rather than here. Failures always notify and
are deliberately not listed.
