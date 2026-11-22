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

    @Test fun recordingMapSkipsGapsAndClipsToWindow() {
        // Window 1000..1100; recordings 990..1020, gap, 1040..1060, 1060..1200 (clipped at 1100).
        val m = RecordingMap(1000.0, 1100.0, listOf(
            RecordingSegment(1060.0, 1200.0), RecordingSegment(990.0, 1020.0), RecordingSegment(1040.0, 1060.0),
        ))
        assertEquals(80.0, m.duration, 1e-9)
        assertEquals(0.0, m.positionOf(1000.0)!!, 1e-9)
        assertEquals(15.0, m.positionOf(1015.0)!!, 1e-9)
        assertEquals(20.0, m.positionOf(1030.0)!!, 1e-9) // in the gap: the next recorded moment
        assertEquals(45.0, m.positionOf(1065.0)!!, 1e-9)
        assertNull(m.positionOf(1100.0))
        assertEquals(1015.0, m.timeAt(15.0), 1e-9)
        assertEquals(1040.0, m.timeAt(20.0), 1e-9)
        assertEquals(1065.0, m.timeAt(45.0), 1e-9)
        assertEquals(1100.0, m.timeAt(500.0), 1e-9)
        assertEquals(1100.0, m.lastTime!!, 1e-9)
        assertTrue(RecordingMap(0.0, 10.0, emptyList()).isEmpty)
    }

    @Test fun scaleSteps() {
        assertEquals(60 to 30, TimelineScale.ticks(10.0))
        assertEquals(900 to 300, TimelineScale.ticks(700.0))
        assertEquals(86400 to 21600, TimelineScale.ticks(1e9))
        assertEquals(30, TimelineScale.bar(5.0))
        assertEquals(120, TimelineScale.bar(61.0))
        assertEquals(7200, TimelineScale.bar(1e9))
    }

    @Test fun parsesRecordings() {
        val json = FrigateJson.parseToJsonElement(
            """[{"id": "a", "start_time": 20.0, "end_time": 30.0, "duration": 10}, {"start_time": 5, "end_time": 15}, {"start_time": 40}, {"start_time": 50, "end_time": 50}]""",
        ) as JsonArray
        assertEquals(listOf(RecordingSegment(5.0, 15.0), RecordingSegment(20.0, 30.0)), TimelineScale.parseRecordings(json))
    }
}
