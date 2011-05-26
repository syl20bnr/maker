package starling.varcalculator

import starling.utils.StarlingTest
import org.testng.Assert._
import org.testng.annotations.Test
import starling.daterange.{TestHolidays, Day}
import starling.market.{TestExpiryRules, Market}

class RiskFactorStatisticsTests extends TestExpiryRules {
  @Test
  def testFreightVolScaling{
    assertEquals(RiskFactorStatistics.freightFrontPeriodScaling(Market.BALTIC_CAPESIZE, Day(2009, 5, 31).endOfDay), 2.0, 0.2)
    assertEquals(RiskFactorStatistics.freightFrontPeriodScaling(Market.BALTIC_CAPESIZE, Day(2009, 6, 1).endOfDay), 2.0 - 1.0 / 30.0, 0.2)
    assertEquals(RiskFactorStatistics.freightFrontPeriodScaling(Market.BALTIC_CAPESIZE, Day(2009, 6, 30).startOfDay), 1.0 / 30, 0.2)
  }
}