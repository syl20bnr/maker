package starling.daterange

import starling.utils.Pattern._
import starling.utils.ImplicitConversions._
import starling.utils.Int


case class Tenor(tenorName: String, value: Int) extends Ordered[Tenor] {
  lazy val tenorType = TenorType.typesByShortName(tenorName)

  def compare(that: Tenor) = indexOf(tenorName).compare(indexOf(that.tenorName)) match {
    case 0 => value.compare(that.value)
    case other => other
  }

  override def toString = this match {
    case Tenor.ON   => "ON"
    case Tenor.SN   => "SN"
    case Tenor.CASH => "CASH"
    case _          => value + tenorName
  }

  def toDateRange(day: Day) = tenorType.containing(day) + value

  private def indexOf(tenor: String) = TenorType.ALL_IN_ORDER.indexOf(tenor)
}

object Tenor {
  val Parse = Extractor.regex("""(\d+)(\w+)""") {
    case List(Int(value), TenorType.FromShortName(tenorType)) => Tenor(tenorType, value)
  }.orElse(_.partialMatch {
    case "ON"     => Tenor.ON
    case "SN"     => Tenor.SN
    case "CASH"   => Tenor.CASH
    case "CURMON" => Tenor(Month, 0)
    case "CURQ"   => Tenor(Quarter, 0)
  })

  def parse(any: Any): Tenor = Parse.unapply(any.toString).getOrElse(throw new Exception("Could not parse: " + any))

  /**
   * Value doesn't mean anything outside the context of a particular index. It is used just for
   * sorting tenors in a reasonable way
   */
  def apply(tenorType: TenorType, value: Int): Tenor = Tenor(tenorType.shortName, value)

  val ON          = Tenor(Day, 0)   // Overnight
  val SN          = Tenor(Day, 2)   // Spot Next
  val CASH        = Tenor(Month, 0)
  val OneDay      = Tenor(Day, 1)
  val OneWeek     = Tenor(Week, 1)
  val TwoWeeks    = Tenor(Week, 2)
  val OneMonth    = Tenor(Month, 1)
  val TwoMonths   = Tenor(Month, 2)
  val ThreeMonths = Tenor(Month, 3)
  val SixMonths   = Tenor(Month, 6)
  val NineMonths  = Tenor(Month, 9)
  val TwelveMonths = Tenor(Month, 12)
  val OneYear     = Tenor(Year, 1)
}