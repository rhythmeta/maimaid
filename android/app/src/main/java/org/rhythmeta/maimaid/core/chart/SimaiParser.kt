package org.rhythmeta.maimaid.core.chart

/**
 * Faithful port of the reference engine's simai parser
 * (`packages/maimai-chart-engine/src/core/parser/SimaiParser.ts`).
 *
 * Timing advances only on `,`; BPM, divisor and hi-speed markers take effect in place.
 * The parser intentionally keeps the upstream quirks (1 ms delay-marker offset,
 * one-measure lead-in) because the renderer depends on them.
 */
object SimaiParser {
    private val inoteMarkers = (1..7).map { "&inote_$it=" }
    private val inoteLineRegex = Regex("^&inote_(\\d)=(.*)$", RegexOption.IGNORE_CASE)
    private val bpmRegex = Regex("^\\((\\d+(?:\\.\\d+)?)\\)")
    private val divisorRegex = Regex("^\\{(\\d+(?:\\.\\d+)?)\\}")
    private val hiSpeedRegex = Regex("^<HS\\*([-+]?\\d*\\.?\\d+)>", RegexOption.IGNORE_CASE)
    private val holdRegex = Regex(
        "^(\\d+)[hbx]{1,3}\\[(?:([\\d.]+):([\\d.]+)|#([\\d.]+))\\][bx]*$",
        RegexOption.IGNORE_CASE,
    )
    private val slideRegex = Regex("^(\\d+)([bx?!@]*[-><^vpqszVw*]+.*)$", RegexOption.IGNORE_CASE)
    private val slidePathCharRegex = Regex("[-><^vpqszVw]")
    private val slidePathStartRegex = Regex("[-><^vpqszVw*]")
    private val touchRegex = Regex(
        "^([ABCDE])(\\d*)([hbfx]*)(?:\\[(?:([\\d.]+):([\\d.]+)|#([\\d.]+))\\])?$",
        RegexOption.IGNORE_CASE,
    )
    private val tapRegex = Regex("^(\\d+)([bx$]*)$", RegexOption.IGNORE_CASE)
    private val multiTimingBlockRegex = Regex("\\[[\\d.:#+]+\\]")
    private val secondsWithDelayRegex = Regex("\\[([\\d.]+)##([\\d.]+)\\]")
    private val secondsOnlyBlockRegex = Regex("\\[#([\\d.]+)\\]")
    private val timingBlockRegex = Regex("\\[(?:([\\d.]+)#)?([\\d.]+):([\\d.]+)(?:##([\\d.]+))?\\]")
    private val inlineBpmRegex = Regex("\\((\\d+(?:\\.\\d+)?)\\)")

    /** Difficulty slots present in a maidata text, mirroring `getAvailableDifficulties`. */
    fun availableDifficulties(text: String): Set<Int> {
        val lower = text.lowercase()
        val available = mutableSetOf<Int>()
        inoteMarkers.forEachIndexed { index, marker ->
            if (lower.contains(marker)) available += index + 1
        }
        if (available.isEmpty()) {
            val body = text.split("\n")
                .map { if (it.endsWith("\r")) it.dropLast(1) else it }
                .filterNot { it.trimStart().startsWith("&") }
                .joinToString("")
            if (body.any { it in '1'..'8' }) available += 4
        }
        return available
    }

    /** Chart keys (`inote_N`) present in a maidata text, in ascending difficulty order. */
    fun chartSlots(text: String): List<Int> = availableDifficulties(text).sorted()

    /**
     * Parses a simai chart. Without an explicit difficulty the highest available slot is used;
     * a single-difficulty chart falls back to slot 4.
     */
    fun parse(text: String, difficulty: Int? = null): Chart {
        require(text.isNotEmpty()) { "Invalid input: expected a non-empty string" }

        val lines = text.split("\n").map { if (it.endsWith("\r")) it.dropLast(1) else it }
        require(lines.any { it.startsWith("&") }) {
            "Invalid simai format: expected & metadata lines (e.g. &title=, &inote_4=)"
        }

        var metadataBpm = Double.NaN
        var title = ""
        var artist = ""
        var designer = ""
        val level = mutableMapOf<Int, String>()
        val designers = mutableMapOf<Int, String>()
        val inotes = mutableMapOf<Int, String>()
        var firstSec: Double? = null

        var currentInote: Int? = null
        val currentContent = mutableListOf<String>()

        fun flushInote() {
            val slot = currentInote ?: return
            inotes[slot] = currentContent.joinToString("\n")
            currentInote = null
            currentContent.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            val inoteMatch = inoteLineRegex.find(trimmed)
            if (inoteMatch != null) {
                flushInote()
                currentInote = inoteMatch.groupValues[1].toInt()
                val body = inoteMatch.groupValues[2]
                if (body.isNotEmpty()) currentContent += body
                continue
            }
            val inote = currentInote
            if (inote != null) {
                if (trimmed.startsWith("&") && !trimmed.startsWith("&inote")) {
                    flushInote()
                    val meta = parseMetadataLine(trimmed)
                    metadataBpm = meta.bpm ?: metadataBpm
                    title = meta.title ?: title
                    artist = meta.artist ?: artist
                    designer = meta.designer ?: designer
                    meta.level?.let { level.putAll(it) }
                    meta.designers?.let { designers.putAll(it) }
                    firstSec = meta.firstSec ?: firstSec
                } else if (!trimmed.startsWith("&")) {
                    currentContent += line
                }
                continue
            }
            if (trimmed.startsWith("&")) {
                val meta = parseMetadataLine(trimmed)
                metadataBpm = meta.bpm ?: metadataBpm
                title = meta.title ?: title
                artist = meta.artist ?: artist
                designer = meta.designer ?: designer
                meta.level?.let { level.putAll(it) }
                meta.designers?.let { designers.putAll(it) }
                firstSec = meta.firstSec ?: firstSec
            }
        }
        flushInote()

        val availableDiffs = inotes.keys.sortedDescending()
        var selectedDifficulty = difficulty?.takeIf { it > 0 }
        if (selectedDifficulty == null && availableDiffs.isNotEmpty()) {
            selectedDifficulty = availableDiffs.first()
        }

        var chartBody: String
        if (selectedDifficulty != null && inotes.containsKey(selectedDifficulty)) {
            chartBody = inotes.getValue(selectedDifficulty)
        } else if (availableDiffs.isEmpty()) {
            chartBody = lines.asSequence()
                .map { it.trim() }
                .filterNot { it.isEmpty() || it.startsWith("&") }
                .joinToString("")
            selectedDifficulty = 4
        } else {
            throw IllegalArgumentException(
                "Difficulty $difficulty not found in chart. Available: ${availableDiffs.joinToString(", ")}",
            )
        }

        val selectedDesigner = designers[selectedDifficulty] ?: designer

        if (metadataBpm.isNaN()) {
            inlineBpmRegex.find(chartBody)?.let { metadataBpm = it.groupValues[1].toDouble() }
        }
        // Upstream falls back to 120 so charts without BPM metadata still render.
        if (metadataBpm.isNaN()) metadataBpm = 120.0

        val parsed = parseNotes(chartBody, metadataBpm)
        val notes = parsed.notes
        var firstBpm = parsed.firstBpm

        require(!firstBpm.isNaN()) { "Simai 文件缺少 BPM 声明" }

        var maxMeasure = 0
        var maxTiming = 0.0
        for (note in notes) {
            if (note.measure > maxMeasure) maxMeasure = note.measure
            var endTiming = note.timing
            when (note) {
                is HoldStartNote -> endTiming = note.timing + note.duration
                is TouchHoldStartNote -> endTiming = note.timing + note.duration
                is SlideNote -> {
                    var maxEndMs = 0.0
                    val count = maxOf(note.allDelayMs.size, note.allDurationMs.size)
                    for (index in 0 until count) {
                        val delay = note.allDelayMs.getOrNull(index) ?: 0.0
                        val duration = note.allDurationMs.getOrNull(index) ?: 0.0
                        if (delay + duration > maxEndMs) maxEndMs = delay + duration
                    }
                    endTiming = note.timing + maxEndMs * note.bpm / 60_000.0
                }
                else -> Unit
            }
            if (endTiming > maxTiming) maxTiming = endTiming
        }
        maxMeasure = maxOf(maxMeasure, kotlin.math.ceil(maxTiming / 4.0).toInt())

        val leadInMs = 60_000.0 * 4.0 / firstBpm
        val shiftedNotes = notes.map { it.withLeadIn(4.0, leadInMs) }

        val bpmEvents = parsed.bpmEvents.map { it.copy(timing = it.timing + 4.0) }
            .toMutableList()
            .apply { add(0, BpmEvent(0.0, firstBpm)) }
        val divisorEvents = parsed.divisorEvents.map { it.copy(timing = it.timing + 4.0) }
            .toMutableList()
            .apply { add(0, DivisorEvent(0.0, 4.0)) }
            .sortedBy { it.timing }

        return Chart(
            title = title,
            artist = artist,
            designer = selectedDesigner,
            bpm = firstBpm,
            level = level,
            designers = designers,
            difficulty = selectedDifficulty,
            availableDifficulties = inotes.keys.toSet(),
            measures = maxMeasure + 2,
            notes = shiftedNotes,
            bpmEvents = bpmEvents,
            divisorEvents = divisorEvents,
            firstMs = firstSec?.times(1000.0),
        )
    }

    private class MetadataLine(
        val bpm: Double? = null,
        val title: String? = null,
        val artist: String? = null,
        val designer: String? = null,
        val level: Map<Int, String>? = null,
        val designers: Map<Int, String>? = null,
        val firstSec: Double? = null,
    )

    private fun parseMetadataLine(line: String): MetadataLine {
        val eqIndex = line.indexOf('=')
        if (eqIndex < 0) return MetadataLine()
        val key = line.substring(1, eqIndex).trim()
        val value = line.substring(eqIndex + 1).trim()
        return when {
            key == "title" -> MetadataLine(title = value)
            key == "artist" -> MetadataLine(artist = value)
            key == "des" -> MetadataLine(designer = value)
            key.startsWith("des_") -> key.drop(4).toIntOrNull()
                ?.takeIf { it in 1..7 }
                ?.let { MetadataLine(designers = mapOf(it to value)) }
                ?: MetadataLine()
            key.startsWith("lv_") -> key.drop(3).toIntOrNull()
                ?.takeIf { it in 1..7 }
                ?.let { MetadataLine(level = mapOf(it to value)) }
                ?: MetadataLine()
            key == "bpm" -> value.toDoubleOrNull()?.let { MetadataLine(bpm = it) } ?: MetadataLine()
            key == "first" -> value.toDoubleOrNull()?.let { MetadataLine(firstSec = it) }
                ?: MetadataLine()
            else -> MetadataLine()
        }
    }

    private class ParsedNotes(
        val notes: List<ChartNote>,
        val firstBpm: Double,
        val bpmEvents: List<BpmEvent>,
        val divisorEvents: List<DivisorEvent>,
    )

    private fun isWhitespace(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\r' || char == '\n'

    private fun parseNotes(chartBody: String, initialBpm: Double): ParsedNotes {
        val notes = mutableListOf<ChartNote>()
        val bpmEvents = mutableListOf<BpmEvent>()
        val divisorEvents = mutableListOf<DivisorEvent>()

        var currentBpm = initialBpm
        var firstBpm: Double? = null
        var divisor = 4.0
        var currentBeat = 0.0
        var currentMs = 0.0
        var currentHiSpeed = 1.0
        var pos = 0

        while (pos < chartBody.length) {
            while (pos < chartBody.length && isWhitespace(chartBody[pos])) pos++
            if (pos >= chartBody.length) break

            var noteContent = ""
            val startPos = pos

            while (pos < chartBody.length && chartBody[pos] != ',') {
                val char = chartBody[pos]
                if (isWhitespace(char)) {
                    pos++
                    continue
                }
                if (char == '(') {
                    val match = bpmRegex.find(chartBody.substring(pos))
                    if (match != null) {
                        currentBpm = match.groupValues[1].toDouble()
                        if (firstBpm == null) firstBpm = currentBpm
                        bpmEvents += BpmEvent(currentBeat, currentBpm)
                        pos += match.value.length
                        continue
                    }
                }
                if (char == '{') {
                    val match = divisorRegex.find(chartBody.substring(pos))
                    if (match != null) {
                        val newDivisor = match.groupValues[1].toDouble()
                        if (newDivisor != divisor) {
                            divisor = newDivisor
                            divisorEvents += DivisorEvent(currentBeat, divisor)
                        }
                        pos += match.value.length
                        continue
                    }
                }
                if (char == '<') {
                    val match = hiSpeedRegex.find(chartBody.substring(pos))
                    if (match != null) {
                        currentHiSpeed = match.groupValues[1].toDouble()
                        pos += match.value.length
                        continue
                    }
                }
                noteContent += char
                pos++
            }

            if (pos == startPos && pos < chartBody.length && chartBody[pos] != ',') {
                pos++
                continue
            }

            val beatIncrement = 4.0 / divisor

            if (noteContent.trim().isNotEmpty()) {
                val measure = kotlin.math.floor(currentBeat / 4.0).toInt()
                val positionInMeasure = kotlin.math.floor(((currentBeat % 4.0) / 4.0) * 512.0).toInt()

                val noteGroups = mutableListOf<String>()
                var currentGroup = ""
                for (char in noteContent) {
                    when (char) {
                        '`' -> {
                            if (currentGroup.trim().isNotEmpty()) noteGroups += currentGroup.trim()
                            currentGroup = "`"
                        }
                        '/' -> {
                            if (currentGroup.trim().isNotEmpty()) noteGroups += currentGroup.trim()
                            currentGroup = ""
                        }
                        else -> currentGroup += char
                    }
                }
                if (currentGroup.trim().isNotEmpty()) noteGroups += currentGroup.trim()

                val isSimultaneous = noteGroups.size > 1
                for (group in noteGroups) {
                    var noteStr = group.trim()
                    if (noteStr.isEmpty()) continue
                    var hasDelayMarker = false
                    if (noteStr.startsWith("`")) {
                        hasDelayMarker = true
                        noteStr = noteStr.substring(1)
                    }
                    val parsedNotes = parseNoteString(
                        noteStr = noteStr,
                        timing = currentBeat,
                        timingMs = currentMs,
                        measure = measure,
                        positionInMeasure = positionInMeasure,
                        bpm = currentBpm,
                        isSimultaneous = isSimultaneous,
                        hasDelayMarker = hasDelayMarker,
                    )
                    if (currentHiSpeed != 1.0) {
                        notes += parsedNotes.map { it.withHiSpeed(currentHiSpeed) }
                    } else {
                        notes += parsedNotes
                    }
                }
            }

            currentBeat += beatIncrement
            currentMs += 60_000.0 * beatIncrement / currentBpm

            if (pos < chartBody.length && chartBody[pos] == ',') pos++
        }

        return ParsedNotes(
            notes = notes,
            firstBpm = firstBpm ?: initialBpm,
            bpmEvents = if (bpmEvents.isNotEmpty()) bpmEvents else listOf(BpmEvent(0.0, initialBpm)),
            divisorEvents = divisorEvents,
        )
    }

    private fun parseHoldDuration(
        divisor: String?,
        beats: String?,
        seconds: String?,
        bpm: Double,
    ): Double {
        if (divisor != null && beats != null) {
            return (4.0 / divisor.toDouble()) * beats.toDouble()
        }
        return (seconds!!.toDouble() * bpm) / 60.0
    }

    private fun parseNoteString(
        noteStr: String,
        timing: Double,
        timingMs: Double,
        measure: Int,
        positionInMeasure: Int,
        bpm: Double,
        isSimultaneous: Boolean,
        hasDelayMarker: Boolean,
    ): List<ChartNote> {
        val notes = mutableListOf<ChartNote>()
        val delayOffset = if (hasDelayMarker) 1.0 else 0.0

        val holdMatch = holdRegex.find(noteStr)
        if (holdMatch != null) {
            val position = holdMatch.groupValues[1].toInt()
            val holdDuration = parseHoldDuration(
                holdMatch.groupValues[2].ifEmpty { null },
                holdMatch.groupValues[3].ifEmpty { null },
                holdMatch.groupValues[4].ifEmpty { null },
                bpm,
            )
            val lower = noteStr.lowercase()
            val isBreakHold = lower.contains("b")
            val isEx = lower.contains("x")

            if (position in 1..8) {
                val durationMs = 60_000.0 * holdDuration / bpm
                notes += HoldStartNote(
                    position = position,
                    timing = timing,
                    timingMs = timingMs + delayOffset,
                    measure = measure,
                    positionInMeasure = positionInMeasure,
                    scale = 1.0,
                    bpm = bpm,
                    hasDelayMarker = hasDelayMarker,
                    duration = holdDuration,
                    isSameTime = isSimultaneous,
                    isEx = isEx,
                    isBreakHold = isBreakHold,
                )
                val endTiming = timing + holdDuration
                val endTimingMs = timingMs + durationMs + delayOffset
                notes += HoldEndNote(
                    position = position,
                    timing = endTiming,
                    timingMs = endTimingMs,
                    measure = kotlin.math.floor(endTiming / 4.0).toInt(),
                    positionInMeasure = kotlin.math.floor((endTiming % 4.0) / 4.0 * 512.0).toInt(),
                    scale = 1.0,
                    bpm = bpm,
                    hasDelayMarker = false,
                    holdStartTiming = timing,
                    isSameTime = isSimultaneous,
                    isEx = isEx,
                    isBreakHold = isBreakHold,
                )
                return notes
            }
        }

        val slideMatch = slideRegex.find(noteStr)
        if (slideMatch != null && slidePathCharRegex.containsMatchIn(slideMatch.groupValues[2])) {
            val startPosition = slideMatch.groupValues[1].toInt()
            val slideNotation = slideMatch.groupValues[2]
            val pathStartIndex = slidePathStartRegex.find(slideNotation)?.range?.first ?: -1
            val startModifiers =
                if (pathStartIndex >= 0) slideNotation.substring(0, pathStartIndex).lowercase() else ""
            val headlessMarker = when {
                startModifiers.contains("!") -> "!"
                startModifiers.contains("?") -> "?"
                else -> null
            }
            val isStartBreak = startModifiers.contains("b")
            val isHeadless = headlessMarker != null
            val hasTapHead = !isHeadless && startModifiers.contains("@")
            val isEx = noteStr.lowercase().contains("x")

            val slideParts = slideNotation.split("*")
            val allSlideSegments = mutableListOf<List<SlideSegment>>()
            val allDurations = mutableListOf<Double>()
            val allDurationMs = mutableListOf<Double>()
            val allDelayMs = mutableListOf<Double>()
            val allCustomLengths = mutableListOf<Double?>()
            val allSlideBreaks = mutableListOf<Boolean>()

            for (part in slideParts) {
                val hasBreak = Regex("[-><^vpqszVw]\\d*b", RegexOption.IGNORE_CASE).containsMatchIn(part) ||
                    Regex("\\]b", RegexOption.IGNORE_CASE).containsMatchIn(part)
                allSlideBreaks += hasBreak

                val hasMultipleTimings = multiTimingBlockRegex.findAll(part).count() > 1
                if (hasMultipleTimings) {
                    val parsed = parseSlideSegmentsWithTiming(startPosition, part, bpm)
                    allSlideSegments += parsed.segments
                    allDurations += parsed.totalDuration
                    allDurationMs += parsed.totalDurationMs
                    allDelayMs += 60_000.0 / bpm
                    allCustomLengths += null
                } else {
                    var duration = 1.0
                    var customDelay: Double? = null
                    var customDurationSeconds: Double? = null
                    var customLengthSeconds: Double? = null

                    val secondsMatch = secondsWithDelayRegex.find(part)
                    val secondsOnlyMatch = secondsOnlyBlockRegex.find(part)
                    val timingMatch = timingBlockRegex.find(part)
                    if (secondsMatch != null) {
                        customDelay = secondsMatch.groupValues[1].toDouble()
                        customLengthSeconds = secondsMatch.groupValues[2].toDouble()
                        duration = customLengthSeconds
                    } else if (secondsOnlyMatch != null) {
                        customLengthSeconds = secondsOnlyMatch.groupValues[1].toDouble()
                        duration = customLengthSeconds
                    } else if (timingMatch != null) {
                        if (timingMatch.groupValues[1].isNotEmpty()) {
                            customDelay = timingMatch.groupValues[1].toDouble()
                        }
                        duration = (4.0 / timingMatch.groupValues[2].toDouble()) *
                            timingMatch.groupValues[3].toDouble()
                        if (timingMatch.groupValues[4].isNotEmpty()) {
                            customDurationSeconds = timingMatch.groupValues[4].toDouble()
                        }
                    }

                    val durationMsValue: Double
                    val delayMsValue: Double
                    if (customLengthSeconds != null) {
                        durationMsValue = customLengthSeconds * 1000.0
                        delayMsValue = customDelay?.times(1000.0) ?: (60_000.0 / bpm)
                    } else {
                        durationMsValue = customDurationSeconds?.times(1000.0)
                            ?: (60_000.0 * duration / bpm)
                        delayMsValue = customDelay?.let { 60_000.0 / it } ?: (60_000.0 / bpm)
                    }

                    allDurations += duration
                    allDurationMs += durationMsValue
                    allDelayMs += delayMsValue
                    allCustomLengths += customDurationSeconds

                    val pathOnly = part
                        .replace(
                            Regex(
                                "\\[(?:(?:[\\d.]+#)?[\\d.]+:[\\d.]+(?:##[\\d.]+)?|[\\d.]+##[\\d.]+)\\]",
                                RegexOption.IGNORE_CASE,
                            ),
                            "",
                        )
                        .replace(Regex("[bx]", RegexOption.IGNORE_CASE), "")
                    allSlideSegments += parseSlideSegments(startPosition, pathOnly)
                }
            }

            if (startPosition in 1..8) {
                notes += SlideNote(
                    position = startPosition,
                    timing = timing,
                    timingMs = timingMs + delayOffset,
                    measure = measure,
                    positionInMeasure = positionInMeasure,
                    scale = 1.0,
                    bpm = bpm,
                    hasDelayMarker = hasDelayMarker,
                    isHeadless = isHeadless,
                    headlessMode = when (headlessMarker) {
                        "!" -> "pop"
                        "?" -> "fade"
                        else -> null
                    },
                    hasTapHead = hasTapHead,
                    isStartBreak = isStartBreak,
                    allSlideBreaks = allSlideBreaks,
                    isEx = isEx,
                    duration = allDurations[0],
                    durationMs = allDurationMs[0],
                    delayMs = allDelayMs[0],
                    slideSegments = allSlideSegments[0],
                    allSlideSegments = allSlideSegments,
                    allDurations = allDurations,
                    allDurationMs = allDurationMs,
                    allDelayMs = allDelayMs,
                    allCustomLengths = allCustomLengths,
                    isSplitSlide = allSlideSegments.size > 1,
                    customLength = allCustomLengths[0],
                )
                return notes
            }
        }

        if (isMultiDigitNote(noteStr)) {
            val digits = noteStr.toCharArray()
            if (digits.all { it in '0'..'9' } && digits.all { it in '1'..'8' }) {
                for (digit in digits) {
                    notes += TapNote(
                        position = digit - '0',
                        timing = timing,
                        timingMs = timingMs + delayOffset,
                        measure = measure,
                        positionInMeasure = positionInMeasure,
                        scale = 1.0,
                        bpm = bpm,
                        hasDelayMarker = hasDelayMarker,
                        isSameTime = true,
                    )
                }
                return notes
            }
        }

        val touchMatch = touchRegex.find(noteStr)
        if (touchMatch != null) {
            val region = touchMatch.groupValues[1].uppercase()
            val sensorNum = touchMatch.groupValues[2].ifEmpty { null }?.toInt()
            val modifiers = touchMatch.groupValues[3].lowercase()

            val isValidTouch = when (region) {
                "C" -> sensorNum == null || sensorNum == 1 || sensorNum == 2
                "A", "B", "D", "E" -> sensorNum != null && sensorNum in 1..8
                else -> false
            }

            if (isValidTouch) {
                val touchPosition = if (sensorNum != null) "$region$sensorNum" else region
                val isHold = modifiers.contains("h")
                val hasFirework = modifiers.contains("f")
                if (isHold && (touchMatch.groupValues[4].isNotEmpty() ||
                        touchMatch.groupValues[6].isNotEmpty())
                ) {
                    val holdDuration = parseHoldDuration(
                        touchMatch.groupValues[4].ifEmpty { null },
                        touchMatch.groupValues[5].ifEmpty { null },
                        touchMatch.groupValues[6].ifEmpty { null },
                        bpm,
                    )
                    val durationMs = 60_000.0 * holdDuration / bpm
                    notes += TouchHoldStartNote(
                        position = touchPosition,
                        timing = timing,
                        timingMs = timingMs + delayOffset,
                        measure = measure,
                        positionInMeasure = positionInMeasure,
                        scale = 1.0,
                        bpm = bpm,
                        hasDelayMarker = hasDelayMarker,
                        duration = holdDuration,
                        durationMs = durationMs,
                        hasFirework = hasFirework,
                    )
                    val endTiming = timing + holdDuration
                    notes += TouchHoldEndNote(
                        position = touchPosition,
                        timing = endTiming,
                        timingMs = timingMs + durationMs + delayOffset,
                        measure = kotlin.math.floor(endTiming / 4.0).toInt(),
                        positionInMeasure = kotlin.math.floor(
                            (endTiming % 4.0) / 4.0 * 512.0,
                        ).toInt(),
                        scale = 1.0,
                        bpm = bpm,
                        hasDelayMarker = false,
                        holdStartTiming = timing,
                        hasFirework = hasFirework,
                    )
                } else {
                    notes += TouchNote(
                        position = touchPosition,
                        timing = timing,
                        timingMs = timingMs + delayOffset,
                        measure = measure,
                        positionInMeasure = positionInMeasure,
                        scale = 1.0,
                        bpm = bpm,
                        hasDelayMarker = hasDelayMarker,
                        hasFirework = hasFirework,
                    )
                }
                return notes
            }
        }

        val tapMatch = tapRegex.find(noteStr)
        if (tapMatch != null) {
            val position = tapMatch.groupValues[1].toInt()
            val modifiers = tapMatch.groupValues[2].lowercase()
            val starCount = modifiers.count { it == '$' }
            val isBreak = modifiers.contains("b")
            val isEx = modifiers.contains("x")
            if (position in 1..8) {
                notes += TapNote(
                    position = position,
                    timing = timing,
                    timingMs = timingMs + delayOffset,
                    measure = measure,
                    positionInMeasure = positionInMeasure,
                    scale = 1.0,
                    bpm = bpm,
                    hasDelayMarker = hasDelayMarker,
                    isBreak = isBreak,
                    isSameTime = isSimultaneous && !isBreak,
                    isStar = starCount > 0,
                    isSpinningStar = starCount >= 2,
                    isEx = isEx,
                )
            }
        }

        return notes
    }

    private fun isMultiDigitNote(noteStr: String): Boolean {
        if (noteStr.length < 2) return false
        return noteStr.all { it in '0'..'9' }
    }

    private class SlidePathParseResult(
        val segments: List<SlideSegment>,
        val segmentDurations: List<Double>,
        val segmentDurationMs: List<Double>,
    ) {
        val totalDuration: Double = segmentDurations.sum()
        val totalDurationMs: Double = segmentDurationMs.sum()
    }

    private fun parseSlideSegmentsWithTiming(
        startPosition: Int,
        pathNotation: String,
        defaultBpm: Double,
    ): SlidePathParseResult {
        val segments = mutableListOf<SlideSegment>()
        val segmentDurations = mutableListOf<Double>()
        val segmentDurationMs = mutableListOf<Double>()
        var currentPos = startPosition
        var i = 0

        while (i < pathNotation.length) {
            val char = pathNotation[i]
            var pathType: SlidePathType? = null
            var doubleConsumed = false
            if (i + 1 < pathNotation.length && pathNotation[i + 1] == char &&
                (char == 'p' || char == 'q')
            ) {
                pathType = if (char == 'p') {
                    SlidePathType.SPIRAL_CLOCKWISE_DOUBLE
                } else {
                    SlidePathType.SPIRAL_COUNTER_CLOCKWISE_DOUBLE
                }
                i += 2
                doubleConsumed = true
            } else if ("-><^vpqszVw".contains(char)) {
                pathType = SlidePathType.fromSymbol(char)
                i++
            } else {
                i++
                continue
            }

            if (pathType == SlidePathType.VIA) {
                var numStr = ""
                while (i < pathNotation.length && pathNotation[i].isDigit()) {
                    numStr += pathNotation[i]
                    i++
                }
                if (i < pathNotation.length && pathNotation[i] == '[') {
                    val bracketEnd = pathNotation.indexOf(']', i)
                    if (bracketEnd != -1) i = bracketEnd + 1
                }
                if (numStr.length >= 2) {
                    val midPos = numStr[0] - '0'
                    val endPos = numStr.substring(1).toInt()
                    val start = currentPos
                    val leftCorner = ((start + 5) % 8) + 1
                    val rightCorner = ((start + 1) % 8) + 1
                    val d = (((endPos - start) % 8) + 8) % 8
                    val isStdV = (midPos == leftCorner && d in 1..4) ||
                        (midPos == rightCorner && (8 - d) % 8 in 1..4)

                    if (isStdV) {
                        segments += SlideSegment(SlidePathType.VIA, start, endPos, midPos)
                        segmentDurations += 1.0
                        segmentDurationMs += 60_000.0 / defaultBpm
                    } else {
                        segments += SlideSegment(SlidePathType.LINE, start, midPos)
                        segments += SlideSegment(SlidePathType.LINE, midPos, endPos)
                        segmentDurations += 0.5
                        segmentDurations += 0.5
                        segmentDurationMs += 30_000.0 / defaultBpm
                        segmentDurationMs += 30_000.0 / defaultBpm
                    }
                    currentPos = endPos
                }
            } else {
                var numStr = ""
                while (i < pathNotation.length && pathNotation[i].isDigit()) {
                    numStr += pathNotation[i]
                    i++
                }
                while (i < pathNotation.length &&
                    (pathNotation[i] == 'b' || pathNotation[i] == 'x' ||
                        pathNotation[i] == 'B' || pathNotation[i] == 'X')
                ) {
                    i++
                }

                var segDuration = 1.0
                var segDurationMs = 60_000.0 / defaultBpm

                if (i < pathNotation.length && pathNotation[i] == '[') {
                    val bracketEnd = pathNotation.indexOf(']', i)
                    if (bracketEnd != -1) {
                        val timingStr = pathNotation.substring(i + 1, bracketEnd)
                        val secondsMatch = Regex("^([\\d.]+)##([\\d.]+)$").find(timingStr)
                        val secondsOnlyMatch = Regex("^#([\\d.]+)$").find(timingStr)
                        val stdMatch = Regex(
                            "^(?:([\\d.]+)#)?([\\d.]+):([\\d.]+)(?:##([\\d.]+))?$",
                        ).find(timingStr)
                        if (secondsMatch != null) {
                            segDuration = secondsMatch.groupValues[2].toDouble()
                            segDurationMs = segDuration * 1000.0
                        } else if (secondsOnlyMatch != null) {
                            segDurationMs = secondsOnlyMatch.groupValues[1].toDouble() * 1000.0
                            segDuration = segDurationMs * defaultBpm / 60_000.0
                        } else if (stdMatch != null) {
                            segDuration = (4.0 / stdMatch.groupValues[2].toDouble()) *
                                stdMatch.groupValues[3].toDouble()
                            segDurationMs = if (stdMatch.groupValues[4].isNotEmpty()) {
                                stdMatch.groupValues[4].toDouble() * 1000.0
                            } else {
                                60_000.0 * segDuration / defaultBpm
                            }
                        }
                        i = bracketEnd + 1
                        while (i < pathNotation.length &&
                            (pathNotation[i] == 'b' || pathNotation[i] == 'x' ||
                                pathNotation[i] == 'B' || pathNotation[i] == 'X')
                        ) {
                            i++
                        }
                    }
                }

                if (numStr.isNotEmpty()) {
                    val endPos = numStr.toInt()
                    segments += SlideSegment(pathType!!, currentPos, endPos)
                    segmentDurations += segDuration
                    segmentDurationMs += segDurationMs
                    currentPos = endPos
                }
            }
        }

        return SlidePathParseResult(segments, segmentDurations, segmentDurationMs)
    }

    private fun parseSlideSegments(startPosition: Int, pathNotation: String): List<SlideSegment> =
        parseSlideSegmentsWithTiming(startPosition, pathNotation, 120.0).segments
}
