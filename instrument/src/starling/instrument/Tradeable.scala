package starling.instrument

import physical.PhysicalMetalAssignment
import starling.daterange.{DateRange, DayAndTime, Day}
import starling.richdb.RichInstrumentResultSetRow
import starling.utils.ImplicitConversions._
import starling.market.rules.SwapPricingRule
import starling.curves.Environment
import starling.quantity.{NamedQuantity, SpreadOrQuantity, Quantity}
import starling.quantity.UOM

trait Tradeable extends AsUtpPortfolio {
  def tradeableType : TradeableType[_]
  def persistedTradeableDetails : Map[String, Any]
  def shownTradeableDetails: Map[String, Any] = persistedTradeableDetails
  def expiryDay():Option[Day] = None
  def isLive(dayAndTime : DayAndTime) : Boolean
  def valuationCCY : UOM

  // Return a tree structure describing how mtm was calculated
  def explanation(env : Environment, ccy : UOM) : NamedQuantity = {
    if (ccy == valuationCCY)
      explanation(env)
    else
      explanation(env) * (if (ccy == valuationCCY) new Quantity(1.0) else env.withNaming().spotFXRate(ccy, valuationCCY).named("Spot FX"))
  }
  def explanation(env : Environment) : NamedQuantity 

  /**
   * Hack so that for Jons option the premium has an associated market/index + period
   * so P&L and Theta have a Risk Market and Risk Period
   */
  def fixUpCashInstruments(ci: CashInstrument): CashInstrument = {
     // sometimes the cash instrument has already been assigned to a market and period and we don't want to override that here
    if(ci.index.isEmpty && ci.averagingPeriod.isEmpty)
      fixUpMyCashInstruments(ci)
    else
      ci
  }

  protected def fixUpMyCashInstruments(ci: CashInstrument): CashInstrument = ci
}

trait TradeableType[T <: Tradeable] {
  val name:String
  override def toString = name
  def createTradeable(row: RichInstrumentResultSetRow): T
  def sample:T
  def fields:List[String] = {
    val tradeableFields = sample.shownTradeableDetails.keySet.map(_.removeWhiteSpace.toLowerCase).toList
    val allConvertedFields = TradeableType.fields.map(_.removeWhiteSpace.toLowerCase)
    val matchingFields = allConvertedFields.intersect(tradeableFields)
    matchingFields.map(field => TradeableType.fields(allConvertedFields.indexOf(field)))
  }
}

trait HedgingTradeable extends Tradeable {
  def asUtpPortfolio():UTP_Portfolio
}

object TradeableType {
  val types = List[TradeableType[_ <: Tradeable]](
    DeletedInstrument,
    ErrorInstrument,
    Future,
    FuturesCalendarSpread,
    FuturesCommoditySpread,
    CommoditySwap,
    SwapCalendarSpread,
    FuturesOption,
    CalendarSpreadOption,
    CommoditySpreadOption,
    AsianOption,
    FXForward,
    FXOption,
    RefinedAssignment,
    RefinedFixationsForSplit,
    NetEquityPosition,
    CashInstrument,
    PhysicalMetalAssignment
  )
  def fromName(name : String) = types.find(_.name == name) match {
    case Some(t) => types.find(_.name == name).get // some scala bug means have to do it this way
    case None => throw new Exception("Couldn't find trade with name " + name)
  }
  //the union of the keys in the Instrument#details method
  val fieldsWithType = List(  //This is the order in which the fields will be shown in the GUI
    ("Market", classOf[String]),
    ("Period", classOf[DateRange]),
    ("Quantity", classOf[Quantity]),
    ("Initial Price",classOf[SpreadOrQuantity]),
    ("Strike",classOf[Quantity]),
    ("Exercise Day",classOf[Day]),
//    ("Spread", classOf[Quantity]),
    ("Maturity Day", classOf[Day]),
    ("Delivery Day", classOf[Day]),
    ("Call Put", classOf[String]),
    ("Exercise Type", classOf[String]),
    ("Cleared", classOf[Boolean]),
    ("PricingRule", classOf[SwapPricingRule]),
    //("Fixed Rate", classOf[String]),
    ("RIC", classOf[String]),
    ("Error", classOf[String]),
    ("Estimated Delivery", classOf[Day]),
    ("Fixations", classOf[List[RefinedFixation]]),
    ("Cash Instrument Type", classOf[CashInstrumentType]),
    ("Commodity", classOf[String]),
    ("Pricing Spec Name", classOf[String])
  )
  val fields = fieldsWithType.map(_._1)
  val drillDownFields = fields.filterNot(List("Float Payment Freq", "Fixed Basis", "Fixed Payment Freq", "Fixed Rate",
    "Float Basis", "First Spread Period", "Second Spread Period").contains(_))
  val lowercaseNoSpaceFields = fieldsWithType.map(_._1.toLowerCase.replaceAll(" ", ""))
}