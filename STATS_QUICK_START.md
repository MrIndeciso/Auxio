# Stats System - Quick Start Guide

## What Was Added

A complete listening statistics system inspired by Spotify Wrapped that tracks:
- How many times each song has been played
- Total listening time for songs, albums, and artists
- Overall listening statistics

## Key Files

### Backend (Kotlin)

1. **`app/src/main/java/org/oxycblt/auxio/stats/StatsDatabase.kt`**
   - Room database with `SongStats` entity
   - Stores: song UID, play count, total listen time, last played timestamp

2. **`app/src/main/java/org/oxycblt/auxio/stats/StatsRepository.kt`**
   - Business logic for recording plays and retrieving stats
   - Aggregates song stats into album and artist stats
   - Key methods:
     - `recordPlay(song, listenTimeMs)` - Records a play
     - `getAllSongStats()` - Gets all song statistics
     - `getAlbumStats()` - Aggregates by album
     - `getArtistStats()` - Aggregates by artist
     - `getOverallStats()` - Gets totals

3. **`app/src/main/java/org/oxycblt/auxio/stats/StatsTracker.kt`**
   - Listens to playback events via `PlaybackStateManager`
   - Automatically records plays when:
     - Song changes
     - Playback stops
     - Session ends
   - Only counts plays ≥ 3 seconds

4. **`app/src/main/java/org/oxycblt/auxio/stats/StatsViewModel.kt`**
   - Provides stats data to UI via StateFlow
   - Loads top 10 songs, albums, artists, and overall stats

5. **`app/src/main/java/org/oxycblt/auxio/stats/StatsFragment.kt`**
   - Displays statistics in RecyclerViews
   - Shows overall stats card with total time and plays
   - Three sections: Top Songs, Top Albums, Top Artists

6. **`app/src/main/java/org/oxycblt/auxio/stats/StatsModule.kt`**
   - Dagger Hilt dependency injection setup
   - Provides database and repository instances

### Frontend (XML)

7. **`app/src/main/res/layout/fragment_stats.xml`**
   - Main stats screen layout with ScrollView
   - Overall stats card + 3 RecyclerViews

8. **`app/src/main/res/layout/item_stat_song.xml`**
   - List item for song stats (rank, name, artist, plays, time)

9. **`app/src/main/res/layout/item_stat_album.xml`**
   - List item for album stats (rank, name, artist, plays, time)

10. **`app/src/main/res/layout/item_stat_artist.xml`**
    - List item for artist stats (rank, name, plays, time)

### Integration Points

11. **`app/src/main/java/org/oxycblt/auxio/playback/service/PlaybackServiceFragment.kt`**
    - Modified to inject and attach `StatsTracker`
    - Ensures stats are recorded during playback

12. **`app/src/main/java/org/oxycblt/auxio/home/HomeFragment.kt`**
    - Added menu handler for "Stats" item
    - Navigates to stats screen

13. **`app/src/main/res/menu/toolbar_home.xml`**
    - Added "Stats" menu item to home toolbar

14. **`app/src/main/res/navigation/inner.xml`**
    - Added stats fragment to navigation graph
    - Connected navigation action from home to stats

15. **`app/src/main/res/values/strings.xml`**
    - Added stats-related string resources

## How It Works

### Recording Stats
```
1. User plays a song
2. ExoPlayer starts playback
3. PlaybackStateManager notifies listeners
4. StatsTracker receives onProgressionChanged(isPlaying=true)
5. StatsTracker starts tracking time
6. User changes song or stops playback
7. StatsTracker calculates duration
8. If duration >= 3 seconds:
   - StatsTracker calls StatsRepository.recordPlay()
   - Repository updates or creates SongStats entry
   - Database saves the updated stats
```

### Viewing Stats
```
1. User taps "Stats" in home menu
2. HomeFragment navigates to StatsFragment
3. StatsFragment observes StatsViewModel
4. ViewModel calls StatsRepository to load stats
5. Repository queries database and aggregates data
6. ViewModel updates StateFlow
7. Fragment updates UI with stats
```

## Database Schema

```sql
CREATE TABLE SongStats (
    songUid TEXT PRIMARY KEY,
    playCount INTEGER,
    totalListenTimeMs INTEGER,
    lastPlayedTimestamp INTEGER
);
```

## Testing the Feature

To test the stats system:

1. **Build and run the app**
   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug
   ```

2. **Play some music**
   - Play different songs for varying durations
   - Skip some songs (< 3 seconds) - these won't count
   - Play some songs multiple times

3. **View your stats**
   - Open the app
   - Tap the overflow menu (⋮) in the home toolbar
   - Select "Stats"
   - Verify the stats display correctly

## Customization Ideas

Want to customize the stats system? Here are some ideas:

1. **Change the minimum play threshold**
   - Edit `StatsTracker.kt` line ~111: `if (listenTimeMs >= 3000)`
   - Change 3000 to desired milliseconds

2. **Show more/fewer top items**
   - Edit `StatsViewModel.kt` line ~50
   - Change `topSongs = songStats.take(10)` to desired count

3. **Add genre stats**
   - Add `getGenreStats()` to `StatsRepository.kt`
   - Aggregate by song.genre similar to artists
   - Update UI to display genre stats

4. **Add time range filters**
   - Add date range parameters to repository methods
   - Filter stats by lastPlayedTimestamp
   - Add UI controls for selecting time range

## Troubleshooting

**Stats not recording:**
- Check if StatsTracker is attached in PlaybackServiceFragment
- Verify Dagger Hilt is injecting dependencies correctly
- Check logcat for "StatsTracker" logs

**Stats not displaying:**
- Verify navigation action exists in inner.xml
- Check if StatsViewModel is loading data
- Look for errors in logcat

**Build errors:**
- Run `./gradlew spotlessApply` to fix formatting
- Ensure all submodules are initialized
- Check that Room and Hilt dependencies are present

## Performance Notes

- Stats recording is async and won't block playback
- Database operations use Room's suspend functions
- Aggregations computed on-demand (not stored)
- Should handle large music libraries efficiently
- Consider adding indices if queries become slow

## Future Enhancements

See STATS_IMPLEMENTATION.md section "Future Enhancements" for ideas:
- Time range filtering
- Export stats
- Listening trends
- Per-song detailed view
- Playlist statistics
- And more!
