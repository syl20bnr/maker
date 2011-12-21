package starling.instrument.physical

import starling.curves.Environment
import starling.daterange._
import starling.quantity.{UOM, Quantity}
import starling.quantity.UOM._
import starling.market._

trait TitanPricingSpec {

  def premiumExcludingVAT(env : Environment) : Quantity = {
    val p = premium(env).named("Premium")
    val premiumInPremiumCurrency = vatLiableForCurrency(env, p) match {
      case Some(vat) => (p / toDisplayVat(vat)).named("Premium Excl VAT")
      case _ => p
    }
    inValuationCurrency(env, premiumInPremiumCurrency)
  }

  def premiumIncludingVAT(env : Environment) : Quantity = {
    val p = premium(env).named("Premium")
    inValuationCurrency(env, p)
  }

  def priceExcludingVATExcludingPremium(env: Environment): Quantity
  def priceIncludingVATExcludingPremium(env : Environment) : Quantity

  def priceExcludingVATIncludingPremium(env : Environment) : Quantity =
    priceExcludingVATExcludingPremium(env) + premiumExcludingVAT(env)

  def priceIncludingVATIncludingPremium(env : Environment) : Quantity =
    priceIncludingVATExcludingPremium(env) + premiumIncludingVAT(env)

  def valueExcludingVATExcludingPremium(env : Environment, quantity : Quantity) =
    priceExcludingVATExcludingPremium(env) * quantity * env.discount(valuationCCY, settlementDay(env.marketDay))

  def valueExcludingVATIncludingPremium(env : Environment, quantity : Quantity) =
    priceExcludingVATIncludingPremium(env) * quantity * env.discount(valuationCCY, settlementDay(env.marketDay))

  def valueIncludingVATExcludingPremium(env : Environment, quantity : Quantity) =
    priceIncludingVATExcludingPremium(env) * quantity * env.discount(valuationCCY, settlementDay(env.marketDay))

  def valueIncludingVATIncludingPremium(env : Environment, quantity : Quantity) =
    priceIncludingVATIncludingPremium(env) * quantity * env.discount(valuationCCY, settlementDay(env.marketDay))

  def settlementDay(marketDay: DayAndTime): Day

  def valuationCCY: UOM
  assert(valuationCCY != null, "Valuation currency is null")

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity): Quantity

  def isComplete(marketDay: DayAndTime): Boolean

  def pricingType: String

  def daysForPositionReport(marketDay: DayAndTime): List[Day]

  def quotationPeriod: Option[DateRange]

  // Working assumption is that premium is inclusive of VAT when the relevant exchange includes it
  def premium(env : Environment) : Quantity

  def indexOption: Option[IndexWithDailyPrices]

  def expiryDay: Day
  def futuresMarket : FuturesMarket


  protected def inValuationCurrency(env : Environment, p : Quantity) =
    inCurrency(env, p, valuationCCY)

  protected def inMarketCurrency(env : Environment, p : Quantity) =
    inCurrency(env, p, futuresMarket.currency)

  private def inCurrency(env : Environment, p : Quantity, ccy : UOM) = {
    if (p == Quantity.NULL)
      p
    else {
      val namedEnv = env.withNaming()
      val baseCurrency = p.numeratorUOM.inBaseCurrency
      val priceInBaseCurrency = p inUOM (baseCurrency / p.denominatorUOM)
      val settlementDay_ : Day = settlementDay(namedEnv.marketDay)
      val fxRate = namedEnv.forwardFXRate(ccy, priceInBaseCurrency.numeratorUOM, settlementDay_).named("Forward FX (" + settlementDay_ + ")")

      priceInBaseCurrency * fxRate
    }
  }

  protected def addPremiumConvertingIfNecessary(env: Environment, price: Quantity, premium: Quantity): Quantity = {
    val priceInValuationCurrency = inValuationCurrency(env, price)

    if (premium == Quantity.NULL) // no premium case
      priceInValuationCurrency
    else
      priceInValuationCurrency + inValuationCurrency(env, premium)
  }

  private def addVATIfLiable(env : Environment, q : Quantity) =
    vatLiableForCurrency(env, q) match {
      case Some(vat) => q * toDisplayVat(vat)
      case _ => q
    }

  protected def subtractVATIfLiable(env : Environment, price : Quantity) =
    vatLiableForCurrency(env, price) match {
      case Some(vat) => price / toDisplayVat(vat)
      case _ => price
    }

  private def vatLiableForCurrency(env : Environment, q : Quantity) = {
    val numUom = q.numeratorUOM
    if (numUom.isCurrency) {
      numUom.inBaseCurrency match {
        case CNY => Some(env.shanghaiVATRate)
        case _ => None
      }
    }
    else None
  }

  // ensure VAT is displayed in explanation in the form 120% rather than 20%, 0.2 + 1.0 or similar
  protected def toDisplayVat(vat : Quantity) = (vat + 1.0).unnamed.named("VAT")
}

object TitanPricingSpec {
  def calcSettlementDay(index: IndexWithDailyPrices, day: Day) = {
    val lme = FuturesExchangeFactory.LME
    val market: CommodityMarket = index.market
    market.asInstanceOf[FuturesMarket].exchange match {
      case `lme` => day.addBusinessDays(market.businessCalendar, 2)
      case _ => day
    }
  }

  /**
   * Used for unknown pricing spec and also benchmarks
   * Nothing in common really and its use for the latter is probably wrong
   */
  def representativeObservationDay(index :IndexWithDailyPrices, observingPeriod : DateRange, marketDay : DayAndTime) : Day = {
    val lme = FuturesExchangeFactory.LME
    index.market.asInstanceOf[FuturesMarket].exchange match {
      case `lme` => {
        val thirdWednesdayIsObservedOnDay = (observingPeriod.firstDay.dayOnOrAfter(DayOfWeek.wednesday) + 14).addBusinessDays(index.businessCalendar, -2)
        if (marketDay >= thirdWednesdayIsObservedOnDay.endOfDay)
          observingPeriod.lastDay.thisOrPreviousBusinessDay(index.businessCalendar)
        else
          thirdWednesdayIsObservedOnDay
      }
      case _ => observingPeriod.lastDay.thisOrPreviousBusinessDay(index.market.businessCalendar)
    }
  }

  /**
   * Shanghai uses Contract based averaging, rather than Calendar based
   */
  def averagingPeriod(index: IndexWithDailyPrices, month: Month) = index match {
    case fi: FuturesFrontPeriodIndex if fi.futuresMarket.exchange == FuturesExchangeFactory.SHFE => fi.frontContractPeriod(month)
    case vwi: ShfeVwapMonthIndex => vwi.baseFrontFuturesMarketIndex.frontContractPeriod(month)
    case _ => month
  }
}

case class AveragePricingSpec(index: IndexWithDailyPrices, averagingPeriod: DateRange,
                              premium: Quantity, valuationCCY : UOM) extends TitanPricingSpec {
  val observationDays = index.observationDays(averagingPeriod)
  
  // Just a guess
  def settlementDay(marketDay: DayAndTime) = TitanPricingSpec.calcSettlementDay(index, averagingPeriod.lastDay)

  def fixedQuantity(marketDay: DayAndTime,
                    totalQuantity: Quantity): Quantity = totalQuantity * observedDays(marketDay).size / observationDays.size

  def pricingType: String = "Month Average"

  protected def observedDays(marketDay: DayAndTime) = observationDays.filter(_.endOfDay <= marketDay)

  def isComplete(marketDay: DayAndTime) = observedDays(marketDay).size == observationDays.size

  def indexOption = Some(index)

  def quotationPeriod = Some(averagingPeriod)

  def daysForPositionReport(marketDay: DayAndTime) = observationDays.filter(_.endOfDay > marketDay)

  def premium(env : Environment) = premium

  def expiryDay = TitanPricingSpec.calcSettlementDay(index, averagingPeriod.lastDay)

  def priceExcludingVATExcludingPremium(env : Environment) = {
    inValuationCurrency(env, subtractVATIfLiable(env, env.averagePrice(index, averagingPeriod)))
  }

  def priceIncludingVATExcludingPremium(env:Environment) = {
    inValuationCurrency(env, env.averagePrice(index, averagingPeriod))
  }

  def futuresMarket : FuturesMarket = index.market.asInstanceOf[FuturesMarket]
}

case class OptionalPricingSpec(choices: List[TitanPricingSpec], declarationDay: Day,
                               chosenSpec: Option[TitanPricingSpec]) extends TitanPricingSpec {

  lazy val specToUse = chosenSpec.getOrElse(choices.head)

  def futuresMarket = specToUse.futuresMarket

  def settlementDay(marketDay: DayAndTime) = specToUse.settlementDay(marketDay)

  def priceExcludingVATExcludingPremium(env: Environment) = {
    assert(chosenSpec.isDefined || env.marketDay < declarationDay.endOfDay, "Optional pricing spec must be fixed by " + declarationDay)
    inValuationCurrency(env, specToUse.priceExcludingVATExcludingPremium(env))
  }

  def priceIncludingVATExcludingPremium(env : Environment) : Quantity = {
    assert(chosenSpec.isDefined || env.marketDay < declarationDay.endOfDay, "Optional pricing spec must be fixed by " + declarationDay)
    inValuationCurrency(env, specToUse.priceIncludingVATExcludingPremium(env))
  }

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity) = specToUse.fixedQuantity(marketDay, totalQuantity)

  def isComplete(marketDay: DayAndTime) = specToUse.isComplete(marketDay)

  def pricingType: String = chosenSpec match {
    case Some(spec) => spec.pricingType;
    case None => "Optional"
  }

  def premium(env : Environment) = specToUse.premium(env)

  def daysForPositionReport(marketDay: DayAndTime) = specToUse.daysForPositionReport(marketDay)

  def valuationCCY = specToUse.valuationCCY

  def quotationPeriod = specToUse.quotationPeriod

  def indexOption = specToUse.indexOption

  def expiryDay = specToUse.expiryDay
}

case class WeightedPricingSpec(specs: List[(Double, TitanPricingSpec)], valuationCCY : UOM) extends TitanPricingSpec {
  def settlementDay(marketDay: DayAndTime) = specs.flatMap(_._2.settlementDay(marketDay)).sortWith(_ > _).head

  def priceExcludingVATExcludingPremium(env: Environment) = Quantity.sum(specs.map {
    case (weight, spec) => inValuationCurrency(env, spec.priceExcludingVATExcludingPremium(env)) * weight
  })

  override def priceIncludingVATExcludingPremium(env : Environment) : Quantity = Quantity.sum(specs.map {
    case (weight, spec) => inValuationCurrency(env, spec.priceIncludingVATExcludingPremium(env)) * weight })

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity) = specs.map {
    case (weight, spec) => spec.fixedQuantity(marketDay, totalQuantity) * weight
  }.sum

  def isComplete(marketDay: DayAndTime) = specs.forall {
    _._2.isComplete(marketDay)
  }

  def pricingType: String = "Weighted"

  private def quotationPeriodStart: Option[Day] = specs.map(_._2.quotationPeriod).collect {
    case Some(period) => period.firstDay
  }.sortWith(_ < _).headOption

  private def quotationPeriodEnd: Option[Day] = specs.map(_._2.quotationPeriod).collect {
    case Some(period) => period.lastDay
  }.sortWith(_ < _).lastOption

  override def premium(env : Environment) = specs.map {
    case (wt, spec) => inValuationCurrency(env, spec.premium(env)) * wt
  }.sum

  def daysForPositionReport(marketDay: DayAndTime) = specs.flatMap {
    case (amt, spec) => spec.daysForPositionReport(marketDay)
  }.distinct

  def quotationPeriod = (quotationPeriodStart, quotationPeriodEnd) match {
    case (Some(d1), Some(d2)) => Some(DateRange(d1, d2))
    case _ => None
  }

  def indexOption = None

  def expiryDay = specs.map(_._2.expiryDay).max

  def futuresMarket = {
    val markets = specs.map(_._2.futuresMarket).distinct
    assert(markets.size == 1, "Expected a single associated futures market - got " + markets.mkString(", "))
    markets.head
  }

  override def premiumExcludingVAT(env : Environment) : Quantity =
    specs.map{ case (weight, spec) => inValuationCurrency(env, spec.premiumExcludingVAT(env)) * weight }.sum

  override def premiumIncludingVAT(env : Environment) : Quantity =
    specs.map{ case (weight, spec) => inValuationCurrency(env, spec.premiumIncludingVAT(env)) * weight }.sum
}

case class InvalidTitanPricingSpecException(msg: String) extends Exception(msg)

case class FixedPricingSpec(futuresMarket : FuturesMarket, settDay: Day, pricesByFraction: List[(Double, Quantity)],
                            premium: Quantity, valuationCCY : UOM) extends TitanPricingSpec {

  def settlementDay(marketDay: DayAndTime) = settDay

  private def priceExclPremium(env : Environment) : Quantity = {
    val totalFraction = pricesByFraction.map(_._1).sum
    if (totalFraction == 0) {
      throw new InvalidTitanPricingSpecException("Fixed Pricing Spec with no fixed prices")
    } else {
      Quantity.sum(pricesByFraction.zipWithIndex.map {
        case ((qty, prc), i) => inMarketCurrency(env, prc.named("Fix " + i)) * qty
      }) / totalFraction
    }
  }

  def priceExcludingVATExcludingPremium(env : Environment) =
    inValuationCurrency(env, subtractVATIfLiable(env, priceExclPremium(env)))

  def priceIncludingVATExcludingPremium(env : Environment) =
    inValuationCurrency(env, priceExclPremium(env))

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity) = totalQuantity

  def isComplete(marketDay: DayAndTime) = true

  def pricingType: String = "Fixed"

  def quotationPeriodStart: Option[Day] = None

  def quotationPeriodEnd: Option[Day] = None

  def indexName: String = "No Index"

  def daysForPositionReport(marketDay: DayAndTime) = List(marketDay.day)

  def quotationPeriod = None

  def indexOption = None

  def expiryDay = settDay

  def premium(env : Environment) = premium
}

case class UnknownPricingFixation(fraction: Double, price: Quantity)


case class UnknownPricingSpecification(
              index: IndexWithDailyPrices,
              observingPeriod : DateRange,
              fixations: List[UnknownPricingFixation],
              declarationDay: Day,
              premium: Quantity,
              valuationCCY : UOM) extends TitanPricingSpec {

  /**Bug in trade management. Neptune store 'no fixations' as a single fixation with zero price */
  private lazy val fixationsSansZeroPrice = fixations.filterNot(_.price.isZero)
  def settlementDay(marketDay: DayAndTime) = TitanPricingSpec.calcSettlementDay(index, unfixedPriceDay(marketDay))

  private def unfixedPriceDay(marketDay : DayAndTime) = TitanPricingSpec.representativeObservationDay(index, observingPeriod, marketDay)

  private def priceExclPremium(env : Environment) = {
    val totalFixed = fixationsSansZeroPrice.map(_.fraction).sum
    val unfixedFraction = 1.0 - totalFixed

    val fixedPayment = {
      Quantity.sum(fixationsSansZeroPrice.zipWithIndex.map {
        case (f, i) => f.price.named("Fix_" + i) * f.fraction
      }).named("Fixed")
    }

    val unfixedPayment = (index.fixingOrForwardPrice(env, unfixedPriceDay(env.marketDay)) * unfixedFraction).named("Unfixed")

    if (fixedPayment.isZero)
      inMarketCurrency(env, unfixedPayment)
    else if (unfixedPayment.isZero)
      inMarketCurrency(env, fixedPayment)
    else
      inMarketCurrency(env, fixedPayment) + inMarketCurrency(env, unfixedPayment)
  }

  def priceExcludingVATExcludingPremium(env: Environment) =
    inValuationCurrency(env, subtractVATIfLiable(env, priceExclPremium(env)))

  def priceIncludingVATExcludingPremium(env: Environment) =
    inValuationCurrency(env, priceExclPremium(env))

  def fixedQuantity(marketDay: DayAndTime, totalQuantity: Quantity) = totalQuantity * fixationsSansZeroPrice.map(_.fraction).sum

  def isComplete(marketDay: DayAndTime) = declarationDay.endOfDay >= marketDay

  def pricingType: String = "Unknown"

  def daysForPositionReport(marketDay: DayAndTime) = index.observationDays(observingPeriod).filter(_.endOfDay > marketDay)

  def quotationPeriod = Some(observingPeriod)

  def indexOption = Some(index)

  def expiryDay = TitanPricingSpec.calcSettlementDay(index, index.observationDays(observingPeriod).last)

  def futuresMarket : FuturesMarket = index.market.asInstanceOf[FuturesMarket]

  def premium(env : Environment) = premium
}
