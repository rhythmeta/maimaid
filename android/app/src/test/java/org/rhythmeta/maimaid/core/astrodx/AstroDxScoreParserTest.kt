package org.rhythmeta.maimaid.core.astrodx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AstroDxScoreParserTest {
    @Test
    fun mapsClearTypeThreeAndFourToFcAndFcPlus() {
        val root = Json.parseToJsonElement(
            """{"version":3,"level_metadata":{"Song":{"title":"Song","difficulties":[
                {"id":5,"value":"13","stats":{"clearType":3,"achievementRate":99.5,"dxScore":100,"totalNotesCount":100}},
                {"id":6,"value":"13+","stats":{"clearType":4,"achievementRate":100.1,"dxScore":110,"totalNotesCount":100}}
            ]}}}""",
        ).jsonObject
        val scores = AstroDxScoreParser.parse(root).scores
        assertEquals("fc", scores[0].input.fc)
        assertEquals("fcp", scores[1].input.fc)
    }

    @Test
    fun neverTreatsEasyAsBasic() {
        val root = Json.parseToJsonElement(
            """{"version":4,"level_metadata":{"Song":{"title":"Song","difficulties":[
                {"id":1,"stats":{"clearType":2,"achievementRate":80,"dxScore":1,"totalNotesCount":1}}
            ]}}}""",
        ).jsonObject
        assertEquals(1, AstroDxScoreParser.parse(root).scores.size)
        assertEquals(1, AstroDxScoreParser.parse(root).scores[0].difficultyId)
        assertNull(AstroDxScoreParser.parse(root).scores[0].alias)
    }
}

