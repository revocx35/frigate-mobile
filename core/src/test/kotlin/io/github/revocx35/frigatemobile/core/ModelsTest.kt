package io.github.revocx35.frigatemobile.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses responses recorded from a real Frigate 0.18.3 (core/src/test/resources/fixtures). */
class ModelsTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/0.18.3/$name")!!.readText()

    @Test fun config() {
        val c = FrigateConfig.parse(FrigateJson.parseToJsonElement(fixture("config.json")) as JsonObject)
        assertEquals(listOf("front", "garage"), c.cameras.map { it.name })
        val front = c.camera("front")!!
        assertTrue(front.record)
        assertTrue(front.snapshots)
        assertEquals(640, front.width)
        assertEquals(16f / 9f, front.aspectRatio, 0.01f)
        assertEquals("Front", front.title)
        assertFalse(c.camera("garage")!!.audioConfigured)
    }

    @Test fun stats() {
        val s = FrigateStats.parse(FrigateJson.parseToJsonElement(fixture("stats.json")) as JsonObject)
        assertEquals("0.18.3-a19e23a", s.version)
        assertEquals("0.18.3", s.latestVersion)
        assertEquals(2, s.cameras.size)
        assertTrue(s.cameras.all { it.online })
        assertEquals("cpu1", s.detectors.single().name)
        assertTrue(s.storage.any { it.path == "/media/frigate/recordings" })
        assertTrue(s.storage.none { it.path.startsWith("/dev/shm") || it.path.startsWith("/tmp") })
        assertNotNull(s.cpuPercent)
    }

    @Test fun reviews() {
        val items = FrigateJson.decodeFromString(ListSerializer(ReviewItem.serializer()), fixture("review.json"))
        assertEquals(2, items.size)
        val r = items.first { it.camera == "garage" }
        assertTrue(r.isAlert)
        assertEquals(listOf("car"), r.labels)
        assertEquals(1, r.data.detections.size)
        val urls = MediaUrls(ServerAddress.parse("https://nvr.example:8971")!!)
        assertEquals("https://nvr.example:8971/clips/review/thumb-garage-${r.id}.webp", urls.reviewThumb(r).toString())
    }

    @Test fun summaryAndEvents() {
        val ev = FrigateJson.decodeFromString(ListSerializer(FrigateEvent.serializer()), fixture("events.json"))
        assertEquals(setOf("person", "car"), ev.map { it.label }.toSet())
        assertTrue(ev.all { it.hasSnapshot })
        assertNull(ev.first().subLabelText)
        val days = FrigateJson.decodeFromString(ListSerializer(RecordingDay.serializer()), fixture("front_recordings_summary.json"))
        assertTrue(days.single().hours.isNotEmpty())
        val p = FrigateJson.decodeFromString(Profile.serializer(), fixture("profile.json"))
        assertTrue(p.isAdmin)
    }

    @Test fun subLabelBothShapes() {
        val old = FrigateJson.decodeFromString(FrigateEvent.serializer(), """{"id":"a","sub_label":"Bob"}""")
        val new = FrigateJson.decodeFromString(FrigateEvent.serializer(), """{"id":"a","sub_label":["Bob",0.9]}""")
        assertEquals("Bob", old.subLabelText)
        assertEquals("Bob", new.subLabelText)
    }

    @Test fun ongoingReviewHasNoEnd() {
        val r = FrigateJson.decodeFromString(ReviewItem.serializer(), """{"id":"x","camera":"c","start_time":1.5,"end_time":null,"severity":"detection"}""")
        assertTrue(r.inProgress)
        assertFalse(r.isAlert)
    }

    @Test fun mediaUrls() {
        val u = MediaUrls(ServerAddress.parse("https://nvr.example/frigate")!!)
        assertEquals("https://nvr.example/frigate/vod/front/start/100/end/201/index.m3u8", u.vod("front", 100.7, 200.2).toString())
        assertEquals("https://nvr.example/frigate/api/front?fps=5&height=480&h=480", u.mjpeg("front", 5, 480).toString())
        assertEquals("https://nvr.example/frigate/api/events/1.5-ab/snapshot.jpg?bbox=1", u.eventSnapshot("1.5-ab").toString())
    }
}
