package com.sunrich.oms.ai

import org.springframework.stereotype.Component

/** The intents Ask OMS understands (mirrors the frontend intent engine). */
enum class AiIntent {
    REPORTING_HIERARCHY,
    MANAGER_OF,
    CONTACT_INFO,
    PERSON_ATTRIBUTE,
    RECENT_HIRES,
    DEPARTMENT_STATS,
    DEPARTMENT_HEAD,
    POSITIONS_BY_TITLE,
    VACANCIES,
    FIND_EMPLOYEE,
    INSIGHTS,
    ACTIVITY_SUMMARY,
    HELP,
    UNKNOWN,
    DENIED,
}

/**
 * Rule-based intent detection. Deliberately deterministic — no LLM is involved
 * in deciding which OMS service to call, which keeps routing cheap, testable and
 * safe. Entity resolution (matching a name/department) is left to the
 * orchestrator, which has the loaded records.
 */
@Component
class IntentDetector {

    private val restricted = Regex(
        "\\bsalar|\\bcompensat|\\bremunerat|\\bpayroll|\\bwage|\\bbonus|\\bctc\\b|\\bincome|\\bpay[\\s-]?(grade|scale|slip|rate|band)",
        RegexOption.IGNORE_CASE,
    )

    fun detect(rawQuery: String): AiIntent {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return AiIntent.HELP
        if (restricted.containsMatchIn(q)) return AiIntent.DENIED

        return when {
            Regex("\\breport(s|ing)?\\b|\\bdirect reports?\\b|\\bteam of\\b|\\bworks (for|under)\\b").containsMatchIn(q) &&
                !Regex("report\\s+(a|an|the)?\\s*(bug|issue)").containsMatchIn(q) -> AiIntent.REPORTING_HIERARCHY

            Regex("\\bmanager of\\b|\\bwho manages\\b|\\bboss of\\b|\\bwho.s the manager\\b").containsMatchIn(q) -> AiIntent.MANAGER_OF
            Regex("\\bcontact\\b|\\be-?mail\\b|\\bphone\\b|\\bnumber\\b|\\breach\\b|\\bcall\\b|\\bmobile\\b|\\blandline\\b|\\bextension\\b|\\bget in touch\\b").containsMatchIn(q) -> AiIntent.CONTACT_INFO
            Regex("\\bposition\\b|\\btitle\\b|\\brole\\b|\\bdesignation\\b|\\bwhat does .* do\\b|\\b(which|what)\\s+(department|team)\\b").containsMatchIn(q) -> AiIntent.PERSON_ATTRIBUTE
            Regex("\\bwho (heads|leads|runs)\\b|\\bhead of\\b|\\bdepartment head\\b").containsMatchIn(q) -> AiIntent.DEPARTMENT_HEAD
            Regex("\\bmost employees\\b|\\blargest department\\b|\\bheadcount\\b|\\bdepartment (stats|size|breakdown)\\b").containsMatchIn(q) -> AiIntent.DEPARTMENT_STATS
            Regex("\\bjoin(ed|ing|ers?)?\\b|\\brecent (hires?|staff|joiners?)\\b|\\bnew (hires?|joiners?|staff|employees?)\\b").containsMatchIn(q) -> AiIntent.RECENT_HIRES
            Regex("\\bvacanc|\\bopen (position|role|vacanc)|\\bopenings?\\b|\\bhiring\\b").containsMatchIn(q) -> AiIntent.VACANCIES

            Regex("\\b(senior|junior|lead|manager|managers|analyst|analysts|engineer|engineers|director|directors|officer|officers|executive|executives)\\b").containsMatchIn(q) &&
                Regex("\\b(show|list|all|find|who)\\b").containsMatchIn(q) -> AiIntent.POSITIONS_BY_TITLE

            Regex("\\binsight|\\boverview\\b|\\bhow are we doing\\b|\\bsummar(y|ise|ize) (the )?(org|organi|company|workforce)").containsMatchIn(q) -> AiIntent.INSIGHTS
            Regex("\\bactivit(y|ies)\\b|\\bwhat happened\\b|\\btoday.s (summary|updates?)\\b|\\bnotification summary\\b").containsMatchIn(q) -> AiIntent.ACTIVITY_SUMMARY
            Regex("\\bfind\\b|\\bhighlight\\b|\\blocate\\b|\\bwhere is\\b|\\bshow me\\b").containsMatchIn(q) -> AiIntent.FIND_EMPLOYEE
            Regex("\\bhelp\\b|\\bwhat can you\\b|\\bhow do you\\b").containsMatchIn(q) -> AiIntent.HELP
            else -> AiIntent.UNKNOWN
        }
    }
}
