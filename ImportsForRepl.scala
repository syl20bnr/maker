// Imports a number of packages to make command line interaction with Starling a little easier
import starling.quantity._
import starling.quantity.UOM._
import starling.services.StarlingInit
import starling.market._
import starling.marketdata._
import starling.daterange._
import starling.daterange.Day._
import starling.curves.readers._
import starling.maths._
import starling.instrument._
import starling.db._
import starling.models._
import starling.utils._
import starling.gui.api._
import starling.curves.Environment
import starling.curves.MarketDataCurveObjectEnvironment

def setNullHolidays{
  import starling.calendar._
  import starling.market._
  HolidayTablesFactory.registerHolidayTablesImpl(NullHolidays)
  FuturesExpiryRuleFactory.registerRulesImpl(new FuturesExpiryRules(new BusinessCalendars(NullHolidays)){
    def rule(eaiQuoteID: Int) = new FuturesExpiryRule{
      def lastTradingDay(d: DateRange) = d.firstDay - 1
    }
  })

}

def init() = starling.services.StarlingInit.devInstance

lazy val devInstance = init()

def makeEnv(pricingGroup : PricingGroup, marketDay : Day) : Environment = {
  val marketDataStore = devInstance.marketDataStore

  val marketDataSelection = MarketDataSelection(Some(pricingGroup), None)
  val marketDataID = marketDataStore.latestMarketDataIdentifier(marketDataSelection)
  val reader = new NormalMarketDataReader(marketDataStore, marketDataID)

  val marketDataSlice = new MarketDataReaderMarketDataSlice(reader, ObservationPoint(marketDay, ObservationTimeOfDay.LMEClose))
  Environment(MarketDataCurveObjectEnvironment(marketDay.endOfDay, marketDataSlice))
}