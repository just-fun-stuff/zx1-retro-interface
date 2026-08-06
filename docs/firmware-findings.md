# Firmware reference — NW-ZX1 (firmware 1.13)

The engineering reference for everything discovered about this device.
Device: `WALKMAN`, Android 4.1.1, build `icx1240_H114`, player
`com.sony.walkman.mediaplayers.wm2` v2.2.2 (`/system/app/MediaPlayers.apk`).
SoC: TI OMAP4, 2× Cortex-A9 at 300/600/800/1008 MHz (`hotplug` governor),
NEON. All facts below were proven on the physical device unless marked
schema-only.

## Now-playing must be pulled

Firmware 1.13 pushes nothing: no `metachanged`-family broadcast from any
Sony package, and the player registers a `MediaButtonIntentReceiver` but a
null RemoteControlClient. The only working source is the
`IMediaServiceRemote` binder (below), which binds with **zero
permissions**. Blocked alternatives: the `…music.summary.recently` /
`.history` providers need a signature-level permission;
`TemporaryProvider` is an image scratch-cache behind a normal-level Sony
permission and was empty during playback anyway. Last-resort fallback if
the binder ever breaks: an AccessibilityService reading the player's
notification (its `tickerText` is the track title — also usable as a
validation oracle via `dumpsys notification`, tag
`tag_play_notification`; artist/art are locked in RemoteViews on 4.1).

## The Genie provider

`content://com.sony.walkman.providers.GenieMediaStore/external/audio/media`
is Sony's library database. Its `<provider>` declares only a
`writePermission`, so reads need **zero permissions**.

### Quirks — every one matters

1. **Caller WHERE selections return nothing** (even `_id>0`). Single rows
   must be addressed by appended-id URI (`ContentUris.withAppendedId`).
2. **Null projections return only `_id`** (media row and albums row
   alike). Always name columns explicitly; `SELECT *` discovery is
   impossible.
3. **Directory reads work with explicit projections** (all ~2700 rows,
   sortstr columns included) — but return nothing (empty or null cursor)
   **while Sony's rescan runs**. Sony reindexes at every power-on and
   every USB-storage disconnect. Treat an empty/unreadable directory read
   as *unknown*, never as *changed* — the launcher's cache fingerprint
   logic depends on this.
4. The provider honors path-scoped URIs from its matcher (see queue URIs
   below); `_data` returns NULL to unprivileged callers on the media row
   (the platform MediaStore `_data` is readable instead).

### Confirmed columns (value-proven)

`_id`, `title`, `artist`, `album`, `duration` (ms), `sample_rate` (Hz),
`bits_per_sample`, `audio_bitrate` (bps), `audio_codec` (enum below),
`mime_type`, `year` (int), `number_of_channels` (int), `track` (int),
`genre` (string), `size` (bytes), `album_id`, `title_sortstr` (Sony's
normalized alphabetical key — populated on **all** rows of the real
library).

Example row: `_id=1736, title=String Quintet in G, Op.77 - III Poco
andante, artist=Berlin Philharmonic String Quintet, album=A.Dvorák -
String Quintet Op.77, year=2011, sample_rate=96000, bits_per_sample=24,
audio_bitrate=2742000, audio_codec=9 (ALAC), mime_type=audio/mp4,
number_of_channels=2, duration=456533, track=3, genre=Classical`.

Schema-only (present in the DB client jar, values unverified):
`date`, `date_original`, `date_published`, `production_date_info`,
`date_added`, `date_modified`, `format`, `aac_codec_detail`,
`wmchannelinfo`, `synclyric` (time-synced lyrics!), `unsynclyric`,
`play_count`, `first_playback_date`, `last_playback_date`,
`total_play_time` (all NULL to unprivileged readers), `artist_id`,
`genre_id`, `year_id`, `*_initial`, `artist_sortstr`, `album_sortstr`.

### Art columns

Media row: `albumcoverart`, `coverart`, `album_rep_media_coverart`,
`album_rep_media_thumbnail`, `image_strict_size`. Albums row
(`…/external/audio/albums/<album_id>`) carries the same five. On device,
`coverart` holds an 812-byte blob and the album-row rep/thumbnail columns
hold small blobs — none decode as images (too small for covers; likely
serialized references; hex previews logged once per run by
`ZX1AlbumArt`). The working art source is the **platform MediaStore
albumart** (below).

### Playlists

`…/external/audio/playlists` answers an unprivileged read with columns
`[_id, name]` (11 rows on this device). Members:
`…/external/audio/playlists/<id>/media` in provider order.

### `audio_codec` enum — ground-truthed across the whole library

| `audio_codec` | mime | Meaning | Seen at |
|---|---|---|---|
| 2 | audio/mpeg | **MP3** | 44.1/16, 48/16 |
| 3 | audio/mp4 | **AAC** (lossy) | 44.1/16 only |
| 8 | audio/flac | **FLAC** | 44.1/16 up to 192/24 |
| 9 | audio/mp4 | **ALAC** | 44.1/16 up to 192/24 |

Unmapped codec → fall back to the mime subtype.

### HR rule and quality line

`bits_per_sample > 16 OR sample_rate > 48000` cleanly separates the
hi-res files of the real library. Quality line format:
`<CODEC> <sr/1000>/<bits>  <bitrate/1000>KBPS  <n>CH` + `HR` chip —
round sample rate to one decimal, drop trailing `.0`, omit NULL fields.

### Genie URI matcher (from the wm2 string table)

`external/audio/media` (+ `/#`, `/initials`), `…/albums` (+ `/#`,
`/#/media`, `/#/representative`, `/initials`), `…/artists` (+ `/#`,
`/#/media`, `/#/albums`, `/#/albums/#/media`, `/initials`),
`…/playlists` (+ `/#`, `/#/media`), `…/genres/…` and `…/years/…`
hierarchies, `…/folders` (+ `/#/members`, `/#/parent`),
`…/recent/#/…`, `…/rates/#/media`, `…/search/media`,
`…/sensmechannels/#/media`.

## The binder — read side

Bind action `com.sony.walkman.media.IMediaService`, package
`com.sony.walkman.mediaplayers.wm2`; no permission required. The service
is a factory:

- `IMediaServiceRemote` (token `com.sony.walkman.media.IMediaServiceRemote`):
  - `getPlayers()` → **txn 3** → `readException(); createStringArrayList()`
    — on device the list is `[com.sony.walkman.mediaapp.music]`; it is
    **empty until the player app has run once** since boot.
  - `getPlayerInterface(String name, Bundle)` → **txn 1**: token,
    `writeString(name)`, `writeInt(0)` (null bundle) →
    `readException(); readStrongBinder()` → an `IPlayerRemote`.
- `IPlayerRemote` (token `com.sony.walkman.media.IPlayerRemote`):
  - `getCurrentInfo()` → **txn 27** → `readException();` then
    `readInt()!=0 ? PlayInfo : null`.
  - `getAudioSessionId()` → **txn 51** → the player's audio session
    (46 on device).
  - `addListener(IPlayerEventListenerRemote, String)` / `removeListener`
    (txns 1/2 on this interface) — push callbacks, an upgrade path over
    polling.

**`PlayInfo` parcel read order** (consume in sequence to stay aligned; a
reader may stop after the last field it needs):

1. int errorCode  2. int speedNum  3. int speedDenom
4. **long trackId** ← the Genie `_id`
5. **int playTime** (elapsed ms)  6. **int duration** (ms)
7. int chapterIdx  8. int numOfChapter  9. int playTimeInChapter
10. **int trackIndexInList** (**0-based** — display 1-based)
11. **int trackCountInList**
12. **int PlayState ordinal** — `CLOSE=0, PAUSE=1, PLAY=2` (map CLOSE →
STOP)  13. bool endOfTrack  14. bool mute  15. int shuffleMode
16. int repeatMode  17. bool trackChange  18. int audioBitrate (live —
may differ from Genie's stored value for VBR)  19–22 int (video
bitrate/frame w/h; 22 = unshuffledIndex)  23–29 int (stream
counts/indices, PAR w/h)  30. `readInt()!=0 ? Rect : null`
31. bool seekable  32. bool pausable.

Other `IPlayerRemote` accessors (decompile-verified): `getRepeatMode`
txn 21, `getShuffleMode` txn 19, `getShuffledIndex` txn 26,
`getMediaListInfo` txn 22, `getPlayerConfig` txn 38,
`getAudioOutputEffect` txn 33 (active EQ), `inquireMeta` /
`…WithIndex` / `…WithTime` txns 41–43.

**Never brute-force transaction codes against the live player** — a
stray no-arg call could hit stop/close. All codes here come from the
decompiled `/system/framework/com.sony.walkman.media.client.dex.jar`
(plain classes.dex; pull and jadx to re-derive; never commit Sony
source).

## The binder — write side (playback handoff)

The player's queue model is **declarative**: the client hands a content
query descriptor (`PlayListSelectionInfo`) and the media service runs
the query itself, in its own Sony-privileged process — WHERE/ORDER BY
work there even though they are void for us.

- `open(PlayListSelectionInfo, boolean)` → **txn 3** — replaces the media
  list and cues `startIndex`; the client library always passes the
  boolean as `true`. Asynchronous on the service side.
- `play()` → **txn 6** — see "bare play() is a trap" below; the working
  start call is `playWithParam` **txn 7**.
- `getMediaListInfo()` → **txn 22** — read-only oracle: the current
  list's descriptor.
- Reply framing for all three: `readException()`, presence int, then
  `RequestResult` = `{int taskId, int MediaError}`. MediaError parcels
  as its integer code: `0` Success, `305` Error_EmptyPlaylist (terminal
  — the query matched nothing), `308` Error_CursorUnavailable,
  `50432` InvalState / `50433` StateChange (transitional).
- Also mapped (documented, not issued): close=4, play=6, pause=8,
  seek=9, prevChapter=11, nextChapter=12, seekChapter=13, prevTrack=14,
  nextTrack=15, seekTrack=16, setShuffleMode=18, setRepeatMode=20,
  closeSync=47, applyPlayRange=49, clearPlayRange=50, playlistUpdated=52.

**`PlayListSelectionInfo` parcel layout** (writeToParcel order, read
back field-for-field by the service):

1. nullable **Uri** (presence int + `Uri.writeToParcel`)
2. int **startIndex**
3. nullable String **selection**
4. nullable String **sortOrder** — **a single bare column name**: the
   wm2 code validates sort strings ("The column name for an SQL ORDER BY
   clause should be specified"); a two-column sort was rejected live
   with MediaError 308
5. String[] **selectionArgs** — **always write absent (0)**: the
   service-side reader discards them into a null target
6. nullable String **tag** — Sony's own lists use
   `com.sony.walkman.mediaapp.music`
7. nullable String **proxyHost** (null)  8. int **proxyPort** (0)
9. nullable String[3] **column names** — the library default
   `{_id, _data, mime_type}`; the reader demands exactly three
10. int **startIndexDisabled** (0 = honor startIndex).

The outer call wraps this as: interface token, presence int, the ten
fields, then the boolean int.

**Live capture of Sony's own descriptor** (getMediaListInfo while their
UI played an album):
`uri=content://…GenieMediaStore/external/audio/albums/122/media,
index=3, selection=null, sort=null, tag=com.sony.walkman.mediaapp.music`
— path-scoped URI, no selection, no sort (the provider's default order
for an album is track order).

**Queue URIs without selections:** album = `albums/<id>/media`; all
songs = `external/audio/media` (ORDER BY `title_sortstr`); playlist =
`playlists/<id>/media` in provider order. To keep the tapped row's
position valid as the queue start index, display lists sort locally with
SQLite BINARY collation (unsigned UTF-8 bytes, NULL first, rowid-order
ties) on exactly the ORDER BY column.

**Exported intent entry (fallback tier), proven working on device:**
wm2's `ScanAndPlayFileActivity` is an exported `ACTION_VIEW` handler for
**file-scheme** audio (mimes mpeg/mp4/3gpp/3gpp2/x-wav/x-ms-wma/
x-sony-oma/flac/aiff; an `ACTION_PLAY_CONTENT` alias, file scheme too).
`content:` URIs match nothing — join Genie→platform for the `_data`
path and fire VIEW `file://<path>` at the explicit component. Plays one
file and opens the Sony UI.

**The open() wedge (observed live, two runs).** The player entry
persists while idle: `getPlayers()` answers
`[com.sony.walkman.mediaapp.music]` with nothing playing. An open()
shaped `{single-column sort, tag=""}` was answered by **silence** — no
MediaError, no exception, the transact never returns — on an idle
engine, and again post-reboot on a healthy engine mid-playback. The
unanswered call is not inert: it holds the service's write path, so
from that moment Sony's own UI won't start playback, the hardware keys
go dead, and `ScanAndPlayFileActivity` opens to a spinner that never
finishes — everything queues behind the wedge until the device reboots.
Reads (`getPlayers`, `getMediaListInfo`, `getCurrentInfo`) keep
answering throughout. By contrast a `{two-column sort, tag=""}` open
DID reply (MediaError 308) — the service answers when cursor creation
fails early; the block sits past that point. The Sony shape —
`{their module tag; albums/playlists sort=null; songs a single title
sort column}` — replies instantly and never wedges (the empty-tag/
our-sort shape remains the wedge trigger; the two changed together and
were not isolated further, since each re-test costs a reboot).
Consequences for any client: the handoff watchdog must live off the
calling thread; variant ladders are impossible — the first wedged rung
poisons every later one — so send exactly **one shape per tap, the
player's own**, and treat an unanswered transact as fatal: latch all
later requests to fail fast (the intent tiers can only spin against a
wedged service).

**Task lifecycle: RequestResult is submission, not completion (proven
live).** A MediaError of 0 only means the task was queued; completion
is announced through listener events (`onOpen`/`onPlay`), which only
**registered clients** hear — every anonymous open() was accepted and
then died at execution. Three more lifecycle facts, each proven live:
the descriptor **updates at submission time** (the oracle shows the new
uri before the task has run — a descriptor match proves nothing); a
**failed task closes the list** (`media list: none` afterward); and a
`play()` submitted while the open task is still cueing is accepted and
silently does nothing. After a failed play task the service issues
**its own close task** (an `onClosed` with a task id never submitted)
— the self-close is what vaporizes the list after every failed start.

**The registered-client handshake (the working sequence, constants in
`SonyWalkmanContract`):** `getPlayerInterface(name, Bundle)` returns
the client id under `"id"`; `addListener` (IPlayerRemote txn 1)
attaches a hosted `IPlayerEventListenerRemote` stub;
`setServerKeepAlive(id, true)` (txn 46) so the player outlives a
short-lived client. Callbacks are **not oneway** — the service blocks
on every reply — so the stub must answer instantly and never block or
call back into the player. `onOpen` is listener txn 2, `(int, int,
PlayListSelectionInfo?)` — both leading ints carry the task id. The
handoff waits on it, re-checks that the current list is the submitted
one, then starts playback. Teardown mirrors Player.close:
`removeListener` (txn 2), then `releasePlayerInterface` (media-service
txn 2).

**Bare play() is a trap (proven live).** `play()` txn 6 is accepted
with MediaError 0, but each bare-play task fails, and a failing task
closes the freshly opened list. The stock controller never calls it:
`PlaybackController.play()` is `playWithParam(PlaySpeed(1, 1),
muted=false, fadeInTime=-1)` — txn 7, parcel `{1, numerator,
denominator, muted-int, fadeInTime}` (PlaySpeed is two ints). Send it
once, then watch getCurrentInfo for PLAY — never resubmit, since a
failed task kills the list. What bare play() is actually for remains
unknown.

**The listener transaction map** (decompile-verified, index = txn):
1 onError, 2 onOpen, 3 onClosed, 4 onPlay, 5 onPause, 6 onSeek,
7 onPreviousTrack, 8 onNextTrack, 9 onSeekTrack, 10 onUpdated,
11 onNextChapter, 12 onPrevChapter, 13 onSeekChapter, 14 onFlash,
15 onSequenceBegin, 16 onSequenceEnd, 17 onTrackBegin, 18 onTrackEnd,
19 onTrackChanged, 20 onShuffleModeChanged, 21 onInqured,
22 onBufferingUpdate, 23 onAudioOutputEffectChanged, 24 onMetaData,
25 onPlayAudioStreamChanged, 26 onPlaySubtitleStreamChanged,
27 onTerminate, 28 onVideoFrameChanged, 29 onPlaylistChanged,
30 onApplyPlayRange, 31 onClearPlayRange, 32 onStartFade, 33 onFadeEnd.
`onError` carries a MediaError (parcels as a single int; its code table
is MediaError.java in the decompile), optionally preceded by a PlayInfo
— skip it positionally: 3 ints, 1 long, 7 ints, PlayState ordinal,
16 ints, optional 4-int Rect, 2 ints.

**Dead ends, proven dead on device — do not revisit:**

- **Caller credentials / storage permission.** Theory: the engine opens
  a queue's media under the submitting client's uid and this firmware
  enforces `READ_EXTERNAL_STORAGE`. A direct file-read probe under the
  launcher's uid answered `canRead=true` while the same queue still
  died — the uid was never blocked from the media. (The permission is
  held anyway; it is arguably correct for an app that reads music
  files.)
- **Audio focus.** A pre-play focus request was granted and changed
  nothing; the engine **takes focus itself when playback starts** (the
  launcher's own holder received AUDIOFOCUS_LOSS right after PLAY), so
  any pre-play request is inert. (MediaError 307 exists for the
  service's own focus failures.)

**ROOT CAUSE — Genie descriptors cannot play from an unprivileged
client (proven live).** A Genie `_data` probe under the launcher's uid
answered `columns=[_id, _data, mime_type] _data=null` — **Genie serves
the `_data` column but withholds its value from unprivileged callers**
(the same caller-gating that voids WHERE selections). The provider's
manifest confirms the gate: GenieMediaStore's default access permission
is `com.sony.walkman.database.media.permission.ACCESS_DRM_METADATA`,
protectionLevel **signature|system** — unholdable by any sideloaded app
(ordinary-column reads evidently pass through a laxer path; sensitive
columns are nulled in provider code). The engine's default track
projection is exactly `{_id, _data, mime_type}` and it resolves the
queue **under the submitting client's identity** — so a Genie
descriptor submitted by an unprivileged client builds a correct list
(ids readable), cues the right index, then dies at play opening a null
path (`onError` MediaError 256, Error_Unknown — the taxonomy has
dedicated codes for focus 307, cursor 308, track open/access/IO
50448–50450, codecs, licenses, so the engine died below all of them),
whereupon the service closes the list. Sony's own components submit as
their uid and get real paths — which is why a byte-identical
single-file descriptor plays from ScanAndPlayFileActivity and dies when
submitted by the launcher (proven same-run).

Consequence: **binder queues must ride the platform MediaStore**, whose
`_data` this uid provably reads (the view-file tier lives on it). Album
taps open `content://media/external/audio/media` with
`selection=album_id=<platform id>` + single-column `ORDER BY track`
(the selection *string* field is writable — only selectionArgs must
stay absent); one-track queues open the appended-id platform uri. The
Genie→platform join (title+album+duration with a track-number
tie-break, refuses ambiguity) supplies the ids. **Genie descriptors are
never submitted** — each one costs a dead list.

**CONFIRMED WORKING (proven live):** platform-MediaStore descriptors
play from the launcher's registered client — album queues
(`selection=album_id=N`, `ORDER BY track`, tapped index cued) and
one-track appended-id lists both reach PLAY with no UI switch, and
playback survives leaving the launcher. Measured consequence: **the
player then reports platform ids** in PlayInfo.trackId, so the
now-playing poll routes identity by the current list's uri authority
(one oracle read per track change) and maps platform ids back to Genie
rows through the library snapshot (title + album, duration ±2 s,
ambiguity refuses → dark, never a guess; identical-title ties narrow
through the track number first — see the box-set finding below), then
runs the normal Genie query so the whole display pipeline stays
Genie-based.

**Songs queues:** SONGS taps open the whole platform music table
(`is_music=1`, `ORDER BY title_key`) with the start index counted
against that same query (`title_key <`, rowid ties) — the tapped track
cues exactly even where the displayed Genie collation diverges from
title_key. An arbitrary member order is inexpressible in a descriptor
(single-column ORDER BY, selectionArgs absent) — the shape that *can*
express a stored list is the **platform playlists table**:
`…/audio/playlists/<id>/members` orders by the single column
`play_order` (see below).

Open questions: whether platform-album display order (Genie trackNo,
Genie-id ties) ever diverges from the player's `ORDER BY track`
(platform-rowid ties) on tie-heavy or multi-disc albums — the
cued-vs-requested verification line names any mismatch; whether the
stock app reasserts its persisted list on a cold start after our
open().

**Queue-next over a platform playlist (PROVEN LIVE 2026-07-21).** ADD
TO NOW PLAYING rides one launcher-owned platform playlist ("ZX1 NOW
PLAYING" — invisible to Sony's UI, which reads only Genie playlists):
the current queue order is materialized launcher-side (platform
descriptors re-run verbatim through our own resolver — same provider,
same SQL, same tie order; Genie path-scoped lists re-read by id in
provider order and joined to platform ids with the strict discipline,
capped at 200 rows; the all-songs table rides the snapshot's
`title_sortstr` order; PlayInfo's live index disambiguates duplicate
tracks), written into the playlist, and reopened through the
registered-client flow with the descriptor `{members uri,
selection=null, sort=play_order, Sony's module tag}`.
`WRITE_EXTERNAL_STORAGE` (install-time) covers the playlist writes.
The live run answered every open question:

- **The engine takes the members uri.** open MediaError 0 → onOpen →
  the oracle echoed the descriptor verbatim (`playlists/<id>/members
  index=7 selection=null sort=play_order tag=…music`) → playWithParam
  → PLAY, cued at the requested index.
- **PlayInfo.trackId reports the platform AUDIO id from a members
  queue, not the member-row id.** Proof: the reported id was identical
  before and after a full member delete+reinsert — no member-row id
  survives that, an audio id does. The poll therefore passes a
  members-queue id through when it matches an audio_id in the list
  (checked first, which also disarms numeric member-id/audio-id
  collisions) and keeps the member→audio walk only as the second
  reading.
- **seek txn 9 works**: `(int positionMs, int origin)`, origin =
  `IPlaybackController.SeekOrigin.ordinal()` (BEGIN=0, CURRENT=1,
  END=2). MediaError 0, an onSeek event, and getCurrentInfo read back
  exactly the requested position in state PLAY.
- **The reopen is audible.** The play watch caught the engine at PAUSE
  right after open() — replacing the list mid-play stalls the
  pipeline, re-cues the current track from 0:00, and playWithParam +
  seek recover: one audible hitch per reopen, by construction.
- **The engine watches its list's backing table.** ~200 ms after the
  member rewrite (delete + bulkInsert) — before our open() was even
  submitted — the oracle read `media list: none` while audio kept
  playing, and the freshly attached listener heard onUpdated +
  onPlaylistChanged. A change to the backing rows makes the engine
  drop its current descriptor (the decode pipeline finishes the
  playing track regardless); the reopen is what re-establishes it.
- `pause` **txn 8** (argumentless, the stock controller's own — unlike
  bare play) is wired for restoring a paused state but has not yet run
  live: the run was mid-PLAY.

**The hitchless in-place insert (PROVEN LIVE 2026-07-21).** Consequence
of the last two facts: when the current list already IS our members
list, a gentler edit never touches playback — insert ONE member row,
then verify with read-only calls (getCurrentInfo's live track count
over ~1.5 s, and the descriptor staying present) whether the engine
integrated it. The live run answered YES: **the engine integrates a
pure single-row insert into its open list's backing table** — count
grew, playback never blinked (a full delete+rewrite drops the
descriptor; a plain insert is absorbed). Repeat adds are therefore
seamless; only a queue-type swap (album → our playlist) still pays the
one reopen hitch. On any refusal — descriptor drops, count never
moves, insert rejected — the proven rewrite+reopen runs with a freshly
re-read playhead (the stray row is swept by the normalizing rewrite,
never duplicated). No new write transactions are involved.
`playlistUpdated` **txn 52** (documented, never issued: its int
argument has no caller in the client jar — semantics live only in the
un-decompiled wm2 odex) is not needed for this; it stays the research
lever only if some future edit shape turns out not to be absorbed.

**Queue semantics:** ADD TO NOW PLAYING appends at the END of the
queue — several adds line up first-added-first, the playlist-building
order (an earlier insert-after-current design made stacked adds come
out last-added-first). Appending also removes any play_order gap
arithmetic: rewrites normalize 0..n-1 and an in-place add just writes
tail+1.

**Playlists as platform playlists — shipped, awaiting the device run.**
The queue-scratch list generalizes: every launcher playlist *is* a
platform MediaStore playlist, so a stored list and an engine queue are
the same object — `playlists/<id>/members` + `ORDER BY play_order`
cued at the tapped row is the already-proven descriptor. Consequences
that follow from proven facts, not new protocol: launcher lists play
from any position with no mirroring; Sony's Genie lists cannot be
descriptors at all (their `_data` is withheld), so playing one
materializes it into the reserved "ZX1 NOW PLAYING" list first — which
also leaves the queue *as* that list, so a following ADD TO NOW
PLAYING takes the hitchless path. Member reads use the same query and
ORDER BY the engine runs, so a row's display index is its queue index.
The AOSP idiom is followed for member identity: `_id` on the members
uri is the map-row id, and a member is deleted by appended-id uri.

Open questions the current build is built to answer (all logged under
`ZX1Playlists`; nothing below is claimed as proven):

- **Does the engine integrate a single-row member DELETE** the way it
  integrates an insert? `PlaylistEditor` performs the delete, then asks
  read-only whether the live track count fell and the descriptor
  survived: `the engine integrated the delete live` vs `the engine did
  not integrate the delete; reopening the list`. Either answer is
  handled (the reopen repairs it, cued at the playing row, or at the row
  that inherited the removed one's position); the point is to record
  which one this firmware gives.
- **Does the engine's own default projection (`{_id, _data, mime_type}`)
  return the same member rows our display query does?** If a member's
  platform `_data` were ever null the engine's list would be shorter
  than the displayed one and the cue index would drift. The
  cued-vs-requested log line in `ZX1Handoff` names any mismatch.
- **Does anything Sony-side react to platform playlists** — their UI,
  their indexer, their rescan? Still expected to be invisible (their app
  reads Genie playlists only), now with several named lists rather than
  one.
- Whether a whole-list delete could be survivable mid-play is
  deliberately not tested: the launcher refuses to delete the list the
  engine is holding, since nothing could repair a descriptor whose rows
  no longer exist.

## Album art

Working source, proven across the library: **platform MediaStore
albumart**. Join the Genie row to `content://media/external/audio/media`
by `title` + `album` + `duration` (±2 s; title-only as fallback; if
surviving candidates disagree on the album id, refuse — never risk
another album's art), then stream
`content://media/external/audio/albumart/<albumId>`. The provider keeps
250–300 px JPEG thumbs under
`/storage/sdcard0/Android/data/com.android.providers.media/albumthumbs/`
(world-readable) — that resolution is the source ceiling for the art
panel. Platform `_data` is readable on 4.1 (storage-read enforcement
began in 4.4), so `MediaMetadataRetriever.getEmbeddedPicture()` works as
a further fallback (never needed on device). Genie's own art blobs do
not decode (above). Cover processing: bounds-only decode → power-of-two
subsample → center-crop to panel aspect → filtered scale to the panel's
native pixel size (~447 × 257) → BT.601 greyscale → 2% histogram-tail
contrast stretch → smooth 256-step ink→glass ramp; the plasma keeps its
Bayer 4×4 dither and chunky 146 × 85 raster.

## Formats on this device

FLAC and ALAC up to 192 kHz / 24-bit, MP3, AAC. Containers `audio/flac`,
`audio/mp4`, `audio/mpeg`. No DSD seen. Library measures 2701 tracks /
204 artists / 132 albums / 11 playlists. Album/artist strings run long
(classical) — the title line scrolls; other lines hard-clip.

## Display and audio-path facts

- **App window is 480 × 782, not 480 × 854**: the firmware keeps
  Android's nav bar (72 px at density 240); `FLAG_FULLSCREEN` removes
  only the status bar on 4.1; immersive mode does not exist.
- `PlayInfo.trackIndexInList` is **0-based** (live capture read
  `TR 0/13`); display 1-based.
- The hi-res path (S-Master) **bypasses the AudioFlinger mixer
  entirely**: the mixer idles during playback, yet Sony's audio policy
  still refcounts STREAM_MUSIC, so `AudioManager.isMusicActive()` stays
  a valid PLAY/STOP fallback.
- The only Sony ServiceManager entry is `wmportservice`; the media
  service is reachable exclusively as the app-bound binder.
- `com.sonyericsson.audioeffectservice` broadcasts effect/route status
  (`…XLOUD_SPEAKER_STATUS` / `…WMPORT_STATUS` / headphone variants, plus
  `com.sony.walkman.intent.action.UPDATE_HEADPHONE_DEVICE_MODEL`) —
  distinguishes speaker vs WM-PORT dock vs headphones if the footer ever
  wants the dock case. No Sony package broadcasts track metadata.

## Live spectrum capture is impossible (settled — do not re-attempt)

Proven with RECORD_AUDIO held and a working `Visualizer` attached to the
player's real audio session (46, via txn 51): **every FFT capture is
pure zeros on every format** (96/24 FLAC, 44.1 FLAC, MP3 — the S-Master
bypass covers all playback, lossy included), and **session 0 refuses to
initialize** (`Cannot initialize Visualizer engine, error: -1`). No
Android-side point observes this device's playback PCM. Sony's own
visualizer (`com.sony.nextgen.UXVisualizerWalkman`) confirms it
architecturally: it holds no RECORD_AUDIO, references `audiofx` zero
times, and **replays offline "12 Tone Analysis"/SensMe data** synced to
`IPlayerRemote` position — and its stored analysis (`sensme_tempo`,
`sensme_style`, `analyze_status`) returns NULL to unprivileged readers
and is mood/tempo data, not a spectrum. The offline-analysis pipeline
below is the working answer; without stored analysis the chassis window
holds its designed baseline.

## Offline self-analysis — decode paths and sizing

Measured live 2026-07-20 (throwaway `DecodeProbeService`, decode-to-EOF
on real library files, background thread priority, device otherwise
idle):

| Source | Route | Result |
|---|---|---|
| FLAC 44.1/16 | `MediaExtractor` | **`audio/raw` PCM straight from the extractor**, 16-bit LE — **42.9× realtime** |
| FLAC 44.1/24 | `MediaExtractor` | works, delivered **truncated to 16-bit** (implied duration matches at 16-bit math) — 15.6× |
| FLAC 48/24 | `MediaExtractor` | works, 16-bit out — 45.9× |
| FLAC 96/24, 192/24 | `MediaExtractor` | **fails: zero tracks exposed** — the JB-era sniffer refuses sample rates above 48 kHz (matches platform MediaStore's `duration=0` on the same rows) |
| MP3 44.1 | MediaCodec `audio/mpeg` | 11.0× realtime, 16/44.1 stereo out |
| AAC M4A 44.1 | MediaCodec `audio/mp4a-latm` | 9.1× (7.7× cold) — 16/44.1 out |
| ALAC M4A | `MediaExtractor` | track exposed as `application/octet-stream` — **no audio track, undecodable** |

- So the platform-decodable set is MP3 + AAC + FLAC ≤ 48 kHz (any bit
  depth): 1683 of this library's 2701 tracks (62%). ALAC (765) and
  FLAC > 48 kHz (250) need the hand-written decoders below.
- **FLAC decodes with platform APIs**: Sony's `libstagefright.so` ships
  the stock `FLACExtractor` with embedded libFLAC — `MediaExtractor`
  clients receive decoded PCM directly (which is why `media_codecs.xml`
  lists only a FLAC *encoder*). The 24→16-bit truncation and the 48 kHz
  ceiling are that 2012 extractor's stock behavior.
- **ALAC is absent from the entire Android media stack** — only Sony's
  closed engine decodes it. A spectrum-grade decoder must be
  hand-written (M4A box walk + Rice/LPC, ~1000–1500 lines).
- File access: platform `_data` paths are real and readable
  (`/storage/sdcard0/Music/…`; hi-res FLAC runs 50–95 MB).
- Storage: `/data` had ~555 MB free. Envelope cost 8 bands × 4 bits ×
  12 fps = **48 B/s of track** → ~12–17 KB/track, whole library ≈ 40 MB,
  so a ~20 MB LRU cap is plenty. 16 levels per bar matches the ~25 px
  window.
- At the measured 9–46×, analysis outruns the playhead well inside a
  first listen: a typical track is fully analyzed in 4–30 s of
  lowest-priority CPU, once.

## Hand-written decoders — measured 2026-07-20

The 38% gap (765 ALAC + 250 FLAC > 48 kHz) closed with original
decoders written from the published bitstream formats (`AlacDecoder`,
`FlacDecoder`; nothing ported — GPLv2). Verification method, repeatable:
the decoder sources are Android-free, so the same files compile on a
desktop JVM against a `Harness` that compares every 16-bit output sample
with afconvert's decode (24-bit reference `>> 8`, the identical
truncation) — **all five probe formats bit-exact** (397M interleaved
samples, zero mismatches), and the on-device probe CRCs matched the
desktop CRCs exactly, so device decode ≡ verified reference decode.

Speed on the device (throwaway exported `DecodeProbeService`,
decode-to-EOF at `THREAD_PRIORITY_LOWEST`, hotplug governor observed at
max 1008 MHz during the runs, CRC32 computed alongside):

| Source | naive | + rice inline | + log2/emit tables |
|---|---|---|---|
| ALAC 44.1/16 (35 MB) | 4.7× | 6.3× | **8.8×** |
| ALAC 96/24 (140 MB) | 1.9× | 3.1× | **4.1×** |
| ALAC 192/24 (180 MB) | 0.9× | 1.5× | **2.0×** |
| FLAC 96/24 **6-channel** (204 MB) | 1.1× | 1.7× | **1.7×** |
| FLAC 192/24 stereo (67 MB) | 2.7× | 3.8× | **4.0×** |

- **The Dalvik lesson (this is the whole table):** the trace JIT
  neither inlines small virtual methods nor keeps fields in registers
  across them, so a per-sample bit-reader call is ruinous. Copying the
  reader state into locals inside the entropy loops, a 1 KB
  leading-ones/zeros table for the unary prefixes, and a log2 table
  for ALAC's Rice-parameter law roughly **doubled** throughput
  end-to-end. The same sources run 80–340× on a desktop JVM.
- ALAC's Rice history is NOT bounded by the 0xFFFF escape clamp — the
  update arithmetic pushes it into the millions on hi-res content
  (observed live); any table on `(history >> 9) + 3` needs a computed
  fallback.
- The measured floor: CD ALAC (636 tracks, the bulk) 8.8×; 88.2/96
  ALAC ≈ 4×; FLAC 176.4/192 ≈ 4×; the corners are 192 kHz ALAC (10
  tracks) at exactly 2.0× and the library's one 6-channel 96/24 album
  at 1.7× (stereo-equivalent ≈ 5× — the 5.1 layout triples the
  samples; the log2/emit round moved only ALAC, as its cost is the
  long-multiply LPC). Analysis still beats the playhead and runs once
  per track ever; on those corner files the first-listen bars trail
  the playhead further than elsewhere. End-to-end through the real
  pipeline while the track played (poll → router → decoder →
  envelope), measured: CD ALAC finished at 8.7× (idle probe 8.8× —
  the second A9 core absorbs Sony's own decode) and 192/24 FLAC at
  2.3× (idle 4.0× — hi-res playback loads the engine harder), both
  completing well inside the first listen.
- **The duration-0 join trap, confirmed live:** platform MediaStore
  rows for >48 kHz FLAC carry `duration=0` (the JB scanner cannot
  parse them), so a strict title+album+duration join rejects every
  hi-res FLAC. The join ranks unknown durations behind real matches
  instead of rejecting (`AlbumArtResolver`, album-ambiguity refusal
  unchanged) — which also opens the platform-albumart cover source to
  those tracks.
- **Identical-title box sets defeat duration-0 rows entirely (live
  2026-07-21):** classical sets repeat movement titles exactly within
  one album — a 96/24 *Vivaldi: The Four Seasons* rip carries three
  tracks named "… - 3. Allegro" — and with every platform duration
  reading 0 the title+album+duration join has no separator left. The
  platform→Genie now-playing map refused 9 of that album's 12 tracks
  (`platform id 13749 matches several library rows; staying dark`:
  dark title, plasma art, flat spectrum), and the forward join's
  duration ties silently kept the first cursor row — a wrong-file
  risk for the spectrum analyzer and the one-track queue fallback.
  Both providers store the track number and they align row-for-row
  (platform `track` 1–12 ↔ Genie `track` 1–12, measured). The
  launcher uses it as a tie-break only, compared modulo 1000 (the
  platform scanner encodes multi-disc rips as disc*1000+track): the
  forward join prefers the agreeing row among equal-rank candidates,
  the reverse map must narrow the survivors to exactly one — never to
  widen or override a unique match, so unresolvable ties still go
  dark rather than guess.
- The Genie `sample_rate` column is plain Hz and routes the analyzer:
  codec 9 → ALAC decoder any rate; codec 8 + rate > 48000 → FLAC
  decoder; all else platform. Codec 8 with NULL rate stays platform.
- 6-channel 96/24 FLAC exists in this library (Genie reports it like
  any row; Sony plays it); `FlacDecoder` handles independent channels
  to 8, and `BandAnalyzer` mixes any channel count.

## Misc verified facts

- wm2 activity table exports `ACTION_START_TOP`, `ACTION_START_DASHBOARD`,
  `ACTION_START_PLAYING_VIEW`, `ACTION_RECENTLY_ADDED_VIEW`,
  `ACTION_REC_FOLDER_VIEW`, `ACTION_GLOBAL_SEARCH` (on MusicTopActivity).
- `MusicControlService` exports `appwidget.PREV / NEXT / PLAY_PAUSE /
  PLAY / PAUSE / CUE_START / REV_START / …` — working transport intents
  (unused by design: hardware keys own transport).
- `GenieProtectedMediaStore` (DRM titles) needs a signature|system
  permission — DRM tracks won't appear via the open provider; skip.
- The platform media scanner runs on this firmware alongside Genie's
  indexer (platform MediaStore is populated and albumthumbs exist), so
  the platform scan broadcasts correlate with Sony's reindex events.
