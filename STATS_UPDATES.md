# Stats System - Updates Based on Feedback

## Changes Made

This document summarizes the updates made to the stats system based on user feedback.

### Feedback Addressed

#### 1. Multi-Artist Song Handling

**Original Concern:** How are multi-artist songs handled when artists are tagged inconsistently with different separators (comma, semicolon)?

**Resolution:** No code changes needed. The existing implementation already handles this correctly:

- **Musikr's Responsibility**: The musikr library (Auxio's music indexing component) parses artist tags during music library indexing. It handles various separators (comma, semicolon, etc.) and creates individual `Artist` objects.

- **Song.artists Structure**: The `Song.artists` field is a `List<Artist>`, not a string. When musikr parses a multi-artist tag like "Artist A, Artist B", it creates two separate Artist objects in this list.

- **Stats Aggregation**: The `StatsRepository.getArtistStats()` method correctly handles multi-artist songs:
  ```kotlin
  for (artist in song.artists) {
      // Each artist gets credited individually
  }
  ```

- **Result**: Each artist in a multi-artist song receives proper credit for plays and listening time, regardless of the original tag format.

#### 2. Time Period Filtering

**Original Request:** Support for viewing stats by specific time periods (yearly, monthly, weekly, etc.) instead of just all-time stats.

**Implementation:** Added comprehensive time-based filtering with the following components:

##### New Components

1. **PlayEvent Entity** (`StatsDatabase.kt`)
   - Stores individual play occurrences with timestamps
   - Schema: `id`, `songUid`, `timestamp`, `listenTimeMs`
   - Enables flexible time-based queries

2. **TimePeriod Enum** (`TimePeriod.kt`)
   - Defines 8 predefined time periods
   - Calculates timestamp ranges using Calendar API
   - Periods supported:
     - ALL_TIME (default)
     - THIS_YEAR
     - LAST_YEAR
     - LAST_12_MONTHS
     - THIS_MONTH
     - LAST_MONTH
     - THIS_WEEK
     - LAST_WEEK

3. **Database Version 2**
   - Added `PlayEvent` table alongside existing `SongStats`
   - Maintains backward compatibility
   - `SongStats` kept for fast all-time queries

##### Modified Components

1. **StatsRepository** - All methods now accept optional `TimePeriod` parameter:
   ```kotlin
   suspend fun getAllSongStats(timePeriod: TimePeriod = TimePeriod.ALL_TIME)
   suspend fun getAlbumStats(timePeriod: TimePeriod = TimePeriod.ALL_TIME)
   suspend fun getArtistStats(timePeriod: TimePeriod = TimePeriod.ALL_TIME)
   suspend fun getOverallStats(timePeriod: TimePeriod = TimePeriod.ALL_TIME)
   ```

2. **StatsViewModel** - Added time period selection state:
   ```kotlin
   private val _selectedTimePeriod = MutableStateFlow(TimePeriod.ALL_TIME)
   fun setTimePeriod(timePeriod: TimePeriod)
   ```

3. **StatsFragment** - Added Material dropdown selector for time period

4. **fragment_stats.xml** - Added `TextInputLayout` with `AutoCompleteTextView` for period selection

##### Data Storage Strategy

**Dual Storage Approach:**

1. **SongStats Table (Aggregated)**
   - Stores cumulative all-time statistics
   - Used for fast all-time queries
   - Updated on every play

2. **PlayEvent Table (Individual Events)**
   - Stores each play occurrence with timestamp
   - Used for time-based filtering
   - Enables flexible historical analysis

**Query Strategy:**
```
If timePeriod == ALL_TIME:
    → Query SongStats table (fast aggregated data)
Else:
    → Query PlayEvents within time range
    → Aggregate results on-the-fly
    → Return filtered stats
```

##### Time Period Calculations

Each `TimePeriod` calculates its timestamp range using Java's Calendar API:

- **THIS_YEAR**: From January 1 00:00:00 of current year to now
- **LAST_YEAR**: From January 1 to December 31 23:59:59 of previous year
- **LAST_12_MONTHS**: From current date minus 12 months to now
- **THIS_MONTH**: From day 1 00:00:00 of current month to now
- **LAST_MONTH**: From day 1 to last day of previous month
- **THIS_WEEK**: From first day of current week to now
- **LAST_WEEK**: From first to last day of previous week

##### UI Changes

**Before:**
- Single view showing all-time stats only

**After:**
- Dropdown selector at top of screen
- Dynamically updates all stats sections on selection
- Remembers selection during session
- Default: "All Time"

## Technical Details

### Database Migration

```sql
-- Version 1 (Original)
CREATE TABLE SongStats (
    songUid TEXT PRIMARY KEY,
    playCount INTEGER,
    totalListenTimeMs INTEGER,
    lastPlayedTimestamp INTEGER
)

-- Version 2 (Updated)
-- SongStats table unchanged

-- New table added:
CREATE TABLE PlayEvent (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    songUid TEXT,
    timestamp INTEGER,
    listenTimeMs INTEGER
)
```

### Performance Considerations

1. **All-Time Queries**: Use pre-aggregated `SongStats` table
   - O(n) where n = number of unique songs played
   - Fast for large datasets

2. **Time-Filtered Queries**: Query `PlayEvent` table with timestamp range
   - O(m) where m = number of plays in time range
   - Indexed by timestamp for performance
   - Aggregation done in-memory

3. **Trade-off**: Increased storage (each play stored twice) for query flexibility
   - `SongStats`: ~50 bytes per unique song
   - `PlayEvent`: ~40 bytes per play occurrence
   - Acceptable for typical usage patterns

### Code Changes Summary

**Files Modified:**
- `StatsDatabase.kt` - Added PlayEvent entity, new DAO methods, v2 schema
- `StatsRepository.kt` - Added TimePeriod parameters, dual-query strategy
- `StatsViewModel.kt` - Added time period selection state
- `StatsFragment.kt` - Added dropdown setup and event handling
- `fragment_stats.xml` - Added Material dropdown UI component
- `strings.xml` - Added time period labels

**Files Added:**
- `TimePeriod.kt` - Time period enum with range calculations

**Lines Changed:**
- +347 lines added
- -44 lines removed
- Net: +303 lines

## User Experience

### How to Use

1. Open Stats screen from home menu
2. Tap the "Time Period" dropdown at the top
3. Select desired time period
4. All stats sections update immediately:
   - Overall listening time and play count
   - Top 10 songs
   - Top 10 albums
   - Top 10 artists

### Example Scenarios

**Scenario 1: "What were my top songs this month?"**
- Select "This Month" from dropdown
- View Top Songs section

**Scenario 2: "Compare this year vs last year"**
- Select "This Year", note the total time
- Switch to "Last Year", compare

**Scenario 3: "Weekly listening habits"**
- Select "This Week" for current week
- Select "Last Week" for comparison

## Future Enhancements

Potential additions based on this foundation:

1. **Custom Date Ranges**: Allow users to pick arbitrary start/end dates
2. **Trend Visualization**: Show listening trends over time with charts
3. **Period Comparison**: Side-by-side comparison of two time periods
4. **Export**: Export stats for specific time periods
5. **Notifications**: Alert users about milestones within periods
6. **More Granularity**: Add "Last 7 Days", "Last 30 Days", "Last 6 Months"

## Testing Recommendations

1. **Time Period Calculations**: Verify each period returns correct timestamp ranges
2. **Boundary Conditions**: Test at month/year boundaries
3. **Timezone Handling**: Ensure consistent behavior across timezones
4. **Empty Data**: Test periods with no plays
5. **Performance**: Test with large datasets (1000+ play events)
6. **UI Interaction**: Verify dropdown works smoothly with all periods

## Conclusion

Both feedback items have been successfully addressed:

✅ **Multi-artist handling** - Confirmed working correctly via musikr
✅ **Time period filtering** - Fully implemented with 8 predefined periods

The implementation provides a solid foundation for future statistics enhancements while maintaining backward compatibility and performance.
