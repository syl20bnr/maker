package starling.instrument.physical

import org.scalatest.FunSuite
import starling.curves.{ForwardPriceKey, IndexFixingKey, ShanghaiVATRateKey, UnitTestingEnvironment}
import starling.daterange.{Day, Month}
import starling.market.{FuturesFrontPeriodIndex, Market, TestMarketTest}
import starling.quantity.Quantity
import starling.quantity.UOM._
import starling.quantity.utils.QuantityTestUtils._
import starling.curves.USDFXRateKey
import starling.curves.DiscountRateKey
import starling.utils.Log
import starling.quantity.UOM

class TitanPricingSpecTests extends FunSuite with TestMarketTest with Log {

  val marketDay = Day(2011, 12, 1).endOfDay

  val env = UnitTestingEnvironment(
    marketDay,
    {
      case ForwardPriceKey(mkt, _, _) => Quantity(100, mkt.priceUOM)
      case IndexFixingKey(index, _) => Quantity(99, index.priceUOM)
      case ShanghaiVATRateKey() => Quantity(17, PERCENT)
      case USDFXRateKey(ccy) => fxRates(ccy)
      case DiscountRateKey(ccy, day, _) => new Quantity(math.exp(- zeroRates(ccy) * day.endOfDay.timeSince(marketDay)))
    }
  )

  val fxRates = Map(
    EUR -> Quantity(1.5, USD/EUR),
    GBP -> Quantity(1.2, USD/GBP),
    CNY -> Quantity(6.2, USD/CNY)
  )

  val zeroRates = Map(
    EUR -> 0.1,
    GBP -> 0.2,
    CNY -> 0.2,
    USD -> 0.25
  )

  test("Prices inclusive/exclusive of VAT are sensible") {

    val averagePricingSpec = AveragePricingSpec(
      FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
      Month(2011, 12),
      Quantity(10, USD/MT),
      CNY
    )

    val unknownPricingSpecification = UnknownPricingSpec(
      FuturesFrontPeriodIndex(Market.SHANGHAI_COPPER),
      Month(2012, 1),
      List(UnknownPricingFixation(0.4, Quantity(100, USD/MT))),
      Day(2012, 1, 31),
      Quantity(15, GBP/MT),
      CNY
    )

    val fixedPricingSpec = FixedPricingSpec(
      Market.SHANGHAI_COPPER,
      Day(2012, 2, 1),
      List(
        (0.4, Quantity(15, EUR/MT)),
        (0.6, Quantity(32, GBP/MT))
      ),
      Quantity(99, USD/MT),
      CNY
    )

    val simpleWeightedPricingSpec = WeightedPricingSpec(
      List(
        (
          0.4,
          AveragePricingSpec(
            FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
            Month(2011, 12),
            Quantity(10, CNY/MT),
            CNY
          )
        ),
        (
          0.6,
          UnknownPricingSpec(
            FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
            Month(2012, 1),
            List(UnknownPricingFixation(0.4, Quantity(100, CNY/MT))),
            Day(2012, 1, 31),
            Quantity(15, CNY/MT),
            CNY
          )
        )
      ),
      CNY
    )

    val optionalPricingSpec = OptionalPricingSpec(
      List(
        AveragePricingSpec(
          FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
          Month(2011, 12),
          Quantity(10, USD/MT),
          CNY),
        UnknownPricingSpec(
          FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
          Month(2012, 1),
          List(UnknownPricingFixation(0.4, Quantity(100, USD/MT))),
          Day(2012, 1, 31),
          Quantity(15, GBP/MT),
          CNY)
      ),
      Day(2012, 1, 1),
      None
    )

    val completelyVatAdjustedSpecs = List(
      averagePricingSpec,
      unknownPricingSpecification,
      fixedPricingSpec,
      simpleWeightedPricingSpec,
      optionalPricingSpec
    )

    // check all the simply (completely) VAT adjusted specs
    completelyVatAdjustedSpecs.foreach(spec => {
      try {
        val priceExclVAT = spec.priceExcludingVATExcludingPremium(env)
        val priceInclVAT = spec.priceIncludingVATExcludingPremium(env)
        val vatRate = env.shanghaiVATRate

        assertQtyEquals(priceExclVAT * (vatRate + 1.0), priceInclVAT, 1e-6, "spec " + spec + " inclusive and exclusing vat mismatch")
        assert(priceInclVAT.numeratorUOM === spec.valuationCCY, "spec " + spec + ", price mismatch against valuation currency")
      }
      catch {
        case th : Throwable => {
          Log.error("problem with spec " + spec, th)
          throw th
        }
      }
    })
  }


  test("Prices in mixed currencies inclusive/exclusive of VAT are sensible across multiple pricing specs") {

    /**
     * check for partially VAT cases, where some of the weighted specs are VAT adjusted and some are not
     */
    val multiVatRateWeightedSpec1 = WeightedPricingSpec(
      List(
        (
          0.2,
          AveragePricingSpec(
            FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
            Month(2011, 12),
            Quantity(10, EUR/MT),
            CNY
          )
        ),
        (
          0.5,
          UnknownPricingSpec(
            FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
            Month(2012, 1),
            List(UnknownPricingFixation(0.5, Quantity(100, USD/MT))),
            Day(2012, 1, 31),
            Quantity(15, USD/MT),
            EUR
          )
        ),
        (
          0.3,
          UnknownPricingSpec(
            FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
            Month(2012, 2),
            List(UnknownPricingFixation(0.5, Quantity(100, CNY/MT))),
            Day(2012, 1, 31),
            Quantity(15, CNY/MT),
            CNY
          )
        )
      ),
      CNY
    )

    val partiallyVatAdjustedSpecs = List(multiVatRateWeightedSpec1)

    partiallyVatAdjustedSpecs.foreach(spec => {
      try {
        val priceExclVAT = spec.priceExcludingVATExcludingPremium(env)
        val priceInclVAT = spec.priceIncludingVATExcludingPremium(env)
        val vatRate = env.shanghaiVATRate
        val priceWithFullVat = priceExclVAT * (vatRate + 1.0)

        assertQtyNotEquals(priceWithFullVat, priceInclVAT, 1e-6, "spec " + spec + " completely inclusive and exclusive vat should not be the same")
        assertQtyLessThan(priceInclVAT, priceWithFullVat, 1e-6, "spec " + spec + " partially inclusive and exclusive vat should be less than fully applied vat")
        assert(priceInclVAT.numeratorUOM === spec.valuationCCY, "spec " + spec + ", price mismatch against valuation currency")
      }
      catch {
        case th : Throwable => {
          Log.error("problem with spec " + spec, th)
          throw th
        }
      }
    })
  }
}

class TitanPricingSpecTestsForVat extends FunSuite with TestMarketTest with Log {

  // Create an environment where all prices are 100 ccy/MT
  // and all exchange rates are 1.0
  // An assignment that is priced half off USD and half off CNY should have a VAT adjustment of 17% / 2

  val marketDay = Day(2011, 12, 1).endOfDay

  val env = UnitTestingEnvironment(
    marketDay,
    {
      case ForwardPriceKey(mkt, _, _) => Quantity(100, mkt.priceUOM)
      case IndexFixingKey(index, _) => Quantity(100, index.priceUOM)
      case ShanghaiVATRateKey() => Quantity(17, PERCENT)
      case USDFXRateKey(ccy) => fxRates(ccy)
      case DiscountRateKey(ccy, day, _) => new Quantity(math.exp(- zeroRates(ccy) * day.endOfDay.timeSince(marketDay)))
    }
  )

  val fxRates = Map(
    EUR -> Quantity(1.0, USD/EUR),
    GBP -> Quantity(1.0, USD/GBP),
    CNY -> Quantity(1.0, USD/CNY)
  )

  val zeroRates = Map(
    EUR -> 0.1,
    GBP -> 0.2,
    CNY -> 0.2,
    USD -> 0.25
  )

  test("Prices mixed inclusive/exclusive of VAT are sensible") {

    val averagePricingSpec = AveragePricingSpec(
      FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
      Month(2011, 12),
      Quantity(10, CNY/MT),
      USD
    )

    val unknownPricingSpecification = UnknownPricingSpec(
      FuturesFrontPeriodIndex(Market.SHANGHAI_COPPER),
      Month(2012, 1),
      List(UnknownPricingFixation(0.4, Quantity(100, CNY/MT))),
      Day(2012, 1, 31),
      Quantity(0, CNY/MT),
      USD
    )

    val fixedPricingSpec = FixedPricingSpec(
      Market.SHANGHAI_COPPER,
      Day(2012, 2, 1),
      List(
        (0.5, Quantity(100, USD/MT)),
        (0.5, Quantity(100, CNY/MT))
      ),
      Quantity(0, USD/MT),
      USD
    )

    val multiVatRateWeightedSpec1 = WeightedPricingSpec(
      List(
        (
          0.5,
          AveragePricingSpec(
            FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
            Month(2011, 12),
            Quantity(10, USD/MT),
            USD
          )
        ),
        (
          0.25,
          AveragePricingSpec(
            FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
            Month(2011, 12),
            Quantity(7, CNY/MT),
            CNY
          )
        ),
        (
          0.25,
          AveragePricingSpec(
            FuturesFrontPeriodIndex(Market.SHANGHAI_ZINC),
            Month(2011, 12),
            Quantity(3, CNY/MT),
            CNY
          )
        )
      ),
      USD
    )

    val partiallyVatAdjustedSpecs = List(
      averagePricingSpec,
      averagePricingSpec.copy(valuationCCY = CNY),
      unknownPricingSpecification,
      unknownPricingSpecification.copy(valuationCCY = CNY),
      fixedPricingSpec,
      fixedPricingSpec.copy(valuationCCY = CNY),
      multiVatRateWeightedSpec1,
      multiVatRateWeightedSpec1.copy(valuationCCY = CNY))

    partiallyVatAdjustedSpecs.foreach(spec => {
      try {
        val priceExclVAT = spec.priceExcludingVATExcludingPremium(env)
        val priceInclVAT = spec.priceIncludingVATExcludingPremium(env)
        val vatRate = env.shanghaiVATRate
        val halfVat = (vatRate / 2.0) + 1.0

        def expectedVat(valCcy : UOM) = if (valCcy == CNY) vatRate + 1.0 else Quantity(100.0, PERCENT)

        val expectedPriceInclVat = (spec match {
          case WeightedPricingSpec(_, _) => halfVat
          case AveragePricingSpec(_, _, _, valCcy) => expectedVat(valCcy)
          case FixedPricingSpec(_, _, _, _, valCcy) => expectedVat(valCcy)
          case UnknownPricingSpec(_, _, _, _, _, valCcy) => expectedVat(valCcy)
          case _ => priceExclVAT * (vatRate + 1.0)
        }) * priceExclVAT

        assertQtyEquals(priceInclVAT, expectedPriceInclVat, 1e-6, "spec " + spec + " partially inclusive and exclusive * vat should not be the same")
        assert(priceInclVAT.numeratorUOM === spec.valuationCCY, "spec " + spec + ", price mismatch against valuation currency")
      }
      catch {
        case th : Throwable => {
          Log.error("problem with spec " + spec, th)
          throw th
        }
      }
    })
  }
}
