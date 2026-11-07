package com.abfilepro.app.billing

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abfilepro.app.core.PointsLedger
import com.abfilepro.app.core.PointsPolicy
import com.abfilepro.app.core.ScanCaptureMode
import java.util.concurrent.TimeUnit

/**
 * P33: points for the free tier. Active only in the Play build for people without Pro; everyone else
 * is never limited. Stored on the phone only (never sent anywhere).
 */
object PointsState {
    private const val PREFS = "ab_file_pro_points"
    private const val KEY_BALANCE = "balance"
    private const val KEY_BONUS_DAY = "bonus_day"
    private const val KEY_ADS_DAY = "ads_day"
    private const val KEY_ADS_TODAY = "ads_today"

    private var ledger by mutableStateOf(PointsLedger())

    /**
     * True when points apply to this person right now. Points are the free alternative to paying, so
     * Pro, "remove ads" buyers and the direct APK (no ads) are never limited.
     */
    val active: Boolean get() = ProState.gatingActive && !ProState.adsRemoved

    val balance: Int get() = ledger.balance

    private fun today(): Long = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() + java.util.TimeZone.getDefault().rawOffset)

    fun adsLeftToday(): Int = ledger.adsLeftToday(today())

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // First ever run: the starting gift is the day-one gift, so no extra daily bonus right away.
        val firstRun = !p.contains(KEY_BONUS_DAY)
        ledger = PointsLedger(
            balance = p.getInt(KEY_BALANCE, PointsPolicy.START_BALANCE),
            lastBonusDay = p.getLong(KEY_BONUS_DAY, -1),
            adsDay = p.getLong(KEY_ADS_DAY, -1),
            adsToday = p.getInt(KEY_ADS_TODAY, 0),
        )
        if (firstRun) {
            ledger = ledger.copy(lastBonusDay = today())
            save(context)
        }
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BALANCE, ledger.balance).putLong(KEY_BONUS_DAY, ledger.lastBonusDay)
            .putLong(KEY_ADS_DAY, ledger.adsDay).putInt(KEY_ADS_TODAY, ledger.adsToday).apply()
    }

    /** Points a save in [mode] with [pages] pages would cost this person (0 when points do not apply). */
    fun costOf(mode: ScanCaptureMode, pages: Int): Int = if (active) PointsPolicy.saveCost(mode, pages) else 0

    fun canSpend(cost: Int): Boolean = !active || cost <= 0 || ledger.canSpend(cost)

    /** Called only after the work really succeeded, so a failed save never costs points. */
    fun spend(context: Context, cost: Int) {
        if (!active || cost <= 0) return
        ledger = ledger.spend(cost)
        save(context)
    }

    /** Returns true when today's gift was just added. */
    fun claimDailyBonus(context: Context): Boolean {
        if (!active || !ledger.dailyBonusAvailable(today())) return false
        ledger = ledger.claimDailyBonus(today())
        save(context)
        return true
    }

    fun earnFromAd(context: Context) {
        ledger = ledger.earnFromAd(today())
        save(context)
    }
}
