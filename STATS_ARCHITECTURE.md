# Stats System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                           USER INTERFACE                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  HomeFragment (Menu)                                                 │
│       │                                                               │
│       │ [User taps "Stats" menu item]                               │
│       ↓                                                               │
│  StatsFragment                                                        │
│       │                                                               │
│       │ observes                                                      │
│       ↓                                                               │
│  StatsViewModel                                                       │
│       │                                                               │
│       │ loads data                                                    │
│       ↓                                                               │
└───────┼───────────────────────────────────────────────────────────────┘
        │
        │
┌───────┼───────────────────────────────────────────────────────────────┐
│       │              BUSINESS LOGIC LAYER                              │
├───────┼───────────────────────────────────────────────────────────────┤
│       ↓                                                                │
│  StatsRepository                                                       │
│       │                                                                │
│       ├─ recordPlay(song, duration)                                   │
│       ├─ getAllSongStats() ──┐                                        │
│       ├─ getAlbumStats()     ├─ aggregates → Top 10 Lists           │
│       ├─ getArtistStats()    │                                        │
│       └─ getOverallStats() ──┘                                        │
│              │                                                         │
│              │ reads/writes                                           │
│              ↓                                                         │
└──────────────┼─────────────────────────────────────────────────────────┘
               │
               │
┌──────────────┼─────────────────────────────────────────────────────────┐
│              │              DATA LAYER                                  │
├──────────────┼─────────────────────────────────────────────────────────┤
│              ↓                                                          │
│         StatsDao                                                        │
│              │                                                          │
│              │ Room ORM                                                │
│              ↓                                                          │
│    ┌─────────────────────┐                                            │
│    │  SongStats Table    │                                            │
│    ├─────────────────────┤                                            │
│    │ songUid (PK)        │                                            │
│    │ playCount           │                                            │
│    │ totalListenTimeMs   │                                            │
│    │ lastPlayedTimestamp │                                            │
│    └─────────────────────┘                                            │
│              ↑                                                          │
│              │ writes                                                  │
└──────────────┼──────────────────────────────────────────────────────────┘
               │
               │
┌──────────────┼──────────────────────────────────────────────────────────┐
│              │           TRACKING LAYER                                  │
├──────────────┼──────────────────────────────────────────────────────────┤
│              │                                                           │
│         StatsTracker                                                     │
│              ↑                                                           │
│              │ implements Listener                                      │
│              │                                                           │
│    PlaybackStateManager                                                 │
│         ↑    │                                                           │
│         │    ├─ onIndexMoved() ────────────┐                           │
│         │    ├─ onQueueChanged() ──────────┤                           │
│         │    ├─ onProgressionChanged() ────┼─→ Record Session         │
│         │    ├─ onNewPlayback() ───────────┤   (if > 3 seconds)       │
│         │    └─ onSessionEnded() ──────────┘                           │
│         │                                                                │
│         │ notifies                                                      │
│         │                                                                │
│    PlaybackServiceFragment                                              │
│         ↑                                                                │
│         │                                                                │
│    ExoPlayer (Media3)                                                   │
│         ↑                                                                │
│         │                                                                │
│    [Music Playback]                                                     │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘


DATA FLOW EXAMPLES:

1. Recording a Play:
   Song plays → ExoPlayer → PlaybackServiceFragment → PlaybackStateManager
   → StatsTracker (listener) → recordSession() → StatsRepository.recordPlay()
   → StatsDao.insertOrUpdateStats() → Database

2. Viewing Stats:
   User taps "Stats" → HomeFragment → navigate → StatsFragment
   → StatsViewModel.loadStats() → StatsRepository.getAllSongStats()
   → StatsDao.getAllStatsByPlayCount() + MusicRepository (resolve UIDs)
   → StatsViewModel (StateFlow) → StatsFragment (UI update)

3. Computing Aggregations:
   StatsRepository.getAlbumStats():
   - Fetch all SongStats from database
   - Group by album.uid
   - Sum playCount and totalListenTimeMs per album
   - Sort by playCount descending
   - Return top 10
```

## Key Design Decisions

### 1. Song-Level Storage
Statistics are stored only at the song level, not at album/artist level. This approach:
- ✅ Minimizes database size
- ✅ Provides flexibility (can aggregate differently later)
- ✅ Handles multi-artist songs correctly
- ⚠️ Requires computation for album/artist stats

### 2. Listener Pattern
StatsTracker implements `PlaybackStateManager.Listener` rather than directly accessing ExoPlayer:
- ✅ Follows existing app architecture
- ✅ Decoupled from playback implementation details
- ✅ Receives high-level playback events
- ✅ Easy to test and maintain

### 3. Minimum Play Threshold
Songs must be played for at least 3 seconds to count:
- ✅ Prevents accidental skips from inflating stats
- ✅ Similar to industry standards (Spotify uses ~30 seconds, but Auxio is for personal use)
- ✅ Configurable if needed in the future

### 4. Async Recording
Play recording happens asynchronously on IO dispatcher:
- ✅ Doesn't block playback
- ✅ Prevents UI jank
- ✅ Handles errors gracefully
- ✅ Uses coroutines for modern async patterns

### 5. UI as Separate Screen
Stats accessed via menu item rather than as a home tab:
- ✅ Minimal changes to existing tab infrastructure
- ✅ Keeps home screen focused on music browsing
- ✅ Stats are supplementary feature, not primary
- ✅ Easier to implement without modifying tab system

## Dependencies

```
StatsTracker ──depends on──→ PlaybackStateManager (interface)
                          └→ StatsRepository

StatsRepository ──depends on──→ StatsDao
                             └→ MusicRepository

StatsViewModel ──depends on──→ StatsRepository

StatsFragment ──depends on──→ StatsViewModel
```

All dependencies injected via Dagger Hilt for testability and flexibility.
