package starling.instrument.physical

import starling.quantity.Quantity
import starling.quantity.UOM._
import starling.market.formula.FormulaIndex
import starling.curves.Environment
import starling.pricingschedule.{PricingSchedule}
import starling.market.rules.SwapPricingRule
import starling.daterange.{DateRange, DayAndTime, Day}
import starling.instrument._
import starling.richdb.RichInstrumentResultSetRow

case class Cargo(quantity: Quantity, incoterm: Incoterm, blDate: Day, index: FormulaIndex, pricingSchedule: PricingSchedule, pricingRule: SwapPricingRule)
  extends UTP with Tradeable {

  val calendar = pricingRule.calendar(index.markets)
  val algorithm = pricingSchedule.algorithm
  val pricingPeriod = algorithm.calculateSchedule(calendar)
  val pricingDaysAndRatios = algorithm.applyPricingRule(calendar, pricingPeriod)
  val pricingDays = pricingDaysAndRatios.map(_._1)

  def isLive(dayAndTime: DayAndTime) = {
    dayAndTime < pricingDays.last.endOfDay
  }

  def asUtpPortfolio(tradeDay: Day) = UTP_Portfolio(Map(this.copy(quantity = quantity.copy(value = 1.0)) -> quantity.value))

  def valuationCCY = USD

  def assets(env: Environment) = {
    // TODO fix this
    val settlementDate = pricingDays.last
    Assets(
      // TODO We should have a physical part but I'm not sure how to do it. The physical is usually something like 'Forties'
      // which isn't a market.
      //Asset.knownPhysical(market, deliveryDay, marketVolume, env),
      Asset.knownCash(settlementDate, -price(env) * quantity, env)
    )
  }

  def price(env: Environment) = {
    val prices = pricingDaysAndRatios.map {
      case (day, ratio) => {
        val price = env.averagePrice(index, day, pricingRule, USD / BBL)
        (day, price * ratio)
      }
    }
    prices.map(_._2).sum
  }

  def periodKey = Some(DateRange(pricingDays.head, pricingDays.last))

  def *(x: Double) = copy(quantity = quantity * x)

  def daysForPositionReport(marketDay: DayAndTime) = pricingDays

  def volume = quantity

  def details = Map()

  def tradeableDetails = Map()

  def tradeableType = Cargo

  def instrumentType = Cargo
}

object Cargo extends InstrumentType[CommodityForward] with TradeableType[CommodityForward] {
  def sample = null

  def createTradeable(row: RichInstrumentResultSetRow) = null

  val name = "Cargo"
}