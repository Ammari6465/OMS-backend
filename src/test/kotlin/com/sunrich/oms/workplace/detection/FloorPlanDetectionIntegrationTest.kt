package com.sunrich.oms.workplace.detection

import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.organization.CompanyRequest
import com.sunrich.oms.organization.OrganizationService
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import com.sunrich.oms.workplace.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Exercises the pipeline against a stub engine. The detector seam is what makes
 * this possible: no network, no model, no image decoding.
 */
@SpringBootTest
@Import(StubDetectorConfig::class)
@ActiveProfiles("test")
@Transactional
class FloorPlanDetectionIntegrationTest {

    @Autowired lateinit var service: FloorPlanDetectionService
    @Autowired lateinit var workplace: WorkplaceService
    @Autowired lateinit var org: OrganizationService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var detector: FloorPlanDetector

    private var floorId = 0L

    @BeforeEach
    fun setUp() {
        val n = System.nanoTime()
        val admin = users.save(User("det-$n", "det-$n@example.com", "hash", Role.SUPER_ADMIN, "Detection Admin"))
        auth(admin)
        val company = org.createCompany(CompanyRequest(name = "Detection Co $n")).id
        val office = workplace.createOffice(OfficeRequest(company, "Head Office", "HO-$n".take(50), timeZone = "Asia/Colombo"))
        val building = workplace.createBuilding(BuildingRequest(office.id, "Tower", "T1"))
        floorId = workplace.createFloor(FloorRequest(building.id, "Floor 1", 1)).id
        workplace.uploadPlan(floorId, plan())
    }

    @AfterEach
    fun tearDown() {
        StubDetector.candidates = emptyList()
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `detection stores geometry and numbers desks by row`() {
        StubDetector.candidates = listOf(
            desk(0.10, 0.60), desk(0.30, 0.60), // lower row, out of order on purpose
            desk(0.30, 0.20), desk(0.10, 0.20), // upper row
            room(DetectedObjectType.CONFERENCE_ROOM, "Conference Room A")
        )

        val run = service.detect(floorId)

        assertThat(run.detected).isEqualTo(5)
        val desks = run.objects.filter { it.type == DetectedObjectType.DESK }
        // Rows run top to bottom, seats left to right, whatever order the
        // detector emitted them in.
        assertThat(desks.filter { it.center.y < 0.5 }.map { it.code }).containsExactlyInAnyOrder("A01", "A02")
        assertThat(desks.filter { it.center.y > 0.5 }.map { it.code }).containsExactlyInAnyOrder("B01", "B02")
        assertThat(desks.first { it.code == "A01" }.center.x).isLessThan(desks.first { it.code == "A02" }.center.x)

        val room = run.objects.single { it.type == DetectedObjectType.CONFERENCE_ROOM }
        assertThat(room.name).isEqualTo("Conference Room A")
        assertThat(room.bbox.width).isGreaterThan(0.0)
        assertThat(room.area).isGreaterThan(0.0)
        assertThat(room.code).isNull()
    }

    @Test
    fun `detection persists every supported object classification`() {
        StubDetector.candidates = DetectedObjectType.entries.mapIndexed { index, type ->
            DetectionCandidate(
                type = type,
                polygon = Polygon.rectangle(0.01 + index * 0.02, 0.1, 0.01, 0.01),
                confidence = 0.8
            )
        }

        val run = service.detect(floorId)

        assertThat(run.objects.map { it.type })
            .containsExactlyInAnyOrderElementsOf(DetectedObjectType.entries)
    }

    @Test
    fun `re-running detection keeps human corrections and replaces automatic ones`() {
        StubDetector.candidates = listOf(desk(0.1, 0.1), desk(0.3, 0.1))
        val first = service.detect(floorId)
        val corrected = first.objects.first()
        service.applyEdits(floorId, DetectionEditRequest(objects = listOf(
            DetectedObjectRequest(
                id = corrected.id, type = DetectedObjectType.CABIN, name = "CEO Cabin",
                polygon = Polygon.rectangle(0.5, 0.5, 0.2, 0.2).serialise()
            )
        )))

        StubDetector.candidates = listOf(desk(0.7, 0.7))
        val second = service.detect(floorId)

        assertThat(second.preserved).isEqualTo(1)
        assertThat(second.detected).isEqualTo(1)
        val kept = second.objects.single { it.type == DetectedObjectType.CABIN }
        assertThat(kept.name).isEqualTo("CEO Cabin")
        assertThat(kept.source).isEqualTo(DetectionSource.EDITED)
        // The untouched automatic desk from the first run is gone.
        assertThat(second.objects.filter { it.type == DetectedObjectType.DESK }).hasSize(1)
    }

    @Test
    fun `clean re-scan replaces automatic and human corrected overlays`() {
        StubDetector.candidates = listOf(desk(0.1, 0.1), desk(0.3, 0.1))
        val first = service.detect(floorId)
        service.applyEdits(floorId, DetectionEditRequest(objects = listOf(
            DetectedObjectRequest(
                id = first.objects.first().id, type = DetectedObjectType.CABIN, name = "Old correction",
                polygon = Polygon.rectangle(0.5, 0.5, 0.2, 0.2).serialise()
            )
        )))

        StubDetector.candidates = listOf(desk(0.7, 0.7))
        val fresh = service.rescan(floorId)

        assertThat(fresh.preserved).isZero()
        assertThat(fresh.objects).hasSize(1)
        assertThat(fresh.objects.single().type).isEqualTo(DetectedObjectType.DESK)
    }

    @Test
    fun `clearing scan overlays leaves promoted workplace desks in place`() {
        StubDetector.candidates = listOf(desk(0.1, 0.1), desk(0.3, 0.1))
        service.detect(floorId)
        service.promoteDesks(floorId)

        assertThat(service.clear(floorId)).isEqualTo(2)
        assertThat(service.list(floorId)).isEmpty()
        assertThat(workplace.listDesks(floorId, false)).hasSize(2)
    }

    @Test
    fun `clearing map contents removes desks zones and overlays but keeps plan image`() {
        workplace.createZone(ZoneRequest(floorId, "Main Zone", "MAIN"))
        StubDetector.candidates = listOf(desk(0.1, 0.1), desk(0.3, 0.1))
        service.detect(floorId)
        service.promoteDesks(floorId)

        val result = service.clearMapContents(floorId)

        assertThat(result).isEqualTo(MapContentsClearResponse(2, 1, 0, 2))
        assertThat(service.list(floorId)).isEmpty()
        assertThat(workplace.listDesks(floorId, false)).isEmpty()
        assertThat(workplace.listZones(floorId, false)).isEmpty()
        assertThat(workplace.plan(floorId).first).isNotEmpty()
    }

    @Test
    fun `manual regions can be drawn and removed without a detector`() {
        val drawn = service.applyEdits(floorId, DetectionEditRequest(objects = listOf(
            DetectedObjectRequest(
                type = DetectedObjectType.PANTRY, name = "Pantry",
                polygon = Polygon.rectangle(0.1, 0.1, 0.1, 0.1).serialise()
            )
        )))
        assertThat(drawn).hasSize(1)
        assertThat(drawn.single().source).isEqualTo(DetectionSource.MANUAL)

        val after = service.applyEdits(floorId, DetectionEditRequest(removedIds = listOf(drawn.single().id)))

        assertThat(after).isEmpty()
    }

    /**
     * The production failure. Detection restarts its row lettering every scan,
     * so a floor that already holds A01 collides on the first candidate. That
     * clash used to be raised inside the shared promotion transaction, which
     * marked it rollback-only; the catch walked past it, every later candidate
     * then failed with "null id ... don't flush the Session after an exception
     * occurs", and the commit was refused with UnexpectedRollbackException.
     * The caller got a 500 and not one desk was created.
     */
    @Test
    fun `a clashing code does not abandon the rest of the floor`() {
        StubDetector.candidates = listOf(desk(0.1, 0.1), desk(0.3, 0.1), desk(0.5, 0.1))
        service.detect(floorId)
        // Take the code the first candidate is about to be given.
        workplace.createDesk(
            DeskRequest(
                floorId = floorId, code = "A01",
                x = BigDecimal("90"), y = BigDecimal("90"),
                width = BigDecimal("2"), height = BigDecimal("2")
            )
        )

        val result = service.promoteDesks(floorId)

        // Every candidate lands; the clash is renamed rather than dropped.
        assertThat(result.created).isEqualTo(3)
        val codes = workplace.listDesks(floorId, false).map { it.code }
        assertThat(codes).contains("A01", "A02", "A03")
        assertThat(codes).hasSize(4)
        assertThat(service.list(floorId).count { it.deskId != null }).isEqualTo(3)
    }

    @Test
    fun `detected rooms are promoted into zones once, and a re-run adds nothing`() {
        StubDetector.candidates = listOf(
            room(DetectedObjectType.CABIN, "Director Cabin"),
            room(DetectedObjectType.CONFERENCE_ROOM, "Board Room")
        )
        service.detect(floorId)

        val first = service.promoteRooms(floorId)
        val second = service.promoteRooms(floorId)

        assertThat(first.created).isEqualTo(2)
        // Idempotent: promoted rooms carry a zoneId and are not offered again.
        assertThat(second.created).isZero()
        val zones = workplace.listZones(floorId, false)
        assertThat(zones.map { it.name }).containsExactlyInAnyOrder("Director Cabin", "Board Room")
        assertThat(service.list(floorId).count { it.zoneId != null }).isEqualTo(2)
    }

    /**
     * The production failure: promoting onto a floor that already holds a zone
     * with the code detection would assign. It used to reject every room on the
     * unique constraint and add none; now the clash is renamed and every new
     * room lands.
     */
    @Test
    fun `promoting rooms works even when a zone code already exists`() {
        workplace.createZone(ZoneRequest(floorId, "Existing", "C1"))
        StubDetector.candidates = listOf(
            room(DetectedObjectType.CABIN, "Cabin One"),
            room(DetectedObjectType.CABIN, "Cabin Two")
        )
        service.detect(floorId)

        val result = service.promoteRooms(floorId)

        assertThat(result.created).isEqualTo(2)
        assertThat(result.skipped).isZero()
        assertThat(workplace.listZones(floorId, false)).hasSize(3)
    }

    @Test
    fun `detected desks are promoted into real desks once`() {
        StubDetector.candidates = listOf(desk(0.1, 0.1), desk(0.3, 0.1))
        service.detect(floorId)

        val first = service.promoteDesks(floorId)
        val second = service.promoteDesks(floorId)

        assertThat(first.created).isEqualTo(2)
        assertThat(second.created).isZero()
        val desks = workplace.listDesks(floorId, false)
        assertThat(desks.map { it.code }).containsExactlyInAnyOrder("A01", "A02")
        // Detection space is 0..1 while the interactive map is 0..100.
        assertThat(desks.first { it.code == "A01" }.x.toDouble()).isCloseTo(10.0, within(0.01))
        assertThat(service.list(floorId).filter { it.deskId != null }).hasSize(2)
    }

    @Test
    fun `a plan no engine can read is refused rather than reported as empty`() {
        // The SVG parser cannot see a raster plan. Saying "nothing recognised"
        // here sends the user off to draw a whole floor by hand over what is
        // really a missing API key.
        StubDetector.readable = false
        try {
            assertThatThrownBy { service.detect(floorId) }
                .isInstanceOf(BadRequestException::class.java)
                .hasMessageContaining("No detection engine can read")
        } finally {
            StubDetector.readable = true
        }
    }

    @Test
    fun `detection is refused when no engine is configured`() {
        StubDetector.configured = false
        try {
            assertThatThrownBy { service.detect(floorId) }
                .isInstanceOf(BadRequestException::class.java)
                .hasMessageContaining("not configured")
        } finally {
            StubDetector.configured = true
        }
    }

    private fun desk(x: Double, y: Double) =
        DetectionCandidate(DetectedObjectType.DESK, Polygon.rectangle(x, y, 0.08, 0.05), confidence = 0.9)

    private fun room(type: DetectedObjectType, name: String) =
        DetectionCandidate(type, Polygon.rectangle(0.6, 0.1, 0.3, 0.2), name = name, ocrText = name)

    private fun plan(): MockMultipartFile {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800"><rect width="10" height="10"/></svg>"""
        return MockMultipartFile("file", "floor.svg", "image/svg+xml", svg.toByteArray())
    }

    private fun auth(user: User) {
        val principal = UserPrincipal(user.id!!, user.username, user.role, user.companyId)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority(principal.authority)))
    }
}
