/*
 * Copyright (c) 2024 Auxio Project
 * SongHistoryFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentSongHistoryBinding
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.stats.PlayEvent
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.auxio.util.showToast

/**
 * A fragment to display the history of played songs.
 */
@AndroidEntryPoint
class SongHistoryFragment : Fragment() {
    private var _binding: FragmentSongHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SongHistoryViewModel by viewModels()

    @Inject
    lateinit var musicRepository: MusicRepository
    private val dateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { isLenient = false }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSongHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.historyRecycler.setOnApplyWindowInsetsListener { v, insets ->
            val extra = resources.getDimensionPixelSize(R.dimen.spacing_medium)
            v.updatePadding(
                top = insets.systemBarInsetsCompat.top + extra,
                bottom = insets.systemBarInsetsCompat.bottom + extra)
            insets
        }

        val historyAdapter = SongHistoryAdapter(musicRepository, ::showEntryActions)
        binding.historyRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }

        collectImmediately(viewModel.history) { history ->
            historyAdapter.submitList(history)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showEntryActions(event: PlayEvent) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_history_entry)
            .setItems(
                arrayOf(
                    getString(R.string.lbl_edit_history_entry),
                    getString(R.string.lbl_delete_history_entry)
                )
            ) { _, which ->
                when (which) {
                    0 -> showEditDialog(event)
                    1 -> confirmDelete(event)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditDialog(event: PlayEvent) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_history, null)
        val timeInput = view.findViewById<TextInputEditText>(R.id.history_edit_time)
        val durationInput = view.findViewById<TextInputEditText>(R.id.history_edit_duration)

        timeInput.setText(dateFormat.format(Date(event.timestamp)))
        durationInput.setText(formatDurationInput(event.listenTimeMs))

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_edit_history_entry)
            .setView(view)
            .setPositiveButton(R.string.lbl_save) { _, _ ->
                val timestamp = parseTimestamp(timeInput.text?.toString())
                val duration = parseDuration(durationInput.text?.toString())
                if (timestamp == null || duration == null) {
                    context.showToast(R.string.err_history_invalid_input)
                    return@setPositiveButton
                }
                viewModel.updateEvent(event.id, event.songUid, timestamp, duration)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(event: PlayEvent) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_delete_history_entry)
            .setMessage(R.string.msg_delete_history_entry)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                viewModel.deleteEvent(event.id, event.songUid)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseTimestamp(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            dateFormat.parse(text)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDuration(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(":").map { it.trim() }
        val seconds =
            when (parts.size) {
                1 -> parts[0].toLongOrNull() ?: return null
                2 -> {
                    val minutes = parts[0].toLongOrNull() ?: return null
                    val secs = parts[1].toLongOrNull() ?: return null
                    minutes * 60 + secs
                }
                3 -> {
                    val hours = parts[0].toLongOrNull() ?: return null
                    val minutes = parts[1].toLongOrNull() ?: return null
                    val secs = parts[2].toLongOrNull() ?: return null
                    hours * 3600 + minutes * 60 + secs
                }
                else -> return null
            }
        return if (seconds > 0) seconds * 1000 else null
    }

    private fun formatDurationInput(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
