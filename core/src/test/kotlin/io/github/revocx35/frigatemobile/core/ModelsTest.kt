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
        assertEquals(mapOf("front" to "front"), front.liveStreams) // Frigate fills in {name: name}
        assertFalse(front.onvifConfigured) // onvif.host is ""
    }

    @Test fun liveStreamsKeepConfigOrderAndOnvif() {
        val root = FrigateJson.parseToJsonElement(
            """{"cameras":{"ptzcam":{"live":{"streams":{"main":"cam_main","sub":"cam_sub"}},"onvif":{"host":"camera.invalid","port":2020}}}}""",
        ) as JsonObject
        val cam = FrigateConfig.parse(root).camera("ptzcam")!!
        assertEquals(listOf("main" to "cam_main", "sub" to "cam_sub"), cam.liveStreams.toList())
        assertTrue(cam.onvifConfigured)
    }

    /** Production-like go2rtc config: Tapos with a talk-only tapo:// stream, an XMeye with a DVRIP backchannel. */
    @Test fun talkStreamsFromGo2rtcSources() {
        val root = FrigateJson.parseToJsonElement(
            """{"go2rtc":{"streams":{
                "neutron_fhd":["rtsp://admin:pw@192.168.1.107:554/user=admin&password=pw&channel=1","dvrip://admin:pw@192.168.1.107:34567?backchannel=1"],
                "neutron_sd":"rtsp://admin:pw@192.168.1.107:554/stream=1",
                "c212_1":["rtsp://u:p@192.168.1.64:554/stream1","ffmpeg:c212_1#audio=opus"],
                "c212_2":["rtsp://u:p@192.168.1.64:554/stream2"],
                "c212_talk":["tapo://cloud@192.168.1.64"],
                "door_1":["rtsp://u:p@192.168.1.21:554/stream1"],
                "door_talk":["tapo://cloud@192.168.1.21"],
                "other_talk":["tapo://cloud@192.168.1.99"],
                "plain":["rtsp://u:p@192.168.1.50/live"],
                "off":["dvrip://u:p@192.168.1.50:34567?backchannel=0"]}},
              "cameras":{
                "neutron_n1":{"live":{"streams":{"fhd":"neutron_fhd","sd":"neutron_sd"}}},
                "tapo_c212":{"live":{"streams":{"fhd":"c212_1","sd":"c212_2"}}},
                "door":{"live":{"streams":{"Stream 1":"door_1"}}},
                "plain":{"live":{"streams":{"plain":"plain"}}}}}""",
        ) as JsonObject
        val c = FrigateConfig.parse(root)
        assertEquals(listOf("neutron_fhd"), c.camera("neutron_n1")!!.talkStreams)
        assertEquals(listOf("c212_talk"), c.camera("tapo_c212")!!.talkStreams)
        assertEquals(listOf("door_talk"), c.camera("door")!!.talkStreams) // not other_talk (another camera)
        assertEquals(emptyList<String>(), c.camera("plain")!!.talkStreams) // backchannel=0 on the same host: off
    }

    @Test fun go2rtcSourceKeepsOnlyHostAndTalk() {
        assertEquals(Go2rtcSource("192.168.1.64", true), Go2rtcSource.parse("tapo://p%40ss@192.168.1.64"))
        assertEquals(Go2rtcSource("cam.lan", true), Go2rtcSource.parse("dvrip://a:b@CAM.lan:34567?backchannel=1"))
        assertEquals(Go2rtcSource("cam.lan", false), Go2rtcSource.parse("rtsp://a:b@cam.lan:554/x?backchannel=10"))
        assertEquals(Go2rtcSource("[fd00::5]", false), Go2rtcSource.parse("rtsp://[fd00::5]:554/x"))
        assertEquals(Go2rtcSource(null, false), Go2rtcSource.parse("rtsp://127.0.0.1:8554/front"))
        assertEquals(Go2rtcSource(null, false), Go2rtcSource.parse("ffmpeg:front#audio=opus"))
        assertEquals(Go2rtcSource(null, true), Go2rtcSource.parse("exec:dd of=/tmp/x#backchannel=1#audio=pcma/8000"))
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
