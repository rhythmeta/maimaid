package org.rhythmeta.maimaid.core.chart

/** Slide shape template selection and per-shape chunky-disappearance steps. */
object SlideGeometry {
    private val mirrorKeys = intArrayOf(-1, 1, 8, 7, 6, 5, 4, 3, 2)
    private val upperHalf = setOf(1, 2, 7, 8)

    /** Flips a button index across the 1-5 diagonal (1↔1, 2↔8, 3↔7, 4↔6, 5↔5). */
    fun mirrorKey(key: Int): Int = mirrorKeys.getOrElse(key) { key }

    fun isUpperHalf(key: Int): Boolean = key in upperHalf

    /** Clockwise steps from start to end, always 1..8 (1 when the endpoints coincide). */
    fun relativeEnd(startPos: Int, endPos: Int): Int = (((endPos - startPos) % 8) + 8) % 8 + 1

    /** Base shape key and whether the 1-5 axis mirror applies. */
    data class SlideShape(val shape: String, val mirror: Boolean)

    fun detectSlideShape(
        type: SlidePathType,
        startPos: Int,
        endPos: Int,
        midPos: Int? = null,
    ): SlideShape? {
        val rel = relativeEnd(startPos, endPos)
        return when (type) {
            SlidePathType.LINE -> {
                if (rel !in 3..7) return null
                val normRel = if (rel > 5) 10 - rel else rel
                SlideShape("line$normRel", normRel != rel)
            }
            SlidePathType.CLOCKWISE -> if (isUpperHalf(startPos)) {
                SlideShape("circle$rel", false)
            } else {
                SlideShape("circle${mirrorKey(rel)}", true)
            }
            SlidePathType.COUNTER_CLOCKWISE -> if (!isUpperHalf(startPos)) {
                SlideShape("circle$rel", false)
            } else {
                SlideShape("circle${mirrorKey(rel)}", true)
            }
            SlidePathType.SHORT_ARC -> {
                if (rel == 1 || rel == 5) return null
                if (rel < 5) SlideShape("circle$rel", false)
                else SlideShape("circle${mirrorKey(rel)}", true)
            }
            SlidePathType.V_SHAPE -> {
                if (rel == 5) return null
                SlideShape("v$rel", false)
            }
            SlidePathType.SPIRAL_CLOCKWISE_DOUBLE -> SlideShape("ppqq$rel", false)
            SlidePathType.SPIRAL_COUNTER_CLOCKWISE_DOUBLE ->
                SlideShape("ppqq${mirrorKey(rel)}", true)
            SlidePathType.SPIRAL_CLOCKWISE -> SlideShape("pq$rel", false)
            SlidePathType.SPIRAL_COUNTER_CLOCKWISE -> SlideShape("pq${mirrorKey(rel)}", true)
            SlidePathType.S_CURVE -> if (rel != 5) null else SlideShape("s", false)
            SlidePathType.Z_CURVE -> if (rel != 5) null else SlideShape("s", true)
            SlidePathType.VIA -> {
                val mid = midPos ?: return null
                val leftCorner = ((startPos + 5) % 8) + 1
                val rightCorner = ((startPos + 1) % 8) + 1
                if (mid == leftCorner && rel in 2..5) {
                    SlideShape("L$rel", false)
                } else {
                    val mirrorRel = ((8 - (rel - 1)) % 8) + 1
                    if (mid == rightCorner && mirrorRel in 2..5) {
                        SlideShape("L$mirrorRel", true)
                    } else {
                        null
                    }
                }
            }
            SlidePathType.WIFI -> SlideShape("wifi", false)
        }
    }

    /**
     * Chunky disappearance step tables: the array starts at 0 and ends at the total arrow
     * count. Wi-Fi slides hide inclusively (step + 1); other shapes hide whole batches.
     */
    val stepMap: Map<String, IntArray> = mapOf(
        "line3" to intArrayOf(0, 2, 8, 13),
        "line4" to intArrayOf(0, 3, 8, 12, 18),
        "line5" to intArrayOf(0, 3, 6, 11, 15, 19),
        "circle1" to intArrayOf(0, 3, 11, 19, 27, 35, 43, 50, 58, 63),
        "circle2" to intArrayOf(0, 3, 7),
        "circle3" to intArrayOf(0, 3, 11, 15),
        "circle4" to intArrayOf(0, 3, 11, 19, 23),
        "circle5" to intArrayOf(0, 3, 11, 19, 27, 31),
        "circle6" to intArrayOf(0, 3, 11, 19, 27, 35, 39),
        "circle7" to intArrayOf(0, 3, 11, 19, 27, 35, 43, 47),
        "circle8" to intArrayOf(0, 3, 11, 19, 27, 35, 43, 50, 55),
        "v1" to intArrayOf(0, 3, 6, 11, 15, 19),
        "v2" to intArrayOf(0, 3, 6, 11, 15, 19),
        "v3" to intArrayOf(0, 3, 6, 11, 15, 19),
        "v4" to intArrayOf(0, 3, 6, 11, 15, 19),
        "v6" to intArrayOf(0, 3, 6, 11, 15, 19),
        "v7" to intArrayOf(0, 3, 6, 11, 15, 19),
        "v8" to intArrayOf(0, 3, 6, 11, 15, 19),
        "ppqq1" to intArrayOf(0, 3, 7, 13, 17, 26, 32, 35),
        "ppqq2" to intArrayOf(0, 3, 7, 12, 16, 25, 28),
        "ppqq3" to intArrayOf(0, 3, 6, 12, 15, 22),
        "ppqq4" to intArrayOf(0, 3, 7, 12, 16, 25, 29, 35, 40, 44, 49),
        "ppqq5" to intArrayOf(0, 3, 7, 12, 16, 25, 29, 35, 40, 44, 49),
        "ppqq6" to intArrayOf(0, 3, 7, 12, 16, 25, 28, 34, 38, 41, 48),
        "ppqq7" to intArrayOf(0, 3, 7, 13, 17, 27, 31, 37, 41, 46),
        "ppqq8" to intArrayOf(0, 3, 7, 12, 16, 25, 29, 35, 41),
        "pq1" to intArrayOf(0, 3, 8, 11, 14, 17, 21, 24, 27, 33),
        "pq2" to intArrayOf(0, 3, 8, 11, 14, 18, 21, 24, 30),
        "pq3" to intArrayOf(0, 3, 9, 12, 16, 19, 23, 27),
        "pq4" to intArrayOf(0, 3, 9, 13, 16, 20, 24),
        "pq5" to intArrayOf(0, 3, 9, 13, 17, 21),
        "pq6" to intArrayOf(0, 3, 8, 11, 15, 18, 21, 25, 28, 31, 35, 38, 42),
        "pq7" to intArrayOf(0, 3, 8, 12, 15, 18, 22, 25, 28, 32, 35, 39),
        "pq8" to intArrayOf(0, 3, 8, 11, 14, 17, 21, 24, 27, 30, 36),
        "s" to intArrayOf(0, 3, 8, 11, 17, 21, 24, 30),
        "wifi" to intArrayOf(0, 1, 4, 6, 11),
        "L2" to intArrayOf(0, 2, 7, 15, 21, 26, 32),
        "L3" to intArrayOf(0, 2, 8, 17, 20, 26, 29, 34),
        "L4" to intArrayOf(0, 2, 8, 17, 22, 26, 32),
        "L5" to intArrayOf(0, 2, 8, 16, 22, 28),
    )
}
