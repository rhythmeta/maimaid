package org.rhythmeta.maimaid.core.chart.render

/**
 * Port of the reference touch draw-order solver (`utils/touchDrawOrder.ts` + `drawGroupSort.ts`).
 * Part order decides which petal covers which across sensors, so it has to match the arcade
 * ordering exactly; the unstable quicksort below is reproduced verbatim for that reason.
 */
object TouchOrder {
    enum class Part { GAUGE, PETAL_0, PETAL_1, PETAL_2, PETAL_3, CENTER }

    class TouchSortNote(
        val sourceIndex: Int,
        val position: String,
        val isHold: Boolean,
        val registeredAtMs: Double,
    )

    class RankedPart(val sourceIndex: Int, val part: Part)

    private val touchLayers = listOf("B", "E", "A", "D", "C")

    private val defaultLauncherKeys = longArrayOf(
        0x800b800aL, 0x800b8000L, 0x800b8000L, 0x80058000L, 0x80058000L, 0x80058002L,
        0x80058002L, 0x80058000L, 0x80058000L, 0x80058000L, 0x80058002L, 0x80058002L,
        0x80058000L, 0x80058000L, 0x80058000L, 0x80058000L, 0x80058002L, 0x80058002L,
        0x80058000L, 0x8005800aL, 0x80058000L, 0x80058000L, 0x80058000L, 0x80058000L,
        0x80058000L, 0x80058000L, 0x80058000L, 0x8006ffffL, 0x8006ffffL, 0x80008000L,
        0x80008000L,
    )

    private class Element(val key: Long, val sourceIndex: Int? = null, val part: Part? = null)

    private fun drawGroupKey(layer: Int, order: Int): Long =
        ((layer + 32768).toLong() shl 16) or ((order + 32768).toLong() and 0xffffL)

    private fun appendNoteElements(elements: MutableList<Element>, note: TouchSortNote) {
        val layer = if (note.isHold) 4 else 3
        fun add(offset: Int, part: Part? = null, sortingLayer: Int = layer) {
            elements += Element(
                drawGroupKey(sortingLayer, -note.sourceIndex + offset),
                note.sourceIndex,
                part,
            )
        }
        add(3, Part.CENTER)
        add(2, Part.PETAL_0)
        add(if (note.isHold) 2 else 1, Part.PETAL_1)
        add(if (note.isHold) 1 else 2, Part.PETAL_2)
        add(1, Part.PETAL_3)
        if (!note.isHold) add(4)
        add(2, null, 5)
        if (note.isHold) add(-1, Part.GAUGE)
    }

    fun resolve(notes: List<TouchSortNote>): List<RankedPart> {
        val comparator = Comparator<TouchSortNote> { a, b ->
            val primary = when {
                a.position[0] == 'C' && b.position[0] == 'C' -> 0
                a.position < b.position -> -1
                a.position > b.position -> 1
                else -> 0
            }
            if (primary != 0) {
                primary
            } else {
                val byRegistered = b.registeredAtMs.compareTo(a.registeredAtMs)
                if (byRegistered != 0) byRegistered else b.sourceIndex.compareTo(a.sourceIndex)
            }
        }
        val ordered = notes.sortedWith(comparator)
        val parts = mutableListOf<RankedPart>()
        for (layer in touchLayers) {
            val group = ordered.filter { it.position[0].toString() == layer }
            if (group.isEmpty()) continue
            val elements = mutableListOf(Element(0L), Element(0xffffffffL))
            val sensorCount = if (layer == "C") 1 else 8
            for (sensor in 1..sensorCount) {
                for (note in group) {
                    if (layer == "C" || note.position.getOrNull(1)?.digitToIntOrNull() == sensor) {
                        appendNoteElements(elements, note)
                    }
                }
                for (key in defaultLauncherKeys) elements += Element(key)
            }
            sortDrawGroup(elements)
            elements.forEachIndexed { _, element ->
                val sourceIndex = element.sourceIndex
                val part = element.part
                if (sourceIndex != null && part != null) {
                    parts += RankedPart(sourceIndex, part)
                }
            }
        }
        return parts
    }

    /** Unstable quicksort/insertion hybrid used by the reference implementation. */
    private fun sortDrawGroup(items: MutableList<Element>) {
        fun swap(a: Int, b: Int) {
            val value = items[a]
            items[a] = items[b]
            items[b] = value
        }

        fun sortThree(a: Int, b: Int, c: Int) {
            if (items[b].key < items[a].key) swap(a, b)
            if (items[c].key < items[b].key) swap(b, c)
            if (items[b].key < items[a].key) swap(a, b)
        }

        fun heapSort(first: Int, count: Int) {
            fun adjust(hole: Int, length: Int, value: Element) {
							var child = hole * 2 + 2
                var currentHole = hole
                while (child < length) {
                    if (items[first + child].key < items[first + child - 1].key) child--
                    items[first + currentHole] = items[first + child]
                    currentHole = child
                    child = currentHole * 2 + 2
                }
                if (child == length) {
                    items[first + currentHole] = items[first + child - 1]
                    currentHole = child - 1
                }
                var parent = (currentHole - 1) / 2
                while (currentHole > hole && items[first + parent].key < value.key) {
                    items[first + currentHole] = items[first + parent]
                    currentHole = parent
                    parent = (currentHole - 1) / 2
                }
                items[first + currentHole] = value
            }
            for (parent in count / 2 - 1 downTo 0) {
                adjust(parent, count, items[first + parent])
            }
            var remaining = count
            while (remaining > 1) {
                remaining--
                val value = items[first + remaining]
                items[first + remaining] = items[first]
                adjust(0, remaining, value)
            }
        }

        fun sort(firstIn: Int, endIn: Int, idealIn: Int) {
            var first = firstIn
            var end = endIn
            var ideal = idealIn
            while (end - first >= 32) {
                if (ideal <= 0) {
                    heapSort(first, end - first)
                    return
                }
                val last = end - 1
                val middle = first + (last - first) / 2
                if (last - first > 64) {
                    val step = (last - first) / 8
                    sortThree(first, first + step, first + 2 * step)
                    sortThree(middle - step, middle, middle + step)
                    sortThree(last - 2 * step, last - step, last)
                    sortThree(first + step, middle, last - step)
                } else {
                    sortThree(first, middle, last)
                }
                swap(middle, last)
                val pivot = items[last].key
                var i = first - 1
                var j = last
                var p = first - 1
                var q = last
                while (true) {
                    do {
                        i++
                    } while (items[i].key < pivot && i != last)
                    do {
                        j--
                    } while (pivot < items[j].key && j != first)
                    if (i >= j) break
                    swap(i, j)
                    if (items[i].key == pivot) swap(++p, i)
                    if (items[j].key == pivot) swap(--q, j)
                }
                swap(i, last)
                var leftEnd = i - 1
                var rightStart = i + 1
                var k = first
                while (k < p) {
                    swap(k, leftEnd)
                    leftEnd--
                    k++
                }
                k = last - 1
                while (k > q) {
                    swap(k, rightStart)
                    rightStart++
                    k--
                }
                val halfIdeal = ideal / 2
                ideal = halfIdeal + halfIdeal / 2
                val leftSize = leftEnd + 1 - first
                val rightSize = end - rightStart
                if (leftSize < rightSize) {
                    sort(first, leftEnd + 1, leftSize)
                    first = rightStart
                } else {
                    sort(rightStart, end, rightSize)
                    end = leftEnd + 1
                }
            }
            for (i in first + 1 until end) {
                var j = i
                while (j > first && items[j].key < items[j - 1].key) {
                    swap(j, j - 1)
                    j--
                }
            }
        }

        sort(0, items.size, items.size)
    }
}
