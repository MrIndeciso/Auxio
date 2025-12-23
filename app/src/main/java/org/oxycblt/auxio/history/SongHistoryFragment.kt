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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentSongHistoryBinding
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.systemBarInsetsCompat

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

        val historyAdapter = SongHistoryAdapter(musicRepository)
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
}
