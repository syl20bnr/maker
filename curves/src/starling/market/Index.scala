package starling.market

import formula.FormulaIndex
import rules._
import starling.quantity.{Quantity, UOM}
import starling.utils.CaseInsensitive
import starling.utils.ImplicitConversions._
import starling.curves._
import starling.calendar.BrentMonth
import starling.daterange._


case class UnknownIndexException(msg: String, eaiQuoteID: Option[Int] = None) extends Exception(msg)

abstract class Index(val name : CaseInsensitive) {
  val identifier = name.toString
  def priceUOM : UOM
  def uom : UOM

  def precision: Option[Precision]

  def markets: List[CommodityMarket]

  override def toString = name

  /**
   * Hack so I can change how indices are displayed in pvot reports.This should be replaced with 'name',
   * and the strings used in Starling DB changed, however I don't know if this will mess up reading
   * from Trinity. TODO - findout 
   */
  def reportDisplayName = name

  def convert(value: Quantity, uom: UOM): Option[Quantity]

  def convertUOM(value: Quantity, uom : UOM) : Quantity = {
    convert(value, uom) match {
      case Some(beqv) => beqv
      case None => throw new Exception(this + ": Couldn't convert from " + value + " to " + uom)
    }
  }

  override def equals(obj: Any): Boolean = obj match {
    case other: Index => name == other.name
    case _ => false
  }

  override def hashCode = name.hashCode

  def tenor:Option[TenorType]

  /**
   * Swaps and asians often have start/end date not on month boundaries in EAI. In general they do mean the whole month
   * as missing days are not observation days
   */
  def makeAveragingPeriodMonthIfPossible(dateRange : DateRange, rule: SwapPricingRule) : DateRange = {
    if (dateRange.isInstanceOf[Month])
      dateRange
    else {
      val month: Month = dateRange.firstMonth
      if ((month == dateRange.lastMonth) && (month.days.filter(rule.isObservationDay(markets, _)) == dateRange.days.filter(rule.isObservationDay(markets, _)))){
        month
      } else {
        dateRange
      }
    }
  }

  def commodity : Commodity

  def possiblePricingRules: List[SwapPricingRule]

  def indexes: Set[SingleIndex]
}

case class IndexSensitivity(coefficient : Double, index : Index)

/**
 * An Index with a single underlying market
 */
abstract class SingleIndex(name: String, val market : CommodityMarket, val lotSizeOverride: Option[Double] = None,
                           val level: Level = Level.Unknown) extends Index(name) {

  def priceUOM : UOM = market.priceUOM
  def currency = market.currency
  def uom = market.uom
  def precision = market.precision

  def markets = List(market)

  def observationTimeOfDay = ObservationTimeOfDay.Default

  def lotSize = lotSizeOverride match {
    case Some(ls) => Some(ls)
    case None => market.lotSize
  }

  def observationDays(period: DateRange): List[Day]

  def isObservationDay(day: Day): Boolean

  def observedPeriod(observationDay : Day) : DateRange = fixingPeriod(observationDay).period(observationDay)

  def fixingPeriod(day:Day) : FixingPeriod

  def observedOptionPeriod(observationDay: Day): DateRange

  def forwardMarket = market match { case m:ForwardMarket => m; case m:FuturesMarket => new ProxyForwardMarket(m) }

  def fixingHistoryKey(observationDay: Day) =
    FixingsHistoryKey(market, fixingPeriod(observationDay), level, observationTimeOfDay)

  def fixingOrForwardPrice(env : Environment, observationDay : Day) = {
    val price = if (observationDay.endOfDay <= env.marketDay) {
      env.fixing(fixingHistoryKey(observationDay), observationDay)
    } else {
      env.indexForwardPrice(this, observationDay)
    }
    price
  }

  def convert(value: Quantity, uom: UOM): Option[Quantity] = {
    market.convert(value, uom)
  }

  def makeAveragingPeriodMonthIfPossible(dateRange: DateRange): DateRange = {
    makeAveragingPeriodMonthIfPossible(dateRange, CommonPricingRule)
  }

  def tenor = Some(forwardMarket.tenor)

  def possiblePricingRules = List(NoPricingRule)

  def indexes = Set(this)
}

object SingleIndex{
  lazy val commodityMarketToIndex : Map[CommodityMarket, SingleIndex] = {
    PublishedIndex.publishedIndexes.map{
      idx => idx.market -> idx
    }.toMap
  }
}

case class PublishedIndex(
  indexName: String,
  eaiQuoteID: Option[Int],
  override val market: CommodityMarket,
  indexLevel: Level = Level.Mid
)
  extends SingleIndex(indexName, market, level = indexLevel)
{
  def this(indexName: String, eaiQuoteID: Int, market: CommodityMarket) = this(indexName, Some(eaiQuoteID), market)

  def isObservationDay(day: Day) = market.isObservationDay(day)

  def observationDays(period: DateRange) = market.observationDays(period)

  def fixingPeriod(day: Day) = market match {
    case f: FuturesMarket => DateRangeFixingPeriod(f.frontPeriod(day))
    case f: ForwardMarket => DateRangeFixingPeriod(f.underlying(day))
  }

  def observedOptionPeriod(observationDay: Day) = market match {
    case fm: FuturesMarket => fm.frontOptionPeriod(observationDay)
    case fm: ForwardMarket => fm.underlyingOption(observationDay)
  }

  def commodity = market.commodity
}

object PublishedIndex{

  // Fur unit tests
  def apply(name : String, market : CommodityMarket) : PublishedIndex = PublishedIndex(name, None, market)

  /**
   * Published Indexes
   */
  val ROTTERDAM_BARGES = new PublishedIndex("3.5% Fuel FOB Rotterdam Barges", 5, Market.FUEL_FOB_ROTTERDAM_BARGES_3_5)
//  val LBMA_GOLD_AM = new PublishedIndex("Gold AM", 804, Market.LBMA_GOLD)
//  val LBMA_GOLD_AVG = new PublishedIndex("Gold Avg", 804, Market.LBMA_GOLD) // TODO This needs to be an average!
//  val LBMA_GOLD_PM = new PublishedIndex("Gold PM", 847, Market.LBMA_GOLD)
//  val SILVER_BULL = new PublishedIndex("Silver (Bull)", 805, Market.LBMA_SILVER) // TODO what's the difference between these 2?
//  val SILVER_LOW_4_LME = new PublishedIndex("Silver low 4 LME", 805, Market.LBMA_SILVER)
  val No_6_3PC_USGC_Waterborne = new PublishedIndex("No.6 3% USGC Waterborne", 11, Market.No_6_3PC_USGC_Waterborne)
  val UNL_87_USGC_PIPELINE = new PublishedIndex("Unl 87 USGC Pipeline", 34, Market.UNL_87_USGC_PIPELINE)
  val HSFO_180_CST_Singapore = new PublishedIndex("HSFO 180 CST Singapore", 8, Market.HSFO_180_CST_Singapore)
  val HSFO_380_CST_Singapore = new PublishedIndex("HSFO 380 CST Singapore", 134, Market.HSFO_380_CST_Singapore)
  val PREM_UNL_FOB_ROTTERDAM_BARGES = new PublishedIndex("Prem Unl FOB Rotterdam Barges", 17, Market.PREM_UNL_FOB_ROTTERDAM_BARGES)

  val FUEL_FOB_NWE_CARGOES_1 = new PublishedIndex("1% Fuel FOB NWE Cargoes", 3, Market.FUEL_FOB_NWE_CARGOES_1)
  val NAPHTHA_CIF_NWE_CARGOES = new PublishedIndex("Naphtha CIF NWE Cargoes", 37, Market.NAPHTHA_CIF_NWE_CARGOES)
  val GAS_OIL_0_5_SINGAPORE = new PublishedIndex("Gas Oil 0.5 Singapore", 52, Market.GAS_OIL_0_5_SINGAPORE)
  val MOGAS_95_UNL_10PPM_NWE_BARGES = new PublishedIndex("Mogas 95 Unl 10ppm NWE Barges (Argus)", 88, Market.MOGAS_95_UNL_10PPM_NWE_BARGES)
  val UNL_92_SINGAPORE_CARGOES = new PublishedIndex("Unl 92 Singapore Cargoes", 198, Market.UNL_92_SINGAPORE_CARGOES)
  val GAS_OIL_0_1_FOB_ROTTERDAM_BARGES = new PublishedIndex("Gas Oil 0.1% FOB Rotterdam Barges (Platts)", 1011, Market.GAS_OIL_0_1_FOB_ROTTERDAM_BARGES)
  val GAS_OIL_0_1_CIF_NWE_CARGOES = new PublishedIndex("Gas Oil 0.1% CIF NWE Cargoes (Platts)", 1049, Market.GAS_OIL_0_1_CIF_NWE_CARGOES)
  val PREM_UNL_10PPM_FOB_MED_CARGOES = new PublishedIndex("Prem Unl 10ppm FOB Med Cargoes (Platts)", 1183, Market.PREM_UNL_10PPM_FOB_MED_CARGOES)
  val PREM_UNL_EURO_BOB_OXY_NWE_BARGES = new PublishedIndex("Prem Unl Euro-Bob Oxy NWE Barges (Argus)", 1312, Market.PREM_UNL_EURO_BOB_OXY_NWE_BARGES)
  val JET_CIF_NWE_CARGOES = new PublishedIndex("Jet CIF NWE Cargoes", 18, Market.JET_CIF_NWE_CARGOES)
  val GAS_OIL_ULSD_10PPM_CIF_NWE_CARGOES = new PublishedIndex("Gas Oil ULSD 10ppm CIF NWE Cargoes", 598, Market.GAS_OIL_ULSD_10PPM_CIF_NWE_CARGOES)
  val GAS_OIL_ULSD_10PPM_FOB_ROTTERDAM_BARGES = new PublishedIndex("Gas Oil ULSD 10ppm FOB Rotterdam Barges", 883, Market.GAS_OIL_ULSD_10PPM_FOB_ROTTERDAM_BARGES)

  // TODO -- WRONG! -- We have changed the market from PLATTS_BRENT to DATED_BRENT because the latter has the matching LIM Symbol
  val FORTIES_CRUDE_1ST_MONTH = new PublishedIndex("Forties Crude 1st month (Platts)", 332, Market.DATED_BRENT)

  val DATED_BRENT = new PublishedIndex("Dated Brent", 40, Market.DATED_BRENT)
  val URALS_CIF_MED = new PublishedIndex("Urals CIF Med Recombined (RCMB)", 159, Market.URALS_CIF_MED)

  val PLATTS_BRENT: Map[BrentMonth, PublishedIndex] = {
    (1 to 12).map {
      i => {
        val month = new BrentMonth(i)
        (month -> new PublishedIndex("Platts " + month, None, Market.PLATTS_BRENT_MONTH_MARKETS(month), indexLevel = Level.MidPoint)
    )
      }
    }
  }.toMap

  val PANAMAX_TC_AVG = PublishedIndex("Panamax T/C Avg", Some(511), Market.BALTIC_PANAMAX, Level.Val)
  val SUPRAMAX_TC_AVG = new PublishedIndex("Supramax T/C Avg", Some(1306), Market.BALTIC_SUPRAMAX, Level.Val)
  val CAPSIZE_TC_AVG = new PublishedIndex("Capsize T/C Avg", Some(512), Market.BALTIC_CAPESIZE, Level.Val)
  val C7_TC_AVG = new PublishedIndex("C7 T/C Avg", Some(524), Market.BALTIC_CAPESIZE_C7, Level.Val)

  val publishedIndexes:List[PublishedIndex] = List(
    ROTTERDAM_BARGES,
    No_6_3PC_USGC_Waterborne,
    UNL_87_USGC_PIPELINE,
    HSFO_180_CST_Singapore,
    PREM_UNL_FOB_ROTTERDAM_BARGES,
    FUEL_FOB_NWE_CARGOES_1,
    NAPHTHA_CIF_NWE_CARGOES,
    GAS_OIL_0_5_SINGAPORE,
    MOGAS_95_UNL_10PPM_NWE_BARGES,
    UNL_92_SINGAPORE_CARGOES,
    GAS_OIL_0_1_FOB_ROTTERDAM_BARGES,
    GAS_OIL_0_1_CIF_NWE_CARGOES,
    PREM_UNL_10PPM_FOB_MED_CARGOES,
    PREM_UNL_EURO_BOB_OXY_NWE_BARGES,
    DATED_BRENT,
    FORTIES_CRUDE_1ST_MONTH,
    URALS_CIF_MED,
    PANAMAX_TC_AVG,
    SUPRAMAX_TC_AVG,
    CAPSIZE_TC_AVG,
    C7_TC_AVG,
    JET_CIF_NWE_CARGOES,
    GAS_OIL_ULSD_10PPM_CIF_NWE_CARGOES,
    GAS_OIL_ULSD_10PPM_FOB_ROTTERDAM_BARGES,
    HSFO_380_CST_Singapore
  ) ::: PLATTS_BRENT.map(_._2).toList

  val eaiQuoteMap = publishedIndexes.toMapWithSomeKeys(_.eaiQuoteID)
  val marketToPublishedIndexMap = publishedIndexes.toMapWithKeys(_.market)
}

case class FuturesFrontPeriodIndex(
  override val market : FuturesMarket,
  rollBeforeDays : Int = 0,
  promptness : Int = 1
 ) extends SingleIndex(
  "%s %s %s price" % (market.name, FuturesFrontPeriodIndex.promptnessString(promptness), market.tenor.toString.toLowerCase),
  market,
  level = Level.Close
 ){

  def observationDays(period : DateRange) : List[Day] = market.observationDays(period)
  def isObservationDay(day : Day) = market.isObservationDay(day)

  def fixingPeriod(observationDay: Day) = {
    val frontMonth = market.frontPeriod(observationDay.addBusinessDays(market.businessCalendar, rollBeforeDays))
    val period = frontMonth match {
      case month:Month => month + (promptness - 1)
      case other => other //This happens in a test but may not be needed when run with real data
    }
    DateRangeFixingPeriod(period)
  }

  def observedOptionPeriod(observationDay: Day) = market.frontOptionPeriod(
    observationDay.addBusinessDays(market.businessCalendar, rollBeforeDays)
  )

  def commodity = market.commodity

  def frontFuturesMonthToSwapMonth(frontFuturesMonth : Month) : Month = {
    market.lastTradingDay(frontFuturesMonth).containingMonth
  }

  override def reportDisplayName = "%s 1st %s" % (market.name, market.tenor.toString.toLowerCase)
}

object FuturesFrontPeriodIndex{
  val WTI10 = new FuturesFrontPeriodIndex(Market.NYMEX_WTI, 0, 1)
  val WTI20 = new FuturesFrontPeriodIndex(Market.NYMEX_WTI, 0, 2)
  val ICEWTI10 = new FuturesFrontPeriodIndex(Market.ICE_WTI, 0, 1)

  val BRT11 = new FuturesFrontPeriodIndex(Market.ICE_BRENT, 1, 1)
  val GO11  = new FuturesFrontPeriodIndex(Market.ICE_GAS_OIL, 1, 1)
  val HO10  = new FuturesFrontPeriodIndex(Market.NYMEX_HEATING, 0, 1)
  val RBOB10  = new FuturesFrontPeriodIndex(Market.NYMEX_GASOLINE, 0, 1)
  val COMEX_HG_COPPER_1ST_MONTH  = new FuturesFrontPeriodIndex(Market.COMEX_HIGH_GRADE_COPPER, 0, 1)
  val COMEX_HG_COPPER_2ND_MONTH  = new FuturesFrontPeriodIndex(Market.COMEX_HIGH_GRADE_COPPER, 0, 2)
  val NYMGO11 = new FuturesFrontPeriodIndex(Market.ICE_GAS_OIL, 1, 1) {
    override def lotSize = Some(1000.0)
  }

  val PLDUB10 = new FuturesFrontPeriodIndex(Market.PLATTS_DUBAI, 1, 0)

  val PLATTS_BRENT_1ST_MONTH = new FuturesFrontPeriodIndex(Market.PLATTS_BRENT, 0, 0)
  val PLATTS_BRENT_2ND_MONTH = new FuturesFrontPeriodIndex(Market.PLATTS_BRENT, 1, 0)
  val PLATTS_BRENT_3RD_MONTH = new FuturesFrontPeriodIndex(Market.PLATTS_BRENT, 2, 0)

  /*
    The ICE Brent one-minute marker. A weighted average of all trades over a specified minute
    during the day. We have no Lim Symbol for this that I can find, but only american options
    (at least in book 173), so a lack of fixings shouldn't be an issue. The forward curve should
    be the same as that for the standard brent futures index.
   */
  val BRT1M11 = new FuturesFrontPeriodIndex(Market.ICE_BRENT, 1, 1)

  val eaiQuoteMap: Map[Int, FuturesFrontPeriodIndex] = Map(
  // these first two are a bit strange, they are the ids for the futures market but some swaps are booked against them.
  // the swaps act like they are booked against the futures front period for these markets
    1 -> BRT11,
    2 -> WTI10,

    7 -> WTI10,
    15 -> HO10,
    28 -> BRT11,
    317 -> PLATTS_BRENT_1ST_MONTH,
    1091 -> ICEWTI10,
    1215 -> PLATTS_BRENT_2ND_MONTH,
    1336 -> PLATTS_BRENT_3RD_MONTH,
    29 -> HO10,
    58 -> GO11,
    933 -> RBOB10,
    1281 -> BRT1M11,
    1431 -> NYMGO11,
    6 -> PLDUB10
    )
  lazy val knownFrontFuturesIndices: List[FuturesFrontPeriodIndex] = List(
    WTI10,
    WTI20,
    ICEWTI10,
    BRT11,
    GO11,
    HO10,
    RBOB10,
    NYMGO11,
    BRT1M11,
    COMEX_HG_COPPER_1ST_MONTH,
    COMEX_HG_COPPER_2ND_MONTH
    )
  lazy val unknownFrontFuturesIndices: List[FuturesFrontPeriodIndex] = {
    val knownMarkets = knownFrontFuturesIndices.map(_.market)
    Market.futuresMarkets.filterNot(knownMarkets.contains).map(m=>FuturesFrontPeriodIndex(m))
  }

  lazy val futuresMarketToIndexMap = Map(
    Market.ICE_BRENT -> BRT11,
    Market.ICE_GAS_OIL -> GO11, // TODO - should this be NYMGO11
    Market.NYMEX_GASOLINE -> RBOB10,
    Market.NYMEX_HEATING -> HO10,
    Market.NYMEX_WTI -> WTI10,
    Market.ICE_WTI -> ICEWTI10
  )

  def promptnessString(promptness: Int): String = {
    if(promptness <= 1)
      "front"
    else if (promptness == 2)
      "second"
    else
      throw new Exception("Unrecognised promptness: " + promptness)
  }
}


abstract class MultiIndex(override val name: CaseInsensitive) extends Index(name) {
  /**
   * To avoid blank rows cash needs to be associated with a risk market and period. 
   * Doesn't really amtter which we use - just making sure the choice is consistent
   */
  lazy val arbitraryIndexToAssignCashTo = indexes.toList.sortWith(_.toString < _.toString).head

  def averagePrice(env : Environment, averagingPeriod: DateRange, rule: SwapPricingRule, priceUOM: UOM): Quantity

  def markets = indexes.flatMap(_.markets).toList

  def tenor = {
    val tenors:Set[TenorType] = indexes.flatMap(_.tenor)
    if (tenors.size == 1) {
      Some(tenors.head)
    } else {
      None
    }
  }

  def commodity = {
    val commodities = indexes.map(_.commodity)
    if (commodities.size == 1)
      commodities.head
    else
      throw new Exception("Not exactly one commodity")
  }

  def convert(value: Quantity, uom: UOM): Option[Quantity] = {
    val conversions = indexes.map(_.convert(value, uom))
    if(conversions.size != 1)
      None
    else
      conversions.head
  }

  protected def checkedConvert(index: Index, price: Quantity, priceUOM: UOM): Quantity = index.convert(price, priceUOM) match {
    case Some(p) => p
    case None => throw new Exception(this + ": Couldn't convert from " + price.uom + " to " + priceUOM + " with " + index)
  }

  def possiblePricingRules = List(CommonPricingRule, NonCommonPricingRule)
}

object Index {

  val indicesToImportFixingsForFromEAI : List[SingleIndex] =
    FuturesFrontPeriodIndex.knownFrontFuturesIndices :::
    FuturesFrontPeriodIndex.unknownFrontFuturesIndices :::
    PublishedIndex.publishedIndexes

  val namedIndexes : List[Index] =
    FuturesFrontPeriodIndex.knownFrontFuturesIndices :::
    FuturesFrontPeriodIndex.unknownFrontFuturesIndices :::
    PublishedIndex.publishedIndexes :::
    FuturesSpreadIndex.spreadIndexes :::
    FormulaIndexList.formulaIndexes :::
    BrentCFDSpreadIndex.named.values.toList

  def fromNameOption(name : String) = namedIndexes.find(_.name == name)

  def fromName(name : String) = namedIndexes.find(_.name == name) match {
    case Some(index) => index
    case None => throw new UnknownIndexException("No index with name " + name + " in " + namedIndexes)
  }
  def singleIndexFromName(name: String) = fromName(name) match {
    case si: SingleIndex => si
    case other => throw new Exception(other + " is not of type TrinityIndex")
  }

  lazy val eaiQuoteMap : Map[Int, Index]=
    FuturesFrontPeriodIndex.eaiQuoteMap ++
    PublishedIndex.eaiQuoteMap ++
    FormulaIndexList.eaiQuoteMap

  def indexFromEAIQuoteID(id: Int): Index = eaiQuoteMap.get(id) match {
    case Some(i:FormulaIndex) => i.verify
    case Some(i) => i
    case None => {
      throw new UnknownIndexException(id + " is not a known Index eaiQuoteID", Some(id))
    }
  }

  def indexOptionFromEAIQuoteID(id: Int) = eaiQuoteMap.get(id)

  def unapply(eaiQuoteID: Int) = indexOptionFromEAIQuoteID(eaiQuoteID)

  def singleIndexFromEAIQuoteID(id: Int): SingleIndex = eaiQuoteMap.get(id) match {
    case Some(i:SingleIndex) => i
    case Some(i) => throw new UnknownIndexException(id + " is not a SingleIndex: " + i, Some(id))
    case None => throw new UnknownIndexException(id + " is not a known Index eaiQuoteID", Some(id))
  }
}