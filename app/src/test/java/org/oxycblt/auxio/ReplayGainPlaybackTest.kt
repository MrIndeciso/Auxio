/*
 * Copyright (c) 2026 Auxio Project
 * ReplayGainPlaybackTest.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.replaygain.*
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.ReplayGainAdjustment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReplayGainPlaybackTest {
    private fun checkGain(
        mode: ReplayGainMode,
        track: Float?,
        album: Float?,
        expected: Float,
        preamp: ReplayGainPreAmp = ReplayGainPreAmp(0f, 0f),
        inAlbum: Boolean = false,
    ) {
        val song = mockk<Song>(relaxed = true)
        val parent = mockk<Album>()
        every { song.album } returns parent
        every { song.replayGainAdjustment } returns ReplayGainAdjustment(track, album)
        val manager = mockk<PlaybackStateManager>()
        every { manager.currentSong } returns song
        every { manager.parent } returns if (inAlbum) parent else null
        val settings = mockk<PlaybackSettings>()
        every { settings.replayGainMode } returns mode
        every { settings.replayGainPreAmp } returns preamp
        val processor = ReplayGainAudioProcessor(manager, settings)
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        repeat(2) {
            processor.onReplayGainSettingsChanged()
            val input =
                ByteBuffer.allocateDirect(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(10000)
                    .putShort(-10000)
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            val amplitude = (10000 * 10.0.pow(expected / 20.0)).toInt()
            assertEquals(amplitude.toDouble(), output.short.toDouble(), 1.0)
            assertEquals(-amplitude.toDouble(), output.short.toDouble(), 1.0)
            assertEquals(input.limit(), input.position())
        }
    }

    @Test
    fun albumReportedQuietUsesTrackGainOnceAndOffIsUnity() {
        checkGain(ReplayGainMode.TRACK, -6.98f, -9.48f, -6.98f)
        checkGain(ReplayGainMode.ALBUM, -6.98f, -9.48f, -9.48f)
        checkGain(ReplayGainMode.OFF, -6.98f, -9.48f, 0f)
    }

    @Test
    fun selectionFallbackAndPreampAreAppliedOnce() {
        checkGain(ReplayGainMode.TRACK, null, -6f, -4f, ReplayGainPreAmp(2f, -3f))
        checkGain(ReplayGainMode.ALBUM, -6f, null, -4f, ReplayGainPreAmp(2f, -3f))
        checkGain(ReplayGainMode.TRACK, null, null, -3f, ReplayGainPreAmp(2f, -3f))
        checkGain(ReplayGainMode.TRACK, 0f, -6f, 2f, ReplayGainPreAmp(2f, -3f))
        checkGain(ReplayGainMode.DYNAMIC, -3f, -6f, -6f, inAlbum = true)
        checkGain(ReplayGainMode.DYNAMIC, -3f, -6f, -3f)
    }
}
