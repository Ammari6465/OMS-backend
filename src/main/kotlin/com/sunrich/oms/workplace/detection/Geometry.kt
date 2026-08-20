package com.sunrich.oms.workplace.detection

import com.sunrich.oms.exception.BadRequestException

/** A point in normalised plan space: 0..1 on both axes, origin top-left. */
data class Point(val x: Double, val y: Double)

/**
 * A normalised polygon plus the derived measures overlays need. Coordinates are
 * fractions of the plan's width and height rather than pixels, so an overlay
 * stays aligned when the image is scaled, zoomed, or re-uploaded at a different
 * resolution.
 */
data class Polygon(val points: List<Point>) {
    init {
        if (points.size < 3) throw BadRequestException("A region needs at least three points")
        if (points.size > MAX_POINTS) throw BadRequestException("A region cannot exceed $MAX_POINTS points")
    }

    val minX get() = points.minOf { it.x }
    val minY get() = points.minOf { it.y }
    val maxX get() = points.maxOf { it.x }
    val maxY get() = points.maxOf { it.y }
    val width get() = maxX - minX
    val height get() = maxY - minY

    /** Centroid of the enclosed surface, falling back to the bounding-box centre. */
    val center: Point
        get() {
            val a = signedArea()
            if (a == 0.0) return Point(minX + width / 2, minY + height / 2)
            var cx = 0.0
            var cy = 0.0
            forEachEdge { p, q ->
                val cross = p.x * q.y - q.x * p.y
                cx += (p.x + q.x) * cross
                cy += (p.y + q.y) * cross
            }
            return Point(cx / (6 * a), cy / (6 * a))
        }

    /** Absolute area as a fraction of the plan surface. */
    val area: Double get() = kotlin.math.abs(signedArea())

    private fun signedArea(): Double {
        var sum = 0.0
        forEachEdge { p, q -> sum += p.x * q.y - q.x * p.y }
        return sum / 2
    }

    private inline fun forEachEdge(block: (Point, Point) -> Unit) {
        for (i in points.indices) block(points[i], points[(i + 1) % points.size])
    }

    /** `"x,y x,y ..."` with coordinates rounded to the precision overlays need. */
    fun serialise(): String = points.joinToString(" ") { "${round(it.x)},${round(it.y)}" }

    companion object {
        const val MAX_POINTS = 200
        private fun round(value: Double) = Math.round(value * 100_000) / 100_000.0

        /**
         * Clamps every coordinate into 0..1. Detectors — vision models above all
         * — routinely return points a little outside the image, and a region
         * that spills off the plan would draw outside its container.
         */
        fun ofClamped(points: List<Point>): Polygon =
            Polygon(points.map { Point(it.x.coerceIn(0.0, 1.0), it.y.coerceIn(0.0, 1.0)) })

        fun parse(value: String): Polygon {
            val points = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { pair ->
                val parts = pair.split(',')
                if (parts.size != 2) throw BadRequestException("Malformed polygon point '$pair'")
                val x = parts[0].toDoubleOrNull() ?: throw BadRequestException("Malformed polygon point '$pair'")
                val y = parts[1].toDoubleOrNull() ?: throw BadRequestException("Malformed polygon point '$pair'")
                Point(x, y)
            }
            return ofClamped(points)
        }

        /** Axis-aligned rectangle, the common case for desks and rooms. */
        fun rectangle(x: Double, y: Double, width: Double, height: Double): Polygon =
            ofClamped(listOf(Point(x, y), Point(x + width, y), Point(x + width, y + height), Point(x, y + height)))
    }
}
