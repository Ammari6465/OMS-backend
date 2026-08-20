package com.sunrich.oms.workplace.detection

import com.sunrich.oms.exception.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class GeometryTest {

    @Test
    fun `rectangle derives centre area and bounds`() {
        val polygon = Polygon.rectangle(0.2, 0.4, 0.2, 0.1)

        assertThat(polygon.center.x).isCloseTo(0.3, within(1e-9))
        assertThat(polygon.center.y).isCloseTo(0.45, within(1e-9))
        assertThat(polygon.area).isCloseTo(0.02, within(1e-9))
        assertThat(polygon.width).isCloseTo(0.2, within(1e-9))
    }

    @Test
    fun `centre of an L shape stays inside the plan`() {
        val polygon = Polygon(listOf(
            Point(0.0, 0.0), Point(0.4, 0.0), Point(0.4, 0.2),
            Point(0.2, 0.2), Point(0.2, 0.4), Point(0.0, 0.4)
        ))

        assertThat(polygon.center.x).isBetween(0.0, 0.4)
        assertThat(polygon.center.y).isBetween(0.0, 0.4)
        assertThat(polygon.area).isCloseTo(0.12, within(1e-9))
    }

    @Test
    fun `coordinates outside the image are clamped rather than rejected`() {
        // Vision models routinely return points slightly off the image; a region
        // that spilled outside would draw beyond its container.
        val polygon = Polygon.ofClamped(listOf(Point(-0.3, 0.5), Point(1.4, 0.5), Point(0.5, 1.2)))

        assertThat(polygon.minX).isEqualTo(0.0)
        assertThat(polygon.maxX).isEqualTo(1.0)
        assertThat(polygon.maxY).isEqualTo(1.0)
    }

    @Test
    fun `serialise and parse round-trip`() {
        val original = Polygon.rectangle(0.125, 0.25, 0.5, 0.125)

        val parsed = Polygon.parse(original.serialise())

        assertThat(parsed.points).isEqualTo(original.points)
    }

    @Test
    fun `degenerate and malformed polygons are rejected`() {
        assertThatThrownBy { Polygon(listOf(Point(0.0, 0.0), Point(1.0, 1.0))) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { Polygon.parse("0.1,0.2 nonsense") }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { Polygon.parse("0.1,0.2 0.3") }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `object types parse leniently from model output`() {
        assertThat(DetectedObjectType.parse("conference room")).isEqualTo(DetectedObjectType.CONFERENCE_ROOM)
        assertThat(DetectedObjectType.parse("Conference-Room")).isEqualTo(DetectedObjectType.CONFERENCE_ROOM)
        assertThat(DetectedObjectType.parse("workstation")).isEqualTo(DetectedObjectType.DESK)
        assertThat(DetectedObjectType.parse("corridor")).isEqualTo(DetectedObjectType.WALKWAY)
        assertThat(DetectedObjectType.parse("emergency exit")).isEqualTo(DetectedObjectType.EXIT)
        assertThat(DetectedObjectType.parse("toilet")).isEqualTo(DetectedObjectType.WASHROOM)
        assertThat(DetectedObjectType.parse(null)).isEqualTo(DetectedObjectType.UNKNOWN)
        assertThat(DetectedObjectType.parse("something else")).isEqualTo(DetectedObjectType.UNKNOWN)
    }
}
