package com.example.sommeil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepStatsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 28) // mercredi

    private fun at(month: Int, day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, month, day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    // 7 h 00 de sommeil le lundi 26 (coucher 23:30), 8 h 00 le mardi 27 (coucher 23:00)
    private val nights = listOf(
        Night(LocalDate.of(2026, 10, 26), at(10, 25, 23, 30), at(10, 26, 6, 30)),
        Night(LocalDate.of(2026, 10, 27), at(10, 26, 23, 0), at(10, 27, 7, 0)),
    )

    @Test
    fun semaineDuLundiAuDimanche() {
        val (start, end) = SleepStats.range(AnalysisPeriod.SEMAINE, 0, today)
        assertEquals(LocalDate.of(2026, 10, 26), start)
        assertEquals(LocalDate.of(2026, 11, 1), end)
    }

    @Test
    fun graphiqueSemaine() {
        val chart = SleepStats.chart(nights, AnalysisPeriod.SEMAINE, 0, today)
        assertEquals(7, chart.bars.size)
        assertEquals(420f, chart.bars[0].minutes!!, 0.01f)
        assertEquals(480f, chart.bars[1].minutes!!, 0.01f)
        assertNull(chart.bars[2].minutes)
        assertEquals("lun", chart.bars[0].label)
    }

    @Test
    fun semainePrecedenteVide() {
        val chart = SleepStats.chart(nights, AnalysisPeriod.SEMAINE, -1, today)
        assertEquals(0, chart.nights.size)
    }

    @Test
    fun graphiqueMoisAUneBarreParJour() {
        val chart = SleepStats.chart(nights, AnalysisPeriod.MOIS, 0, today)
        assertEquals(31, chart.bars.size) // octobre
        assertEquals(420f, chart.bars[25].minutes!!, 0.01f) // 26 octobre
    }

    @Test
    fun graphiqueAnneeMoyenneParMois() {
        val chart = SleepStats.chart(nights, AnalysisPeriod.ANNEE, 0, today)
        assertEquals(12, chart.bars.size)
        assertEquals(450f, chart.bars[9].minutes!!, 0.01f) // octobre : moyenne de 420 et 480
        assertNull(chart.bars[0].minutes)
    }

    @Test
    fun resume() {
        val s = SleepStats.summary(nights, zone)
        assertEquals(2, s.count)
        assertEquals(450, s.avgMin)
        assertEquals(480, s.longestMin)
        assertEquals(420, s.shortestMin)
        assertEquals(23 * 60 + 15, s.avgBedtimeMinOfDay) // moyenne de 23:30 et 23:00
        assertEquals(6 * 60 + 45, s.avgWakeMinOfDay) // moyenne de 06:30 et 07:00
    }

    @Test
    fun coucherMoyenneAMinuit() {
        val n = listOf(
            Night(LocalDate.of(2026, 10, 26), at(10, 25, 23, 30), at(10, 26, 7, 30)),
            Night(LocalDate.of(2026, 10, 27), at(10, 27, 0, 30), at(10, 27, 8, 30)),
        )
        assertEquals(0, SleepStats.summary(n, zone).avgBedtimeMinOfDay) // 23:30 et 00:30 -> 00:00
    }

    @Test
    fun resumeVide() {
        assertEquals(0, SleepStats.summary(emptyList(), zone).count)
    }
}
