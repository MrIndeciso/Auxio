# Stats System Implementation

This document describes the implementation of the music listening statistics system inspired by Spotify Wrapped.

## Overview

The Stats system tracks user listening behavior and provides insights into:
- Total listening time across all music
- Total number of plays
- Top 10 most played songs
- Top 10 most played albums
- Top 10 most played artists

Each statistic includes both play count and total listening time.

## Architecture

### 1. Database Layer (`stats/StatsDatabase.kt`)

**SongStats Entity:**
- `songUid`: Unique identifier for the song
- `playCount`: Number of times played
- `totalListenTimeMs`: Total milliseconds listened
- `lastPlayedTimestamp`: When last played

**StatsDao Interface:**
- `getSongStats(songUid)`: Retrieve stats for a specific song
- `getAllStatsByPlayCount()`: Get all songs ordered by play count
- `getAllStatsByListenTime()`: Get all songs ordered by listen time
- `getTotalListenTime()`: Sum of all listening time
- `getTotalPlayCount()`: Sum of all plays
- `insertOrUpdateStats(stats)`: Update or create stats entry
- `nukeStats()`: Clear all statistics

### 2. Repository Layer (`stats/StatsRepository.kt`)

**StatsRepository Interface:**
Provides high-level operations for stats management:
- `recordPlay(song, listenTimeMs)`: Records a song play with duration
- `getAllSongStats()`: Returns enriched song stats with music metadata
- `getAlbumStats()`: Aggregates stats by album
- `getArtistStats()`: Aggregates stats by artist
- `getOverallStats()`: Returns total play count and listen time

**StatsRepositoryImpl:**
- Integrates with `MusicRepository` to resolve UIDs to music objects
- Aggregates song-level stats into album and artist statistics
- Handles errors gracefully with logging

### 3. Tracking Layer (`stats/StatsTracker.kt`)

**StatsTracker:**
Implements `PlaybackStateManager.Listener` to track playback events:
- Attaches to `PlaybackStateManager` on service start
- Tracks current song and playback start time
- Records sessions when:
  - Song changes (`onIndexMoved`, `onQueueChanged`)
  - Playback stops (`onProgressionChanged`)
  - Session ends (`onSessionEnded`)
- Minimum 3-second threshold to count as a play
- Records asynchronously using coroutines (IO dispatcher)

**Integration:**
Injected into `PlaybackServiceFragment` and lifecycle-managed:
- Attached when service starts
- Detached when service releases

### 4. Dependency Injection (`stats/StatsModule.kt`)

**Modules:**
- `StatsModule`: Binds `StatsRepository` interface
- `StatsRoomModule`: Provides Room database and DAO instances
- Both installed in `SingletonComponent` for app-wide availability

### 5. UI Layer

**StatsViewModel (`stats/StatsViewModel.kt`):**
- `@HiltViewModel` for dependency injection
- Exposes `statsData: StateFlow<StatsData?>` with top songs, albums, artists
- Exposes `isLoading: StateFlow<Boolean>` for loading state
- Loads stats on initialization
- Public `loadStats()` method for manual refresh

**StatsFragment (`stats/StatsFragment.kt`):**
- Displays statistics in a scrollable layout
- Overall stats card showing total time and play count
- Three RecyclerViews for top songs, albums, and artists
- Custom adapters for each list type
- Time formatting (hours/minutes for long durations, minutes/seconds for short)

**Layouts:**
- `fragment_stats.xml`: Main stats screen with ScrollView
  - Material card for overall stats
  - Three sections with headers and RecyclerViews
- `item_stat_song.xml`: Song item with rank, name, artist, play count, listen time
- `item_stat_album.xml`: Album item with rank, name, artist, play count, listen time
- `item_stat_artist.xml`: Artist item with rank, name, play count, listen time

**Navigation:**
- Added "Stats" menu item in home toolbar (`toolbar_home.xml`)
- Stats fragment registered in navigation graph (`inner.xml`)
- Handled in `HomeFragment.onMenuItemClick()` to navigate to stats screen

## Usage

Users can access their statistics by:
1. Opening the app
2. Tapping the overflow menu (three dots) in the home screen toolbar
3. Selecting "Stats"

The stats screen will show:
- Overall listening statistics
- Top 10 songs with play counts and listening time
- Top 10 albums with aggregated play counts and listening time
- Top 10 artists with aggregated play counts and listening time

## Data Flow

1. **Recording Plays:**
   ```
   User plays song → PlaybackStateManager → StatsTracker
   → StatsRepository.recordPlay() → StatsDao.insertOrUpdateStats()
   → Database
   ```

2. **Displaying Stats:**
   ```
   User opens Stats → StatsFragment → StatsViewModel.loadStats()
   → StatsRepository.get*Stats() → StatsDao queries
   → MusicRepository (resolve UIDs) → UI update
   ```

## Technical Details

### Play Tracking Logic
- A "play" is counted when a song is listened to for at least 3 seconds
- Listen time is calculated from playback start to:
  - Song change
  - Playback pause/stop
  - Session end
- Tracks are recorded even if skipped, paused, or interrupted

### Data Aggregation
- Album stats: Sum of all play counts and listen times for songs in the album
- Artist stats: Sum across all songs where artist is credited (handles multi-artist songs)
- Stats are computed on-demand from song-level data

### Performance Considerations
- Stats are stored at song-level only to minimize database size
- Aggregations computed in-memory when displaying stats
- Database queries use indexed columns for performance
- Async operations prevent UI blocking

## Future Enhancements

Possible future improvements:
- Time range filtering (last 7 days, 30 days, year, all time)
- Genre statistics
- Listening trends over time
- Export stats to share
- More detailed per-song statistics
- Playlist statistics
- Listening streaks
