package starling.edm

import starling.quantity.UOMSymbol._
import starling.daterange.{Tenor, SimpleDateRange, Day}

import starling.utils.ImplicitConversions._
import starling.quantity.{UOMSymbol, Percentage, UOM, Quantity}
import com.trafigura.services.marketdata.{MaturityType, NamedMaturity, RelativeMaturity}
import com.trafigura.edm.shared.types.{Quantity => QuantityE, Currency => CurrencyE, Percentage => PercentageE, CompoundUOM, UnitComponent, FundamentalUOM}
import com.trafigura.services.marketdata.Maturity

case class InvalidUomException(msg : String) extends Exception(msg)

object EDMConversions {
  implicit def enrichQuantity(q: Quantity) = new {
    def toEDM = toQuantityE(q)
  }
  implicit def enrichTenor(tenor: Tenor) = new {
    def toEDM: Maturity = (tenor, tenor.tenorType.toString) match {
      case (Tenor.ON, _) => NamedMaturity.ON
      case (Tenor.SN, _) => NamedMaturity.SN
      case (tenor, MaturityType.Parse(maturityType)) => RelativeMaturity(tenor.value, maturityType)
    }
  }
  implicit def enrichUOM(uom: UOM) = new {
    def toCurrency: CurrencyE = CurrencyE().update(_.name = toEDM.name)
    def toEDM: FundamentalUOM = FundamentalUOM(starlingUomToEdmUomName(uom))
  }
  implicit def enrichPercentage(percentage: Percentage) = new {
    def toEDM = PercentageE(Some(percentage.value))
  }

  implicit def enrichQuantityE(q: QuantityE) = new {
    def fromEDM = fromQuantityE(q)
  }
  implicit def enrichEDMDate(date: com.trafigura.edm.shared.types.Date) = new {
    def fromEDM = Day.fromLocal(date.datex)
  }
  implicit def enrichEDMDateRange(dateRange: com.trafigura.edm.shared.types.DateRange) = new {
    def fromEDM = new SimpleDateRange(startDay, endDay)
    def contains(date: com.trafigura.edm.shared.types.Date) = fromEDM.contains(date.fromEDM)
    def startDay = Day.fromLocal(dateRange.startDate)
    def endDay = Day.fromLocal(dateRange.endDate)
  }
  implicit def enrichFundamentalUOM(uom: com.trafigura.edm.shared.types.FundamentalUOM) = new {
    def fromEDM = edmToStarlingUomSymbol(uom.name).asUOM
    def toCurrency: CurrencyE = CurrencyE().update(_.name = uom.name)
  }

  implicit def fromQuantityE(q : QuantityE) : Quantity = {
    val amount = q.amount match {
      case Some(amt) => amt
      case None => throw new Exception("Invalid quantity - no amount")
    }  // No idea why this is optional in EDM
    val uom = UOM.fromSymbolMap(q.uom.components.map {
      case uc => {
        edmToStarlingUomSymbol.get(uc.fundamental.name) match {
          case Some(uomSymbol) => uomSymbol -> uc.exponent
          case None => throw new InvalidUomException(uc.fundamental.name)
        }
      }
    }.toMap)
    Quantity(amount, uom)

  }

  implicit def toQuantityE(q : Quantity) : QuantityE = {
    val symbolPowers = q.uom.asSymbolMap()

    // create edm UOMs, EDM symbol list is GBP, USD, JPY, RMB, MTS, LBS

    val unitComponents = symbolPowers.map{
      case (starlingUOMSymbol, power) => UnitComponent(
        oid = 0,
        exponent = power,
        fundamental = FundamentalUOM(starlingUomSymbolToEdmUom.getOrElse(starlingUOMSymbol, starlingUOMSymbol.toString))
       )
    }.toList

    QuantityE(Some(q.value), CompoundUOM(unitComponents))
  }

  val starlingUomSymbolToEdmUom = Map(
    gbp -> "GBP",
    usd -> "USD",
    jpy -> "JPY",
    cny -> "RMB",
    TONNE_SYMBOL -> "MTS",
    POUND_SYMBOL -> "LBS"
  )

  val starlingUomToEdmUomName: Map[UOM, String] = starlingUomSymbolToEdmUom.mapKeys(_.asUOM)
  val edmToStarlingUomSymbol: Map[String, UOMSymbol] = starlingUomSymbolToEdmUom.map(_.swap)
}
