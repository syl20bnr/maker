package starling.market

import starling.utils.StringIO
import starling.calendar.{HolidayTablesFactory, BusinessCalendars}

class TestMarketLookup extends MarketLookup {
  val file = StringIO.lines("/starling/market/Markets.csv").toList
  val header = file.head.split('\t').map(_.toLowerCase).zipWithIndex.toMap
  val lines = file.tail.map {
    line => {
      val entries = line.split('\t')
      new MarketParser.Line {
        def get(name: String) = {
          val index = header(name.toLowerCase)
          entries(index).trim
        }
      }
    }
  }

  val businessCalendars = new BusinessCalendars(HolidayTablesFactory.holidayTables)
  val expiryRules = FuturesExpiryRuleFactory.expiryRules

  val marketParser = new MarketParser(businessCalendars, expiryRules)
  val all = marketParser.fromLines(lines)

  val allIndexes = all.flatMap {
    case Right(i: Index) => Some(i)
    case _ => None
  }
  val allFuturesMarkets = all.flatMap {
    case Left(m: FuturesMarket) => Some(m)
    case _ => None
  }
}