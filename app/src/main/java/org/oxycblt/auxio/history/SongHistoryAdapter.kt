/*
 * Copyright (c) 2024 Auxio Project
 * SongHistoryAdapter.kt is part of Auxio.
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

package org.oxycblt.auxio.history

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemHistorySongBinding
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.stats.PlayEvent
import org.oxycblt.auxio.R

class SongHistoryAdapter(
    private val musicRepository: MusicRepository,
    private val onItemClick: (PlayEvent) -> Unit
) :
    ListAdapter<PlayEvent, SongHistoryAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistorySongBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemHistorySongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playEvent: PlayEvent) {
            val context = itemView.context
            val song = musicRepository.library?.findSong(playEvent.songUid)
            if (song != null) {
                binding.historyCover.bind(song)
            } else {
                binding.historyCover.bind(
                    emptyList(),
                    context.getString(R.string.cdc_unknown),
                    R.drawable.ic_album_24)
            }
            binding.songName.text = song?.name?.resolve(context) ?: context.getString(R.string.cdc_unknown)
            binding.artistName.text =
                song?.artists?.joinToString { it.name.resolve(context) }
                    ?: context.getString(R.string.cdc_unknown)

            val relativeTime =
                DateUtils.getRelativeDateTimeString(
                    context,
                    playEvent.timestamp,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.WEEK_IN_MILLIS,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
            val listenDuration = formatDuration(context, playEvent.listenTimeMs)
            binding.timestamp.text = relativeTime
            binding.duration.text = listenDuration
            binding.root.setOnClickListener { onItemClick(playEvent) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<PlayEvent>() {
        override fun areItemsTheSame(oldItem: PlayEvent, newItem: PlayEvent):
            Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PlayEvent, newItem: PlayEvent):
            Boolean = oldItem == newItem
    }

    private fun formatDuration(context: android.content.Context, durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> context.getString(R.string.fmt_hours_minutes, hours, minutes)
            minutes > 0 ->
                context.getString(R.string.fmt_minutes_seconds, minutes, seconds)
            else ->
                context.resources.getQuantityString(
                    R.plurals.fmt_seconds, seconds.toInt().coerceAtLeast(1), seconds)
        }
    }
}
