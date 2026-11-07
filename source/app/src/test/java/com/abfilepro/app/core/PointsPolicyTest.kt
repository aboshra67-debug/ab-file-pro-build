package com.abfilepro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PointsPolicyTest {
    @Test fun tenBatchPagesCostTenPoints() {
        assertEquals(10, PointsPolicy.saveCost(ScanCaptureMode.BATCH, 10))
        assertEquals(1, PointsPolicy.saveCost(ScanCaptureMode.BATCH, 1))
    }

    @Test fun translationCostsTwoPoints() {
        assertEquals(2, PointsPolicy.saveCost(ScanCaptureMode.TRANSLATION, 1))
    }

    @Test fun singleDocumentCardAndReceiptStayFree() {
        listOf(ScanCaptureMode.DOCUMENT, ScanCaptureMode.ID_CARD, ScanCaptureMode.DUPLEX, ScanCaptureMode.RECEIPT, ScanCaptureMode.PASSPORT, ScanCaptureMode.PHOTO)
            .forEach { assertEquals(it.name, 0, PointsPolicy.saveCost(it, 5)) }
    }

    @Test fun aLongBookIsCappedSoItIsNeverOutOfReach() {
        assertEquals(PointsPolicy.MAX_SCAN_COST, PointsPolicy.saveCost(ScanCaptureMode.BOOK, 50))
        assertEquals(7, PointsPolicy.saveCost(ScanCaptureMode.BOOK, 7))
    }

    @Test fun zeroOrNegativePagesCostNothing() {
        assertEquals(0, PointsPolicy.saveCost(ScanCaptureMode.BATCH, 0))
        assertEquals(0, PointsPolicy.saveCost(ScanCaptureMode.BATCH, -3))
    }

    @Test fun newPersonStartsWithTheStartingGift() {
        assertEquals(PointsPolicy.START_BALANCE, PointsLedger().balance)
    }

    @Test fun spendingNeverGoesNegative() {
        val ledger = PointsLedger(balance = 3)
        assertFalse(ledger.canSpend(4))
        assertSame(ledger, ledger.spend(4))
        assertEquals(1, ledger.spend(2).balance)
        assertTrue(ledger.canSpend(3))
        assertEquals(0, ledger.spend(3).balance)
    }

    @Test fun dailyBonusComesOncePerDay() {
        val first = PointsLedger(balance = 10).claimDailyBonus(100)
        assertEquals(12, first.balance)
        assertFalse(first.dailyBonusAvailable(100))
        assertSame(first, first.claimDailyBonus(100))
        assertEquals(14, first.claimDailyBonus(101).balance)
    }

    @Test fun rewardedVideoGivesFivePointsUpToThreePerDay() {
        var ledger = PointsLedger(balance = 0)
        repeat(PointsPolicy.MAX_ADS_PER_DAY) { ledger = ledger.earnFromAd(50) }
        assertEquals(15, ledger.balance)
        assertEquals(0, ledger.adsLeftToday(50))
        assertSame(ledger, ledger.earnFromAd(50))
    }

    @Test fun videoLimitResetsTheNextDay() {
        var ledger = PointsLedger(balance = 0)
        repeat(3) { ledger = ledger.earnFromAd(50) }
        assertEquals(3, ledger.adsLeftToday(51))
        assertEquals(20, ledger.earnFromAd(51).balance)
    }

    @Test fun earnedPointsStopAtTheCapButNeverShrinkABigGift() {
        assertEquals(PointsPolicy.MAX_BALANCE, PointsLedger(balance = 98).earnFromAd(1).balance)
        assertEquals(PointsPolicy.MAX_BALANCE, PointsLedger(balance = PointsPolicy.MAX_BALANCE).claimDailyBonus(1).balance)
        assertEquals(130, PointsLedger(balance = 130).earnFromAd(1).balance)
    }
}
