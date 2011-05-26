package starling.instrument

import starling.daterange.{DayAndTime, Day}
import starling.quantity.{UOM, Quantity}
import starling.quantity.UOM._
import java.sql.ResultSet
import starling.richdb.RichInstrumentResultSetRow
import starling.utils.ImplicitConversions._
import starling.market.FXMarket
import starling.quantity.Percentage._
import starling.models.{Put, Call, BlackScholes, CallOrPut}
import starling.curves._
import starling.daterange.DateRangePeriod

case class FXOption(
  strike: Quantity,
  volume: Quantity,
  exerciseDate: Day,
  maturityDate: Day,
  callPut : CallOrPut
)
  extends FXTradeable with UTP
{
  def isLive(dayAndTime : DayAndTime) : Boolean = dayAndTime <= exerciseDate.startOfDay

  override def expiryDay() = Some(exerciseDate)

  def details :Map[String, Any] =
    Map("ExerciseDay" -> exerciseDate, "MaturityDay" -> maturityDate, "Strike" -> strike, "Quantity" -> volume, "CallPut" -> callPut)
  def tradeableDetails :Map[String, Any] =
    Map("ExerciseDay" -> exerciseDate, "MaturityDay" -> maturityDate, "Strike" -> strike, "Quantity" -> volume, "CallPut" -> callPut)


  def instrumentType = FXOption
  def tradeableType = FXOption

  override def forwardState(env: Environment, dayAndTime: DayAndTime) = {
    if (dayAndTime < exerciseDate.endOfDay) {
      this
    } else {
      val correctStrikeUOM = correctStrike.uom
      val callPutSign = callPut match { case Call => 1.0; case Put => -1.0}
      callPut.payoff(env.forwardState(exerciseDate.endOfDay).spotFXRate(correctStrikeUOM.numeratorUOM, correctStrikeUOM.denominatorUOM), correctStrike) match {
        case Some(_) => FXForward(strike, volume * callPutSign, maturityDate)
        case None => this
      }
    }
  }

  def assets(env : Environment):Assets = {
    if (! isLive(env.marketDay)){
      return Assets() //should we add cash if in the money ?
    }
    Assets(Asset.estimatedCash(exerciseDate, price(env) * volume, env))
	}


  def asUtpPortfolio(tradeDay:Day) = UTP_Portfolio(Map(FXOption(strike, Quantity(1.0, volume.uom), exerciseDate, maturityDate, callPut) -> volume.value))

  def isInTheMoney(env : Environment) = {
    val price = env.forwardFXRate(correctStrike.uom, maturityDate)
    callPut match {
      case Call => correctStrike < price
      case Put => correctStrike > price
    }
  }

  def * (scale : Double) = copy(volume = volume * scale)

  def price(env : Environment) = {
    val vol = env.impliedVol(FXMarket(strike.uom), maturityDate, exerciseDate, strike)
    val T = exerciseDate.endOfDay.timeSince(env.marketDay)
    var F = env.forwardFXRate(correctStrike.uom, maturityDate)
    BlackScholes.undiscountedOptionPrice(F, correctStrike, callPut, T, vol)
  }
}

object FXOption extends InstrumentType[FXOption] with TradeableType[FXOption] {
  val id = 8
  val name = "FX Option"

  def createTradeable(row: RichInstrumentResultSetRow) = {
    FXOption(row.getQuantity("Strike"), row.getQuantity("Quantity"), row.getDay("ExerciseDay"), row.getDay("MaturityDay"), row.getCallPut("CallPut"))
  }
  def sample = {
    import starling.quantity.Quantity._
    FXOption(1.1(EUR/USD), 999(USD), Day(2009, 9, 8), Day(2009, 10, 10), Put)
  }
}