package com.example.sommeil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepCalculatorTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val wake = LocalDate.of(2026, 10, 27)
    private val s = SleepSettings(minActivityMin = 5, fallAsleepDelayMin = 10)

    private fun at(day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, 10, day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun iv(d1: Int, h1: Int, m1: Int, d2: Int, h2: Int, m2: Int) =
        ScreenInterval(at(d1, h1, m1), at(d2, h2, m2))

    @Test
    fun nuitSimple() {
        val night = SleepCalculator.computeNight(
            wake,
            listOf(iv(26, 20, 0, 26, 23, 0), iv(27, 7, 0, 27, 7, 30)),
            s, zone,
        )
        assertNotNull(night)
        assertEquals(at(26, 23, 10), night!!.sleepStart) // 23:00 + 10 min d'endormissement
        assertEquals(at(27, 7, 0), night.sleepEnd)
        assertEquals(410, night.durationMin) // 6 h 50
    }

    @Test
    fun coupDOeilNocturneIgnore() {
        val night = SleepCalculator.computeNight(
            wake,
            listOf(iv(26, 21, 0, 26, 23, 0), iv(27, 3, 0, 27, 3, 2), iv(27, 7, 0, 27, 8, 0)),
            s, zone,
        )
        assertEquals(at(27, 7, 0), night!!.sleepEnd)
    }

    @Test
    fun reveilLongCoupeLaNuit() {
        val night = SleepCalculator.computeNight(
            wake,
            listOf(iv(26, 21, 0, 26, 23, 0), iv(27, 3, 0, 27, 3, 30), iv(27, 7, 0, 27, 8, 0)),
            s, zone,
        )
        // 23:00→03:00 (4 h) contre 03:30→07:00 (3 h 30) : la première partie est retenue
        assertEquals(at(27, 3, 0), night!!.sleepEnd)
    }

    @Test
    fun youtubeAvantDormirNeCompteComptePasCommeActivite() {
        // Écran allumé de 21:00 à 00:30, mais YouTube au premier plan de 23:15 à 00:30
        val activity = SleepCalculator.subtract(
            listOf(iv(26, 21, 0, 27, 0, 30), iv(27, 7, 0, 27, 8, 0)),
            listOf(iv(26, 23, 15, 27, 0, 30)),
        )
        val night = SleepCalculator.computeNight(wake, activity, s, zone)
        assertEquals(at(26, 23, 25), night!!.sleepStart) // dernière vraie activité 23:15 + 10 min
        assertEquals(at(27, 7, 0), night.sleepEnd)
    }

    @Test
    fun soustractionDecoupeLesPeriodes() {
        val r = SleepCalculator.subtract(
            listOf(iv(26, 20, 0, 26, 22, 0)),
            listOf(iv(26, 20, 30, 26, 21, 0)),
        )
        assertEquals(listOf(iv(26, 20, 0, 26, 20, 30), iv(26, 21, 0, 26, 22, 0)), r)
    }

    @Test
    fun pasDeReveilPasDeNuit() {
        val night = SleepCalculator.computeNight(wake, listOf(iv(26, 20, 0, 26, 23, 0)), s, zone)
        assertNull(night)
    }
}
