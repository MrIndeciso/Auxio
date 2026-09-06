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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentSongHistoryBinding
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.stats.PlayEvent
import org.oxycblt.auxio.stats.SingleViewAdapter
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.systemBarInsetsCompat

/** A fragment to display the history of played songs. */
@AndroidEntryPoint
class SongHistoryFragment : Fragment() {
    private var _binding: FragmentSongHistoryBinding? = null
    private val binding
        get() = _binding!!

    private val viewModel: SongHistoryViewModel by viewModels()

    @Inject lateinit var musicRepository: MusicRepository
    private val dateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { isLenient = false }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
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
                bottom = insets.systemBarInsetsCompat.bottom + extra,
            )
            insets
        }

        val historyAdapter = SongHistoryAdapter(musicRepository, ::showEntryActions)
        val controls = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val filter = MaterialButton(requireContext())
        val status =
            TextView(requireContext()).apply {
                setPadding(16, 8, 16, 8)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        val loading = ProgressBar(requireContext())
        val retry =
            MaterialButton(requireContext()).apply {
                setText(R.string.stats_retry)
                setOnClickListener { viewModel.retry() }
            }
        val rangeLabel =
            TextView(requireContext()).apply {
                text = viewModel.rangeLabel
                isVisible = text.isNotEmpty()
                setPadding(16, 8, 16, 8)
            }
        controls.addView(rangeLabel)
        controls.addView(filter)
        controls.addView(loading)
        controls.addView(status)
        controls.addView(retry)
        filter.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setItems(
                    arrayOf(
                        getString(R.string.stats_history_all),
                        getString(R.string.stats_history_unavailable),
                    )
                ) { _, which ->
                    viewModel.filterUnavailable(which == 1)
                }
                .show()
        }
        val more =
            MaterialButton(requireContext()).apply {
                setText(R.string.stats_load_more)
                setOnClickListener { viewModel.more() }
            }
        binding.historyRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter =
                ConcatAdapter(SingleViewAdapter(controls), historyAdapter, SingleViewAdapter(more))
        }

        var restored = false
        collectImmediately(viewModel.state) { state ->
            historyAdapter.submitList(state.events) {
                historyAdapter.notifyDataSetChanged()
                if (!restored && !state.loading) {
                    _binding
                        ?.historyRecycler
                        ?.layoutManager
                        ?.onRestoreInstanceState(viewModel.scroll)
                    restored = true
                }
            }
            filter.setText(
                if (state.unavailable) R.string.stats_history_unavailable
                else R.string.stats_history_all
            )
            loading.isVisible = state.loading
            status.text =
                state.error
                    ?: if (!state.loading && state.events.isEmpty())
                        getString(R.string.stats_history_empty)
                    else ""
            status.isVisible = status.text.isNotEmpty()
            retry.isVisible = state.error != null
            more.isVisible = state.more
        }
    }

    override fun onPause() {
        viewModel.scroll = _binding?.historyRecycler?.layoutManager?.onSaveInstanceState()
        super.onPause()
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
                    getString(R.string.lbl_delete_history_entry),
                    getString(R.string.stats_history_song),
                )
            ) { _, which ->
                when (which) {
                    0 -> showEditDialog(event)
                    1 -> confirmDelete(event)
                    2 ->
                        findNavController()
                            .navigate(
                                R.id.songHistoryFragment,
                                bundleOf("songUid" to event.songUid),
                            )
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

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lbl_edit_history_entry)
                .setView(view)
                .setPositiveButton(R.string.lbl_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val timestamp = parseTimestamp(timeInput.text?.toString())
                val duration = parseDuration(durationInput.text?.toString())
                timeInput.error =
                    if (timestamp == null) getString(R.string.err_history_invalid_input) else null
                durationInput.error =
                    if (duration == null) getString(R.string.err_history_invalid_input) else null
                if (timestamp != null && duration != null) {
                    val save = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    save.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (viewModel.updateEvent(event.id, event.songUid, timestamp, duration))
                            dialog.dismiss()
                        else {
                            durationInput.error = viewModel.state.value.error
                            save.isEnabled = true
                        }
                    }
                }
            }
        }
        dialog.show()
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

    private fun parseTimestamp(raw: String?): Long? = HistoryInput.timestamp(raw, dateFormat)

    private fun parseDuration(raw: String?): Long? = HistoryInput.duration(raw)

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
