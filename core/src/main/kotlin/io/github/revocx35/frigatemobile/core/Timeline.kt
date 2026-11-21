package io.github.revocx35.frigatemobile.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a tracked object counts as on the timeline: people red, animals yellow, anything else (cars, ...) blue. */
enum class DetectionKind { PERSON, ANIMAL, OTHER }

/** Frigate's labels (COCO + Frigate+) that are animals. */
private val ANIMAL_LABELS = setOf(
    "animal", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "deer", "fox",
    "raccoon", "squirrel", "rabbit", "skunk", "kangaroo", "mouse", "rat", "goat", "pig", "chicken", "duck", "goose",
    "hedgehog", "badger", "possum", "opossum", "coyote", "wolf", "boar", "moose", "otter", "lizard", "snake",
)

fun detectionKind(label: String): DetectionKind = when (label.lowercase()) {
    "person", "face" -> DetectionKind.PERSON
    in ANIMAL_LABELS -> DetectionKind.ANIMAL
    else -> DetectionKind.OTHER
}

/** One sample of `GET /api/review/activity/motion`: the camera's motion in [time, time + scale). */
data class MotionSample(val time: Double, val motion: Double)

/** A tracked object's time on the timeline. [end] is null while it is still in progress. */
data class TimelineSpan(val id: String, val label: String, val kind: DetectionKind, val start: Double, val end: Double?)

/**
 * One bar of the timeline: [motion] is 0..1 (square root of the share of the busiest bucket loaded), [kind] the most important
 * object seen in the bucket (person > animal > other), null for motion without a detection.
 */
data class TimelineBucket(val start: Double, val motion: Float, val kind: DetectionKind?)

object Timeline {
    /**
     * Parses the motion activity answer: a list of `{start_time, motion, camera}` records (0.14+). Frigate flips the
     * sign of every other camera's motion for its own graph, so only the magnitude counts. Unknown shapes give an
     * empty list instead of an error: the timeline still shows the detections.
     */
    fun parseMotion(root: JsonArray): List<MotionSample> = root.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val t = o.dbl("start_time") ?: return@mapNotNull null
        MotionSample(t, abs(o.dbl("motion") ?: 0.0))
    }.sortedBy { it.time }

    fun spans(events: List<FrigateEvent>): List<TimelineSpan> =
        events.map { TimelineSpan(it.id, it.label, detectionKind(it.label), it.startTime, it.endTime) }

    /**
     * Buckets [after, before) into [bucketSeconds] steps, oldest first. [now] closes spans still in progress.
     * [scale] is the motion value that fills a bar (pass the busiest value of everything loaded so pages match).
     */
    fun buckets(
        after: Double,
        before: Double,
        bucketSeconds: Int,
        motion: List<MotionSample>,
        spans: List<TimelineSpan>,
        now: Double,
        scale: Double = motion.maxOfOrNull { it.motion } ?: 0.0,
    ): List<TimelineBucket> {
        require(bucketSeconds > 0 && before > after)
        val n = ((before - after) / bucketSeconds).toInt().coerceAtLeast(1)
        val level = FloatArray(n)
        val kind = arrayOfNulls<DetectionKind>(n)
        fun index(t: Double) = ((t - after) / bucketSeconds).toInt()
        if (scale > 0) {
            for (s in motion) {
                val i = index(s.time)
                // Square root: real cameras have a few big spikes; small motion must still show as a bar.
                if (i in 0 until n) level[i] = max(level[i], sqrt(min(1.0, s.motion / scale)).toFloat())
            }
        }
        for (s in spans) {
            val end = s.end ?: now
            val first = max(0, index(s.start))
            val last = min(n - 1, index(max(s.start, end - 0.001)))
            for (i in first..last) {
                val k = kind[i]
                if (k == null || s.kind.ordinal < k.ordinal) kind[i] = s.kind
            }
        }
        return (0 until n).map { i -> TimelineBucket(after + i.toDouble() * bucketSeconds, level[i], kind[i]) }
    }
}
