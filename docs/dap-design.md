# DAP design notes — current state and remaining work

Working design document. The design method: the early iPod's economy of
function — reach any song fast through shallow metadata lists, now-playing
as the heart, impossible to get lost, glanceable status, set-and-forget
modes — mapped onto this hardware and Sony's real firmware data, in the
launcher's 1999 LCD design language. Nothing on the LCD is ever faked;
constraints live in CLAUDE.md.

## Current state

- **Home = now-playing.** Real track/artist/quality/year/HR/time from the
  firmware player's binder joined to the Genie database, true
  PLAY/PAUSE/STOP (the pause glyph is two drawn rectangles — no 4.1 font
  gamble), the cover or the generated plasma in the art panel, battery/
  clock/output-route status, and a gliding title line for long titles.
- **SETTINGS** behind a drawn gear on the program index header — the
  interface's one sanctioned icon. Three numbered rows, each tap cycling
  a persisted value (`LauncherSettings`), every screen reconciling on
  resume (`Skin` re-tints the inflated views at runtime — API 16
  drawables cannot take theme attributes): **STYLE** (LCD or the
  amber-on-black TUBE AMP), **ARTWORK STYLE** (GREYSCALE / GAMEBOY /
  FULL COLOR), and **INFO SCREEN** (SPECTRUM or CLOCK — and CLOCK is a
  real off-switch for the whole analysis pipeline: no requests, a
  running pass yields at its next block, flipping back resumes the
  stored envelope — verified live on the device).
- **MUSIC LIBRARY** is launcher-native: PLAYLISTS / ARTISTS / ALBUMS /
  SONGS over `LibraryStore`'s cached snapshot (instant opens; id
  fingerprint freshness that rides out Sony's scan windows; LCD progress
  overlay only on a first build), HR chips on all-hi-res albums, and the
  three-tier playback handoff (binder queue → view-file → app-only).
  **The binder tier is device-proven**: a registered-client handshake
  plus platform-MediaStore queues — album and songs taps play on the
  firmware engine cued at the tapped row with no UI switch, and the
  now-playing poll maps the reported platform ids back to Genie rows for
  the glass. A box set that Genie collapsed into one album — Genie
  buckets by the album-name string and has no disc column — is shown
  disc-major and numbered `1-01` … `3-15` whenever the disc numbers can
  really be read (`AlbumDiscMap`, from Genie's own encoded track number
  or one two-query platform join); when they cannot, the level is left
  exactly as Genie hands it over rather than half-labelled. The browser
  remembers its whole navigation stack (level,
  scroll, search filter) across visits and style changes, re-resolved
  against the current library so a vanished album can never strand it.
  Long-press on any track opens the LCD task menu: **ADD TO NOW
  PLAYING** appends to the playing queue via `NowPlayingQueue` — over
  the launcher's own platform playlist, hitchless in-place when that
  playlist already is the queue (device-proven; the engine integrates
  pure inserts), one honest reopen-with-seek otherwise — and **ADD TO
  PLAYLIST** opens the playlists screen as a picker.
- **Background library refresh** keeps the cache fresh without opening
  the browser: a manifest receiver for the storage/media-scan broadcasts
  and `BOOT_COMPLETED` starts a short-lived, self-stopping service —
  fingerprint check → rebuild → persist, on a bounded doubling backoff
  while Sony's own rescan leaves the provider empty — so the browser's
  own "UPDATING LIBRARY" overlay effectively never shows. After a USB
  storage session the same pass reports on screen instead (next item).
- **USB storage gets its own glass.** Plugging the cable in makes Sony's
  Automount share the music volume over USB mass storage, and the player
  cannot play until that ends. `StorageState` is the one shared answer,
  and every screen shows it through one `StoragePanel`: home re-cuts its
  glass — the status row and PROGRAM INDEX latch stay, the track lines,
  art panel and soft-keys give way — and the library and playlists put
  up a blocking overlay that back leaves. **USB STORAGE** carries the
  panel's one key, **USB STORAGE OFF**, which ends sharing through the
  hidden `StorageManager.disableUsbMassStorage()` — possible because
  `MOUNT_UNMOUNT_FILESYSTEMS` is protection level *dangerous* on this
  firmware (the key reads NOT AVAILABLE if it is ever refused). Then
  **READING STORAGE** holds, static, while Sony reindexes; **UPDATING
  LIBRARY** shows real row counts while the cache rebuilds, when it
  needs to; and the normal screens return, the library re-resolving its
  saved place against the new snapshot. While the volume is unmounted,
  playback handoff, queue-next and playlist edits refuse before any
  binder call, and the analyzer neither starts nor marks tracks failed —
  it closes its file on `MEDIA_EJECT`, because the forced unmount kills
  any process holding a file open there. Power-on rescans stay silent.
  Broadcast order and timings: firmware-findings.md "USB mass storage".
- **PLAYLISTS is launcher-owned.** The soft-key opens the launcher's own
  screen; Sony's dashboard is not involved anywhere. Launcher playlists
  **are** platform MediaStore playlists (`PlaylistStore`), which makes
  each list simultaneously a stored list and a real engine queue:
  tapping any row plays the list from there through the proven handoff.
  Create, rename, delete, add from the library, remove by long-press.
  Sony's Genie lists sit below, read-only and marked so; playing one
  mirrors it into the reserved queue list first (Genie rows can never be
  a queue descriptor). An edit to a list the engine is currently playing
  is checked, not assumed: `PlaylistEditor` asks the engine read-only
  whether it integrated the row (`QueueOracle`) and reopens the list
  cued at the playing track when it did not — the single-row *delete*
  answer is the one open device question. Deleting a whole playing list
  is refused. Honesty rules throughout: unreadable member rows show
  dark, unmatched tracks refuse rather than playing something else.
- **The spectrum window is live** the honest way — live capture is
  proven impossible on this hardware (S-Master; firmware-findings.md),
  so each track is analyzed once offline on its first play
  (lowest-priority `SpectrumAnalyzerService`; no bulk pre-analysis, so
  the ~20 MB LRU cache follows the real rotation) into an 8-band ×
  16-level × 12 fps envelope that `SpectrumController` replays against
  the player's own clock — only while PLAY, resumed, and the frame's
  analysis exists; interrupted analysis resumes where it stopped. The
  analyzer decodes the whole library: the formats the platform stack
  cannot read — ALAC at any rate, FLAC above 48 kHz — route to the
  launcher's own decoders (`AlacDecoder`, `FlacDecoder` over the
  `PcmDecoder` seam), original code from the published format
  specifications, verified bit-exact against a reference decoder, fast
  enough on the device that analysis outruns the playhead even at
  192 kHz (measured figures in firmware-findings.md).
- **PROGRAM INDEX** lists every app with search and carries the gear
  into SETTINGS.

## Remaining work

- **Shuffle-all (stretch).** `setShuffleMode` is txn 18
  (decompile-verified); build only if it proves a clean call on device.
- **Small follow-ons, unscheduled:** repeat/shuffle annunciators
  (PlayInfo slots 15/16), active-EQ name (`getAudioOutputEffect` txn 33),
  synced lyrics (Genie `synclyric`), WM-PORT dock detection for the
  footer (audioeffect route broadcasts), a period-correct launcher icon,
  event-driven now-playing via `addListener` instead of the poll (pairs
  naturally with the analyzer service).

## Deliberately cut

No games, extras, or calendars. No on-launcher EQ — the SOUND path owns
that. No volume UI — the ZX1 has hardware keys. No on-screen transport.
No queue editing beyond what the playlists screen defines. No datum on
the LCD that isn't real. (The original "no color album art" cut was
lifted: FULL COLOR is an opt-in Artwork Style; the two-tone renders stay
the default.)
