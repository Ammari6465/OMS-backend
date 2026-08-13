package com.sunrich.oms.ai

import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.organization.DepartmentResponse
import com.sunrich.oms.organization.DepartmentService
import com.sunrich.oms.organization.PositionService
import com.sunrich.oms.organization.StaffResponse
import com.sunrich.oms.organization.StaffService
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.systemdata.SystemDataService
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Ask OMS orchestration layer.
 *
 * Flow: detect intent → enforce RBAC → call existing OMS services for real data
 * (which already apply company scoping) → assemble a minimal structured context
 * → let the provider phrase the answer. Business logic stays in the domain
 * services; this class only routes and shapes.
 */
@Service
class AiOrchestrationService(
    private val intents: IntentDetector,
    private val staffService: StaffService,
    private val departmentService: DepartmentService,
    private val positionService: PositionService,
    private val systemData: SystemDataService,
    private val provider: AiProvider,
) {

    fun chat(rawQuery: String): AiChatResponse {
        val query = rawQuery.trim()
        val intent = intents.detect(query)
        val result = resolve(intent, query)
        // The provider only rephrases; the data and actions are already final.
        val answer = provider.rephrase(result.answer, intent.name, result.context, query)
        return result.copy(answer = answer)
    }

    /** Used by the frontend BackendAiProvider to polish a client-computed draft. */
    fun rephrase(request: AiRephraseRequest): String =
        provider.rephrase(
            draft = request.draft.orEmpty(),
            intent = request.intent.orEmpty(),
            context = request.context ?: emptyMap(),
            query = request.query.orEmpty(),
        )

    fun suggestions(): List<String> = buildList {
        add("Which department has the most employees?")
        add("Show open vacancies")
        add("Who joined this month?")
        add("Give me an organisation overview")
        if (isAdmin()) add("Summarise today's activity")
    }

    // ---- intent routing ----

    private fun resolve(intent: AiIntent, query: String): AiChatResponse = when (intent) {
        AiIntent.DENIED -> denied()
        AiIntent.HELP -> help()
        AiIntent.REPORTING_HIERARCHY -> reportingHierarchy(query)
        AiIntent.MANAGER_OF -> managerOf(query)
        AiIntent.DEPARTMENT_HEAD -> departmentHead(query)
        AiIntent.DEPARTMENT_STATS -> departmentStats()
        AiIntent.RECENT_HIRES -> joining(query)
        AiIntent.VACANCIES -> vacancies()
        AiIntent.POSITIONS_BY_TITLE -> positionsByTitle(query)
        AiIntent.INSIGHTS -> insights()
        AiIntent.ACTIVITY_SUMMARY -> activitySummary()
        AiIntent.FIND_EMPLOYEE -> findEmployee(query)
        AiIntent.UNKNOWN -> unknown(query)
    }

    // ---- tools (wrap existing, already-scoped services) ----

    private fun staff() = staffService.listLegacy(false)
    private fun departments() = departmentService.listLegacy(false)

    private fun reportingHierarchy(query: String): AiChatResponse {
        val all = staff()
        val person = matchStaff(query, all) ?: return noPerson("reporting_hierarchy")
        val reports = all.filter { it.managerId == person.id }
        val context = mapOf(
            "manager" to person.name,
            "department" to person.departmentName,
            "directReports" to reports.map { mapOf("name" to it.name, "title" to it.title) },
        )
        val answer = if (reports.isEmpty()) {
            "${person.name} has no direct reports on record."
        } else {
            "${reports.size} people report directly to ${person.name} in ${person.departmentName ?: "their department"}:\n" +
                reports.joinToString("\n") { "• ${it.name}${it.title?.let { t -> " — $t" } ?: ""}" }
        }
        return AiChatResponse("reporting-hierarchy", answer, context, focusActions(person), if (reports.isEmpty()) "empty" else "normal")
    }

    private fun managerOf(query: String): AiChatResponse {
        val all = staff()
        val person = matchStaff(query, all) ?: return noPerson("manager_of")
        val manager = all.firstOrNull { it.id == person.managerId }
        val context = mapOf("employee" to person.name, "manager" to manager?.name)
        val answer = manager?.let { "${person.name} reports to ${it.name}${it.title?.let { t -> ", $t" } ?: ""}." }
            ?: "${person.name} has no manager assigned."
        return AiChatResponse("manager-of", answer, context, focusActions(manager ?: person), if (manager == null) "empty" else "normal")
    }

    private fun departmentHead(query: String): AiChatResponse {
        val dept = matchDepartment(query) ?: return AiChatResponse(
            "department-head", "Which department did you mean? For example: \"Who heads Finance?\"", emptyMap(),
            listOf(navigate("Open Departments", "pi pi-briefcase", "/departments")), "empty",
        )
        val head = dept.headStaffId?.let { id -> staff().firstOrNull { it.id == id } }
        val context = mapOf("department" to dept.name, "head" to head?.name)
        val answer = head?.let { "${dept.name} is headed by ${it.name}${it.title?.let { t -> ", $t" } ?: ""}." }
            ?: "${dept.name} has no department head assigned."
        return AiChatResponse("department-head", answer, context, head?.let { focusActions(it) } ?: emptyList(), if (head == null) "empty" else "normal")
    }

    private fun departmentStats(): AiChatResponse {
        val staff = staff()
        val counts = departments().map { d -> d.name to staff.count { it.deptId == d.id } }.sortedByDescending { it.second }
        if (counts.isEmpty()) return AiChatResponse("department-stats", "There are no departments on record yet.", emptyMap(), emptyList(), "empty")
        val top = counts.first()
        val answer = "${top.first} is the largest department with ${top.second} employees.\n" +
            counts.take(6).joinToString("\n") { "• ${it.first}: ${it.second} employees" }
        return AiChatResponse(
            "department-stats", answer,
            mapOf("largest" to mapOf("name" to top.first, "count" to top.second), "departments" to counts.map { mapOf("name" to it.first, "count" to it.second) }),
            listOf(navigate("Open Departments", "pi pi-briefcase", "/departments")),
        )
    }

    /** Dispatches joining questions to a specific person, a time window, or the full roster. */
    private fun joining(query: String): AiChatResponse {
        val q = query.lowercase()
        val named = matchStaff(query, staff())
        if (named != null && Regex("\\bwhen\\b|\\bjoin(ed|ing)?\\s+date\\b|\\bdate\\b").containsMatchIn(q)) {
            val ctx = mapOf("name" to named.name, "joined" to named.dateJoined)
            return if (named.dateJoined == null)
                AiChatResponse("join-roster", "${named.name}'s joining date isn't recorded in OMS.", ctx, focusActions(named), "empty")
            else AiChatResponse("join-roster", "${named.name} joined on ${named.dateJoined}.", ctx, focusActions(named))
        }
        if (Regex("this week|last 7|past week").containsMatchIn(q)) return recentHires(7L, "in the last 7 days")
        if (Regex("this month|last 30|past month|recent|new hire|new joiner|newly|lately").containsMatchIn(q)) return recentHires(31L, "in the last month")
        return joinRoster()
    }

    private fun recentHires(days: Long, label: String): AiChatResponse {
        val cutoff = LocalDate.now().minusDays(days)
        val recent = staff().filter { it.dateJoined != null && !it.dateJoined.isBefore(cutoff) }
            .sortedByDescending { it.dateJoined }
        if (recent.isEmpty()) return AiChatResponse("recent-hires", "No one joined $label on record.", mapOf("window" to label, "count" to 0), listOf(navigate("Open Staff", "pi pi-users", "/staff")), "empty")
        val answer = "${recent.size} people joined $label:\n" +
            recent.take(12).joinToString("\n") { "• ${it.name} — ${it.departmentName ?: "—"} (${it.dateJoined})" }
        return AiChatResponse("recent-hires", answer, mapOf("window" to label, "count" to recent.size), listOf(navigate("Open Staff", "pi pi-users", "/staff")))
    }

    /** Everyone with a recorded joining date, most recent first. */
    private fun joinRoster(): AiChatResponse {
        val all = staff()
        val dated = all.filter { it.dateJoined != null }.sortedByDescending { it.dateJoined }
        val undated = all.size - dated.size
        if (dated.isEmpty()) return AiChatResponse("join-roster", "None of the staff records include a joining date yet.", mapOf("count" to 0), listOf(navigate("Open Staff", "pi pi-users", "/staff")), "empty")
        val more = if (dated.size > 20) "\n…and ${dated.size - 20} more." else ""
        val note = if (undated > 0) "\n($undated record${if (undated == 1) "" else "s"} have no joining date on file.)" else ""
        val answer = "Here's who joined and when — most recent first:\n" +
            dated.take(20).joinToString("\n") { "• ${it.name} — ${it.departmentName ?: "—"} · joined ${it.dateJoined}" } + more + note
        return AiChatResponse("join-roster", answer, mapOf("count" to dated.size, "withoutDate" to undated), listOf(navigate("Open Staff", "pi pi-users", "/staff")))
    }

    private fun vacancies(): AiChatResponse {
        val summary = positionService.vacancySummary(currentCompanyId())
        val open = summary.open
        val answer = if (open == 0L) "There are no open vacancies right now — every position is filled."
        else "There are $open open ${if (open == 1L) "vacancy" else "vacancies"} across the organisation."
        return AiChatResponse("vacancies", answer, mapOf("open" to open, "filled" to summary.filled, "total" to summary.total),
            listOf(navigate("Open Vacancies", "pi pi-inbox", "/vacancies")), if (open == 0L) "empty" else "normal")
    }

    private fun positionsByTitle(query: String): AiChatResponse {
        val term = query.lowercase().replace(Regex("\\b(show|list|all|find|who|the|staff|employees|people|positions|roles)\\b"), "").trim()
        val people = staff().filter { (it.title ?: "").lowercase().contains(term) && term.isNotEmpty() }
        if (people.isEmpty()) return AiChatResponse("positions-by-title", "No staff matching \"$term\" were found.", mapOf("query" to term), listOf(navigate("Open Staff", "pi pi-users", "/staff")), "empty")
        val answer = "${people.size} people match \"$term\":\n" + people.take(12).joinToString("\n") { "• ${it.name} — ${it.title} (${it.departmentName ?: "—"})" }
        return AiChatResponse("positions-by-title", answer, mapOf("query" to term, "people" to people.map { mapOf("name" to it.name, "title" to it.title) }), listOf(navigate("Open Staff", "pi pi-users", "/staff")))
    }

    private fun findEmployee(query: String): AiChatResponse {
        val all = staff()
        val person = matchStaff(query, all) ?: return noPerson("find_employee")
        val byId = all.associateBy { it.id }
        val chain = mutableListOf<String>()
        var cur: StaffResponse? = person
        val guard = mutableSetOf<Long>()
        while (cur != null && guard.add(cur.id)) {
            chain.add(0, cur.name)
            cur = cur.managerId?.let { byId[it] }
        }
        val context = mapOf("name" to person.name, "title" to person.title, "department" to person.departmentName, "reportingChain" to chain)
        val answer = "${person.name}${person.title?.let { " — $it" } ?: ""} · ${person.departmentName ?: "—"}.\n" +
            "Reporting line: ${chain.joinToString(" → ")}."
        return AiChatResponse("find-employee", answer, context, focusActions(person))
    }

    private fun insights(): AiChatResponse {
        val staff = staff()
        val depts = departments()
        val counts = depts.map { d -> d.name to staff.count { it.deptId == d.id } }.sortedByDescending { it.second }
        val open = positionService.vacancySummary(currentCompanyId()).open
        val noHead = depts.filter { it.headStaffId == null }
        val bullets = buildList {
            counts.firstOrNull()?.let { add("${it.first} is the largest department (${it.second} employees).") }
            add("${staff.size} staff across ${depts.size} departments.")
            if (open > 0) add("$open open ${if (open == 1L) "vacancy" else "vacancies"} awaiting a hire.")
            if (noHead.isNotEmpty()) add("${noHead.size} departments without a head: ${noHead.take(4).joinToString(", ") { it.name }}.")
        }
        return AiChatResponse("insights", "Here's a snapshot of your organisation:\n" + bullets.joinToString("\n") { "• $it" },
            mapOf("departments" to counts.map { mapOf("name" to it.first, "count" to it.second) }, "openVacancies" to open, "departmentsWithoutHead" to noHead.map { it.name }),
            listOf(navigate("Open Dashboard", "pi pi-th-large", "/dashboard")))
    }

    private fun activitySummary(): AiChatResponse {
        if (!isAdmin()) {
            return AiChatResponse("activity-summary",
                "The full organisation activity log is restricted to administrators.", mapOf("scope" to "restricted"),
                listOf(navigate("Open Notifications", "pi pi-bell", "/notifications")), "denied")
        }
        val summary = systemData.auditSummary(currentCompanyId())
        val answer = "Today's activity: ${summary.todayEvents} events recorded" +
            (if (summary.securityEvents > 0) ", including ${summary.securityEvents} security-related." else ".")
        return AiChatResponse("activity-summary", answer,
            mapOf("today" to summary.todayEvents, "total" to summary.totalEvents, "security" to summary.securityEvents),
            listOf(navigate("Open Audit Log", "pi pi-history", "/audit")))
    }

    // ---- helpers ----

    private fun denied() = AiChatResponse(
        "denied",
        "You don't have permission to access salary or compensation information, and OMS doesn't store it.",
        mapOf("reason" to "restricted-field"), emptyList(), "denied",
    )

    private fun help() = AiChatResponse(
        "help",
        "I can answer questions from live OMS data. Try: \"Who reports to <name>?\", " +
            "\"Which department has the most employees?\", \"Show open vacancies\", \"Who joined this month?\", or \"Find <name>\".",
        emptyMap(), emptyList(),
    )

    private fun unknown(query: String) = AiChatResponse(
        "unknown",
        "I couldn't map that to OMS data. I can help with reporting lines, headcount, vacancies, recent joiners, " +
            "finding people, and activity summaries. Ask \"help\" for examples.",
        mapOf("query" to query), emptyList(), "empty",
    )

    private fun noPerson(intent: String) = AiChatResponse(
        intent.replace('_', '-'),
        "I couldn't find anyone by that name in the records you can access. Try their full name.",
        emptyMap(), listOf(navigate("Open Staff directory", "pi pi-users", "/staff")), "empty",
    )

    private fun focusActions(person: StaffResponse) = listOf(
        AiAction("focus-organogram", "Show ${person.name.substringBefore(' ')} in the Organogram", "pi pi-sitemap", staffId = person.id),
        navigate("Open Organogram", "pi pi-sitemap", "/organogram"),
    )

    private fun navigate(label: String, icon: String, route: String) = AiAction("navigate", label, icon, route = route)

    /**
     * Best-effort person match. Prefers a whole-name hit, then scores people by
     * how many query tokens match a name word (exact/prefix), ignoring command
     * words — so honorifics and partials ("Dr.", "Henry", "Hen") resolve too.
     */
    private fun matchStaff(query: String, staff: List<StaffResponse>): StaffResponse? {
        val q = query.lowercase()
        staff.firstOrNull { q.contains(it.name.lowercase()) }?.let { return it }
        val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP_WORDS }
        if (tokens.isEmpty()) return null
        return staff
            .map { s ->
                val words = s.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
                s to tokens.count { t -> words.any { it == t || it.startsWith(t) } }
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    companion object {
        private val STOP_WORDS = setOf(
            "find", "locate", "highlight", "show", "me", "where", "is", "are", "was", "in", "on", "the", "a", "an",
            "of", "to", "who", "reports", "report", "reporting", "manager", "managers", "boss", "and", "staff",
            "employee", "employees", "person", "people", "my", "their", "for", "under", "works", "work", "about",
            "tell", "can", "you", "please", "when", "did", "does", "do", "join", "joined", "joining", "has", "have",
        )
    }

    private fun matchDepartment(query: String): DepartmentResponse? {
        val q = query.lowercase()
        return departments().firstOrNull { q.contains(it.name.lowercase()) }
    }

    private fun currentCompanyId(): Long? = SecurityUtils.currentPrincipalOrNull()?.companyId

    private fun isAdmin(): Boolean {
        val role = SecurityUtils.currentPrincipalOrNull()?.role
        return role == Role.SUPER_ADMIN || role == Role.COMPANY_ADMIN
    }
}
