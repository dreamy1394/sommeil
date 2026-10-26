package com.example.sommeil

import java.time.LocalDate
import java.time.ZoneId

/** Période pendant laquelle l'écran était allumé (millisecondes epoch). */
data class ScreenInterval(val start: Long, val end: Long)

/** Une nuit estimée, rattachée à la date du réveil. */
data class Night(val wakeDate: LocalDate, val sleepStart: Long, val sleepEnd: Long) {
    val durationMin: Long get() = (sleepEnd - sleepStart) / 60_000
}

data class SleepSettings(
    /** Une utilisation de l'écran plus courte que ça est ignorée (notif, coup d'œil à l'heure…). */
    val minActivityMin: Int = 5,
    /** Temps moyen entre « je pose le téléphone » et l'endormissement. */
    val fallAsleepDelayMin: Int = 10,
    /** En dessous de cette durée d'inactivité, on ne parle pas de nuit de sommeil. */
    val minSleepHours: Int = 3,
    /** La nuit est cherchée entre la veille à cette heure… */
    val windowStartHour: Int = 18,
    /** …et le jour du réveil à cette heure. */
    val windowEndHour: Int = 14,
)

object SleepCalculator {

    /** Trie et fusionne les périodes qui se chevauchent. */
    fun merge(intervals: List<ScreenInterval>): List<ScreenInterval> {
        val merged = mutableListOf<ScreenInterval>()
        for (i in intervals.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && i.start <= last.end) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, i.end))
            } else {
                merged += i
            }
        }
        return merged
    }

    /**
     * Retire de [base] les moments couverts par [excluded] (ex. YouTube au premier plan).
     * Une période d'écran allumé qui était en fait occupée par YouTube ne compte plus comme activité.
     */
    fun subtract(base: List<ScreenInterval>, excluded: List<ScreenInterval>): List<ScreenInterval> {
        val ex = merge(excluded)
        val out = mutableListOf<ScreenInterval>()
        for (b in base) {
            var cur = b.start
            for (e in ex) {
                if (e.end <= cur) continue
                if (e.start >= b.end) break
                if (e.start > cur) out += ScreenInterval(cur, e.start)
                cur = maxOf(cur, e.end)
                if (cur >= b.end) break
            }
            if (cur < b.end) out += ScreenInterval(cur, b.end)
        }
        return out
    }

    /** Fenêtre de recherche de la nuit qui se termine le matin de [wakeDate]. */
    fun windowFor(wakeDate: LocalDate, s: SleepSettings, zone: ZoneId): Pair<Long, Long> {
        val start = wakeDate.minusDays(1).atTime(s.windowStartHour, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val end = wakeDate.atTime(s.windowEndHour, 0)
            .atZone(zone).toInstant().toEpochMilli()
        return start to end
    }

    /**
     * Cherche la plus longue période sans activité significative, encadrée de part et d'autre
     * par une vraie utilisation du téléphone (le soir avant, le matin après).
     */
    fun computeNight(
        wakeDate: LocalDate,
        intervals: List<ScreenInterval>,
        s: SleepSettings,
        zone: ZoneId,
    ): Night? {
        val (windowStart, windowEnd) = windowFor(wakeDate, s, zone)
        val minActivityMs = s.minActivityMin * 60_000L
        val minSleepMs = s.minSleepHours * 3_600_000L

        // 1. On garde les vraies utilisations (les coups d'œil brefs ne cassent pas la nuit),
        //    puis on les découpe à la fenêtre et on fusionne celles qui se chevauchent.
        val active = intervals
            .filter { it.end - it.start >= minActivityMs }
            .map { ScreenInterval(maxOf(it.start, windowStart), minOf(it.end, windowEnd)) }
            .filter { it.end > it.start }
            .sortedBy { it.start }

        val merged = merge(active)

        // 2. La plus longue pause entre deux activités = la nuit.
        var bestStart = 0L
        var bestEnd = 0L
        for (k in 0 until merged.size - 1) {
            val gapStart = merged[k].end
            val gapEnd = merged[k + 1].start
            if (gapEnd - gapStart >= minSleepMs && gapEnd - gapStart > bestEnd - bestStart) {
                bestStart = gapStart
                bestEnd = gapEnd
            }
        }
        if (bestEnd == 0L) return null

        // 3. On ajoute le délai d'endormissement après la dernière utilisation.
        val sleepStart = minOf(bestStart + s.fallAsleepDelayMin * 60_000L, bestEnd)
        return Night(wakeDate, sleepStart, bestEnd)
    }
}
