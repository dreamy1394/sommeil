package com.example.sommeil

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

// ---------------------------------------------------------------------------------------------
// Calculs (sans Android, testables)
// ---------------------------------------------------------------------------------------------

enum class AnalysisPeriod(val label: String) {
    SEMAINE("Semaine"),
    MOIS("Mois"),
    ANNEE("Année"),
}

/** Une barre du graphique : durée moyenne en minutes, ou null s'il n'y a pas de donnée. */
data class Bar(val label: String, val minutes: Float?)

data class ChartData(val title: String, val bars: List<Bar>, val nights: List<Night>)

data class Summary(
    val count: Int,
    val avgMin: Int?,
    val longestMin: Int?,
    val shortestMin: Int?,
    /** Heure de coucher moyenne, en minutes depuis minuit. */
    val avgBedtimeMinOfDay: Int?,
    /** Heure de réveil moyenne, en minutes depuis minuit. */
    val avgWakeMinOfDay: Int?,
)

object SleepStats {
    private val fr: Locale = Locale.FRENCH

    /** Première et dernière date (incluses) de la période affichée. [offset] : 0 = en cours, -1 = précédente… */
    fun range(period: AnalysisPeriod, offset: Int, today: LocalDate): Pair<LocalDate, LocalDate> =
        when (period) {
            AnalysisPeriod.SEMAINE -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusWeeks(offset.toLong())
                start to start.plusDays(6)
            }
            AnalysisPeriod.MOIS -> {
                val start = today.withDayOfMonth(1).plusMonths(offset.toLong())
                start to start.with(TemporalAdjusters.lastDayOfMonth())
            }
            AnalysisPeriod.ANNEE -> {
                val start = today.withDayOfYear(1).plusYears(offset.toLong())
                start to start.with(TemporalAdjusters.lastDayOfYear())
            }
        }

    fun chart(all: List<Night>, period: AnalysisPeriod, offset: Int, today: LocalDate): ChartData {
        val (start, end) = range(period, offset, today)
        val inRange = all.filter { !it.wakeDate.isBefore(start) && !it.wakeDate.isAfter(end) }
        val byDate = inRange.associateBy { it.wakeDate }

        return when (period) {
            AnalysisPeriod.SEMAINE -> {
                val dm = DateTimeFormatter.ofPattern("d MMM", fr)
                ChartData(
                    title = "${start.format(dm)} – ${end.format(dm)} ${end.year}",
                    bars = (0L..6L).map { i ->
                        val d = start.plusDays(i)
                        Bar(
                            d.dayOfWeek.getDisplayName(TextStyle.SHORT, fr).take(3),
                            byDate[d]?.durationMin?.toFloat(),
                        )
                    },
                    nights = inRange,
                )
            }
            AnalysisPeriod.MOIS -> ChartData(
                title = start.format(DateTimeFormatter.ofPattern("LLLL yyyy", fr)),
                bars = (0L until end.dayOfMonth.toLong()).map { i ->
                    val d = start.plusDays(i)
                    val label = if (d.dayOfMonth == 1 || d.dayOfMonth % 5 == 0) d.dayOfMonth.toString() else ""
                    Bar(label, byDate[d]?.durationMin?.toFloat())
                },
                nights = inRange,
            )
            AnalysisPeriod.ANNEE -> ChartData(
                title = start.year.toString(),
                bars = (1..12).map { m ->
                    val list = inRange.filter { it.wakeDate.monthValue == m }
                    Bar(
                        Month.of(m).getDisplayName(TextStyle.NARROW, fr).uppercase(fr),
                        if (list.isEmpty()) null else list.map { it.durationMin }.average().toFloat(),
                    )
                },
                nights = inRange,
            )
        }
    }

    fun summary(nights: List<Night>, zone: ZoneId): Summary {
        if (nights.isEmpty()) return Summary(0, null, null, null, null, null)
        val durations = nights.map { it.durationMin.toInt() }

        fun minuteOfDay(epochMs: Long): Int {
            val t = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
            return t.hour * 60 + t.minute
        }

        // Les heures de coucher passent minuit (23:30 et 00:30 doivent donner 00:00, pas 12:00) :
        // on les décale pour que la journée commence à midi, on moyenne, puis on re-décale.
        val shiftedBedtimes = nights.map { (minuteOfDay(it.sleepStart) - 720 + 1440) % 1440 }
        val avgBedtime = (shiftedBedtimes.average().toInt() + 720) % 1440
        val avgWake = nights.map { minuteOfDay(it.sleepEnd) }.average().toInt()

        return Summary(
            count = nights.size,
            avgMin = durations.average().toInt(),
            longestMin = durations.max(),
            shortestMin = durations.min(),
            avgBedtimeMinOfDay = avgBedtime,
            avgWakeMinOfDay = avgWake,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Écran « Analyse »
// ---------------------------------------------------------------------------------------------

private fun fmtDur(min: Int) = "%d h %02d".format(min / 60, min % 60)
private fun fmtShort(min: Float) = "%dh%02d".format(min.toInt() / 60, min.toInt() % 60)
private fun fmtClock(minOfDay: Int) = "%02d:%02d".format(minOfDay / 60, minOfDay % 60)

@Composable
fun AnalysisScreen() {
    val ctx = LocalContext.current
    val nights = remember { Storage(ctx).nights() }
    var periodIdx by rememberSaveable { mutableIntStateOf(0) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val period = AnalysisPeriod.values()[periodIdx]
    val today = remember { LocalDate.now() }
    val chart = remember(nights, period, offset) { SleepStats.chart(nights, period, offset, today) }
    val summary = remember(chart) { SleepStats.summary(chart.nights, ZoneId.systemDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Analyse", style = MaterialTheme.typography.headlineMedium) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisPeriod.values().forEach { p ->
                    val select = { periodIdx = p.ordinal; offset = 0 }
                    if (p == period) {
                        Button(onClick = select, modifier = Modifier.weight(1f)) { Text(p.label) }
                    } else {
                        OutlinedButton(onClick = select, modifier = Modifier.weight(1f)) { Text(p.label) }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { offset-- }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                Text(chart.title, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { offset++ }, enabled = offset < 0) {
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (summary.count == 0) {
                        Text("Aucune nuit enregistrée sur cette période.")
                    } else {
                        BarChart(
                            bars = chart.bars,
                            avgMinutes = summary.avgMin?.toFloat(),
                            showValues = period == AnalysisPeriod.SEMAINE,
                            compactLabels = period != AnalysisPeriod.SEMAINE,
                        )
                    }
                }
            }
        }

        if (summary.count > 0) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("■ 7 h et plus", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text("■ 6 à 7 h", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                    Text("■ moins de 6 h", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    Text("- - moyenne", style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Durée moyenne", summary.avgMin?.let(::fmtDur) ?: "-", Modifier.weight(1f))
                    Stat("Nuits comptées", summary.count.toString(), Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Plus longue", summary.longestMin?.let(::fmtDur) ?: "-", Modifier.weight(1f))
                    Stat("Plus courte", summary.shortestMin?.let(::fmtDur) ?: "-", Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Coucher moyen", summary.avgBedtimeMinOfDay?.let(::fmtClock) ?: "-", Modifier.weight(1f))
                    Stat("Réveil moyen", summary.avgWakeMinOfDay?.let(::fmtClock) ?: "-", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun BarChart(
    bars: List<Bar>,
    avgMinutes: Float?,
    showValues: Boolean,
    compactLabels: Boolean,
) {
    val chartHeight = 180.dp
    val valueRoom = 16.dp // place réservée au-dessus de la barre la plus haute
    val maxMin = max(600f, ceil((bars.maxOfOrNull { it.minutes ?: 0f } ?: 0f) / 60f) * 60f)
    val good = MaterialTheme.colorScheme.primary
    val medium = MaterialTheme.colorScheme.tertiary
    val bad = MaterialTheme.colorScheme.error
    val lineColor = MaterialTheme.colorScheme.onSurface
    val gap = if (bars.size > 12) 2.dp else 6.dp

    Column {
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.Bottom,
            ) {
                bars.forEach { b ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val m = b.minutes
                        if (m != null) {
                            if (showValues) {
                                Text(fmtShort(m), fontSize = 10.sp, maxLines = 1)
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((chartHeight - valueRoom) * (m / maxMin))
                                    .background(
                                        color = when {
                                            m >= 420f -> good
                                            m >= 360f -> medium
                                            else -> bad
                                        },
                                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                    ),
                            )
                        }
                    }
                }
            }
            if (avgMinutes != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val barArea = size.height - valueRoom.toPx()
                    val y = size.height - barArea * (avgMinutes / maxMin)
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            bars.forEach { b ->
                Text(
                    b.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = if (compactLabels) 9.sp else 11.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
