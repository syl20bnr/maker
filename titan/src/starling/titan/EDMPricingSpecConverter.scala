package starling.titan

import starling.daterange.Day
import com.trafigura.edm.trademgmt.physicaltradespecs.{PricingSpecification, FixedPricingSpecification, MonthAveragePricingSpecification, PartialAveragePricingSpecification,OptionalPricingSpecification,WeightedPricingSpecification,UnknownPricingSpecification => UNKPricingSpecification}
import com.trafigura.edm.trademgmt.physicaltradespecs.{CashAveragePricingSpecificationIndex, ThreeMonthAveragePricingSpecificationIndex, LowestOfFourAveragePricingSpecificationIndex, AverageOfFourAveragePricingSpecificationIndex, MaxSettlementAveragePricingSpecificationIndex, CashUnknownPricingSpecificationIndex, ThreeMonthUnknownPricingSpecificationIndex, LowestOfFourUnknownPricingSpecificationIndex, AverageOfFourUnknownPricingSpecificationIndex, MaxSettlementUnknownPricingSpecificationIndex, AveragePricingSpecificationIndexEnum, UnknownPricingSpecificationIndexEnum, PartialAverageDayQuantity}
import com.trafigura.trademgmt.internal.refinedmetal.{Metal, Market}
import starling.instrument._
import physical._
import starling.quantity.{UOM, Quantity}
import starling.titan.EDMConversions._
import starling.daterange.DateRange
import starling.market.IndexWithDailyPrices
import starling.utils.ImplicitConversions._
import starling.market.FuturesMarket


trait TitanIndexName {
  def name : String
}

case object CashIndex extends TitanIndexName { val name = "Cash"} 
case object ThreeMonthIndex extends TitanIndexName { val name = "Three Month"} 
case object LowestOfFourIndex extends TitanIndexName { val name = "Lowest of Four"} 
case object AverageOfFourIndex extends TitanIndexName { val name = "Average of Four"} 
case object Ave4MaxSettIndex extends TitanIndexName { val name = "Max Settlement"} 

object TitanIndexName {
  val cashAverageIndex = CashAveragePricingSpecificationIndex 
  val threeMonthAverageIndex = ThreeMonthAveragePricingSpecificationIndex 
  val lowestOfFourAverageIndex = LowestOfFourAveragePricingSpecificationIndex 
  val averageOfFourAverageIndex = AverageOfFourAveragePricingSpecificationIndex 
  val maxSettlementAverageIndex = MaxSettlementAveragePricingSpecificationIndex 

  implicit def fromAveragePricingSpecificationIndexEnum (index : AveragePricingSpecificationIndexEnum) : TitanIndexName = {
    index match {
      case `cashAverageIndex` => CashIndex
      case `threeMonthAverageIndex` => ThreeMonthIndex
      case `lowestOfFourAverageIndex` => LowestOfFourIndex
      case `averageOfFourAverageIndex` => AverageOfFourIndex
      case `maxSettlementAverageIndex` => Ave4MaxSettIndex
      case _ | null => CashIndex // Hack to work around  broken trade management service
      //case _ => throw new Exception("Unrecognised index " + index)
    }
  }

  val cashUnknownIndex = CashUnknownPricingSpecificationIndex 
  val threeMonthUnknownIndex = ThreeMonthUnknownPricingSpecificationIndex 
  val lowestOfFourUnknownIndex = LowestOfFourUnknownPricingSpecificationIndex 
  val averageOfFourUnknownIndex = AverageOfFourUnknownPricingSpecificationIndex 
  val maxSettlementUnknownIndex = MaxSettlementUnknownPricingSpecificationIndex 

  implicit def fromAveragePricingSpecificationIndexEnum (index : UnknownPricingSpecificationIndexEnum) : TitanIndexName = {
    index match {
      case `cashUnknownIndex` => CashIndex
      case `threeMonthUnknownIndex` => ThreeMonthIndex
      case `lowestOfFourUnknownIndex` => LowestOfFourIndex
      case `averageOfFourUnknownIndex` => AverageOfFourIndex
      case `maxSettlementUnknownIndex` => Ave4MaxSettIndex
      case _ | null => CashIndex // Hack to work around broken trade management service
      //case _ => throw new Exception("Unrecognised index " + index)
    }
  }
}

case class EDMPricingSpecConverter(metal : Metal, exchanges : String => Market) {
  import TitanIndexName._
  def getIndex(exchangeID : String, indexName : TitanIndexName) : IndexWithDailyPrices = {
    RefinedTacticalRefDataConversions.index(exchanges(exchangeID), metal, indexName)
  }
  def getFuturesMarket(exchangeID : String) : FuturesMarket = {
    RefinedTacticalRefDataConversions.market(exchanges(exchangeID), metal)
  }

  def fromEdmPricingSpec(
    deliveryDay : Day, 
    deliveryQuantity : Quantity, 
    edmPricingSpec : PricingSpecification, 
    // Trade Mgmt only seems to provide a currency at the top level of a nested pricing spec, hence
    // we have to jump through some hoops.
    valuationCurrency : Option[UOM] = None
  ) : TitanPricingSpec = {
    Option(edmPricingSpec) match {
      case Some(edmPSpec) => edmPSpec match {
        case spec : MonthAveragePricingSpecification => {
          AveragePricingSpec(
            getIndex(spec.market, spec.index),
            Day.fromJodaDate(spec.qpMonth).containingMonth,
            spec.premium,
            valuationCurrency.getOrElse(spec.currency)
          )
        }
        case spec : PartialAveragePricingSpecification => {
          AveragePricingSpec(
            getIndex(spec.market, spec.index),
            DateRange(Day.fromJodaDate(spec.firstAvgDate), Day.fromJodaDate(spec.lastAvgDate)),
            spec.premium,
            valuationCurrency.getOrElse(spec.currency)
          )
        }
        case spec : OptionalPricingSpecification => {
          val ccy : Option[UOM] = Option(spec.currency)
          OptionalPricingSpec(
            spec.choices.map(fromEdmPricingSpec(deliveryDay, deliveryQuantity, _, ccy)),
            Day.fromJodaDate(spec.declarationBy),
            if (spec.chosenSpec == null)
              None
            else
              Some(fromEdmPricingSpec(deliveryDay, deliveryQuantity, spec.chosenSpec, ccy))
          )
        }
        case spec : WeightedPricingSpecification => {
          val ccy : Option[UOM] = Option(spec.currency)
          WeightedPricingSpec(
            spec.wtdSpecs.map{
              case weightedSpec =>
                 (weightedSpec.weight, fromEdmPricingSpec(deliveryDay, deliveryQuantity * weightedSpec.weight, weightedSpec.pricingSpec, ccy))
            },
            valuationCurrency.getOrElse(spec.currency)
          )
        }
        case spec : UNKPricingSpecification => {
          val qpMonth = Day.fromJodaDate(spec.qpMonth).containingMonth
          val index: IndexWithDailyPrices = getIndex(spec.market, spec.index)
          val declarationBy: Day = if (spec.declarationBy == null) qpMonth.lastDay.thisOrPreviousBusinessDay(index.businessCalendar) else Day.fromJodaDate(spec.declarationBy)
          UnknownPricingSpecification(
             index,
             qpMonth,
             spec.fixations.map{
               case fixation =>
                 val fraction = (fromTitanQuantity(fixation.fixedQuantity) / deliveryQuantity).checkedValue(UOM.SCALAR)
                 UnknownPricingFixation(fraction, fromTitanQuantity(fixation.observedPrice))
             },
             declarationBy,
             spec.premium,
             valuationCurrency.getOrElse(spec.currency)
          )
        }
        case spec : FixedPricingSpecification => {
          assert(spec.comps.nonEmpty, "Fixed pricing spec with no fixed prices")
          val exchangeName = spec.hedges.map(_.market).filter(_ != null)/*ignore fx hedge requests*/.uniqueElement("Hedges should all have the same exchange")
          val market = getFuturesMarket(exchangeName)
          // Reasonable guess - The settlement day should live in trade management but doesn't yet
          val settlementDay = spec.comps.flatMap{comp => if (comp.date == null) None else Some(Day.fromLocalDate(comp.date))}.sortWith(_>_).headOption.getOrElse(deliveryDay).addWeekdays(2)
          FixedPricingSpec(
            market,
            settlementDay,
            spec.comps.filterNot{comp => comp.price == null || comp.quantity == null}.map{
              case comp => {
                val fraction = (fromTitanQuantity(comp.quantity) / deliveryQuantity).checkedValue(UOM.SCALAR)
                (fraction, fromTitanQuantity(comp.price))
              }
            },
            Quantity.NULL,  // Customer price = hedge price + premium - however premium can be in a different currency. Need trade management to send hedge as well as customer price
            valuationCurrency.getOrElse(spec.currency)
          )
        }
        case _ => throw new Exception("Unsupported pricing spec type " + edmPricingSpec)
      }
      case None => throw new Exception("Missing pricing spec ")
    }
  }
}
