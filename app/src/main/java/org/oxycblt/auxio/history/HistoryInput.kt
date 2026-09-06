/*
 * Copyright (c) 2026 Auxio Project
 * HistoryInput.kt is part of Auxio.
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

import java.text.ParsePosition
import java.text.SimpleDateFormat

/** Strict parsing shared by the edit dialog and deterministic validation. */
object HistoryInput {
    fun timestamp(raw: String?, format: SimpleDateFormat): Long? {
        val text = raw?.trim().orEmpty()
        val position = ParsePosition(0)
        val parsed = format.parse(text, position) ?: return null
        return parsed.time.takeIf { it >= 0 && position.index == text.length }
    }

    fun duration(raw: String?): Long? {
        val parts = raw?.trim()?.split(':') ?: return null
        if (parts.size !in 1..3) return null
        val numbers = parts.map { it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: return null }
        if (numbers.drop(1).any { it >= 60 }) return null
        return try {
            var seconds = 0L
            for (number in numbers) seconds = Math.addExact(Math.multiplyExact(seconds, 60), number)
            Math.multiplyExact(seconds, 1000).takeIf { it > 0 }
        } catch (e: ArithmeticException) {
            null
        }
    }
}
