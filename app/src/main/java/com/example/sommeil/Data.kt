package com.example.sommeil

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** Sauvegarde locale (rien ne quitte le téléphone). */
class Storage(context: Context) {
    private val prefs = context.getSharedPreferences("sommeil", Context.MODE_PRIVATE)

    fun saveNight(n: Night) {
        prefs.edit().putString("night_${n.wakeDate}", "${n.sleepStart},${n.sleepEnd}").apply()
    }

    fun nights(): List<Night> =
        prefs.all.entries
            .filter { it.key.startsWith("night_") }
            .mapNotNull { e ->
                runCatching {
                    val date = LocalDate.parse(e.key.removePrefix("night_"))
                    val (a, b) = (e.value as String).split(",")
                    Night(date, a.toLong(), b.toLong())
                }.getOrNull()
            }
            .sortedByDescending { it.wakeDate }

    var settings: SleepSettings
        get() {
            val d = SleepSettings()
            return d.copy(
                minActivityMin = prefs.getInt("minActivityMin", d.minActivityMin),
                fallAsleepDelayMin = prefs.getInt("fallAsleepDelayMin", d.fallAsleepDelayMin),
            )
        }
        set(v) {
            prefs.edit()
                .putInt("minActivityMin", v.minActivityMin)
                .putInt("fallAsleepDelayMin", v.fallAsleepDelayMin)
                .apply()
        }

    /** Applications dont l'utilisation ne compte pas comme « éveil » (YouTube par défaut). */
    var excludedPackages: Set<String>
        get() = HashSet(prefs.getStringSet("excludedPackages", DEFAULT_EXCLUDED) ?: DEFAULT_EXCLUDED)
        set(v) {
            prefs.edit().putStringSet("excludedPackages", HashSet(v)).apply()
        }

    companion object {
        val DEFAULT_EXCLUDED = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
        )
    }
}

object ScreenEvents {

    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Reconstitue les périodes d'activité : écran allumé, moins les moments où une application
     * ignorée (ex. YouTube) était au premier plan.
     */
    @Suppress("DEPRECATION")
    fun query(context: Context, from: Long, to: Long, excludedPackages: Set<String>): List<ScreenInterval> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val screen = mutableListOf<ScreenInterval>()
        val excluded = mutableListOf<ScreenInterval>()
        var onSince: Long? = null
        val foregroundSince = HashMap<String, Long>() // application ignorée -> début du premier plan

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> if (onSince == null) onSince = e.timeStamp
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Pas d'allumage vu avant : l'écran était déjà allumé au début de la période.
                    screen += ScreenInterval(onSince ?: from, e.timeStamp)
                    onSince = null
                }
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    if (pkg in excludedPackages && pkg !in foregroundSince) foregroundSince[pkg] = e.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND ->
                    foregroundSince.remove(pkg)?.let { excluded += ScreenInterval(it, e.timeStamp) }
            }
        }
        if (onSince != null) screen += ScreenInterval(onSince, to)
        for (start in foregroundSince.values) excluded += ScreenInterval(start, to)

        return SleepCalculator.subtract(screen, excluded)
    }
}

object SleepRefresher {
    private const val HALF_DAY_MS = 12 * 3_600_000L

    /** Recalcule les nuits des [days] derniers jours et les enregistre. */
    fun refresh(context: Context, days: Int = 7) {
        if (!ScreenEvents.hasPermission(context)) return
        val zone = ZoneId.systemDefault()
        val storage = Storage(context)
        val settings = storage.settings
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)

        for (offset in 0 until days) {
            val date = today.minusDays(offset.toLong())
            val (windowStart, windowEnd) = SleepCalculator.windowFor(date, settings, zone)
            if (windowStart > now) continue
            // On remonte de 12 h pour savoir si l'écran était déjà allumé au début de la fenêtre.
            val intervals = ScreenEvents.query(
                context,
                windowStart - HALF_DAY_MS,
                minOf(now, windowEnd),
                storage.excludedPackages,
            )
            // Si rien n'est trouvé (données système trop anciennes), on garde la nuit déjà enregistrée.
            SleepCalculator.computeNight(date, intervals, settings, zone)?.let { storage.saveNight(it) }
        }
    }
}
