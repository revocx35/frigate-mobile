package io.github.revocx35.frigatemobile.core

import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test fun kinds() {
        assertEquals(DetectionKind.PERSON, detectionKind("person"))
        assertEquals(DetectionKind.ANIMAL, detectionKind("dog"))
        assertEquals(DetectionKind.ANIMAL, detectionKind("Bird"))
        assertEquals(DetectionKind.ANIMAL, detectionKind("deer")) // Frigate+ label
        assertEquals(DetectionKind.OTHER, detectionKind("car"))
        assertEquals(DetectionKind.OTHER, detectionKind("package"))
    }

    @Test fun parsesMotionIgnoringSignAndJunk() {
        // Shape of Frigate 0.18's /api/review/activity/motion; it negates every other camera's motion for its graph.
        val json = FrigateJson.parseToJsonElement(
            """[{"start_time": 1800.0, "motion": -40.0, "camera": "front"}, {"start_time": 1740, "motion": 10, "audio": 0},
               {"motion": 5}, "x", {"start_time": 1860.0, "motion": null}]""",
        ) as JsonArray
        val m = Timeline.parseMotion(json)
        assertEquals(listOf(1740.0, 1800.0, 1860.0), m.map { it.time })
        assertEquals(listOf(10.0, 40.0, 0.0), m.map { it.motion })
    }

    @Test fun bucketsColorByMostImportantObject() {
        val motion = listOf(MotionSample(0.0, 100.0), MotionSample(60.0, 25.0), MotionSample(150.0, 0.0))
        val spans = listOf(
            TimelineSpan("a", "dog", DetectionKind.ANIMAL, 50.0, 130.0),   // buckets 0..2
            TimelineSpan("b", "person", DetectionKind.PERSON, 70.0, 80.0), // bucket 1 only
            TimelineSpan("c", "car", DetectionKind.OTHER, 200.0, null),     // in progress: until now (250) -> 3..4
            TimelineSpan("d", "cat", DetectionKind.ANIMAL, -500.0, -400.0), // before the range
        )
        val b = Timeline.buckets(0.0, 300.0, 60, motion, spans, now = 250.0)
        assertEquals(listOf(0.0, 60.0, 120.0, 180.0, 240.0), b.map { it.start })
        assertEquals(listOf(DetectionKind.ANIMAL, DetectionKind.PERSON, DetectionKind.ANIMAL, DetectionKind.OTHER, DetectionKind.OTHER), b.map { it.kind })
        assertEquals(1f, b[0].motion, 0.001f)
        assertEquals(0.5f, b[1].motion, 0.001f) // sqrt(25/100)
        assertEquals(0f, b[2].motion, 0.001f)
    }

    @Test fun noMotionNoDetections() {
        val b = Timeline.buckets(0.0, 120.0, 60, emptyList(), emptyList(), now = 0.0)
        assertEquals(2, b.size)
        assertTrue(b.all { it.motion == 0f })
        assertNull(b[0].kind)
    }
}
