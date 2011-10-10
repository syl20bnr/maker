package starling.marketdata

import starling.curves.MarketDataSlice
import starling.db.MarketDataReader
import collection.immutable.TreeMap
import starling.utils.ImplicitConversions._
import scalaz.Scalaz._
import starling.utils.sql.PersistAsBlob
import starling.pivot.{Row, Field}
import starling.daterange.{ObservationTimeOfDay, Day, ObservationPoint}

/**
 * The key used to look up market data from the database.
 * Typically it is the market data type + the market/currency
 */
trait MarketDataKey {

  //the type of MarketData returned when using this key
  type marketDataType <: MarketData
  type marketDataDBType

  //the MarketDataType object for this type
  def dataType:MarketDataType

  //a string to identify this key in the database (typically the market or currency)
  def subTypeKey : String

  //represents the MarketData passed in as rows for use in the market data viewer pivot
  //the fields used as keys must be consistent with the fields defined by the associated MarketDataType
  // The fields include those in field values, plus market data fields. E.g. for price data the market
  // data fields are Month, price
  def rows(t : marketDataType, referenceDataLookup: ReferenceDataLookup): Iterable[Row]

  def castRows(marketData:MarketData, referenceDataLookup: ReferenceDataLookup) = rows(cast(marketData), referenceDataLookup)

  //def create(rows:Iterable[Map[Field,Any]]):marketDataType = throw new Exception

  //the field values for this key - everything except the market data values.
  // E.g. for price data this would have commodity, market, Exchange
  // Used as an optimisation. The gui first gets the markets from this function to display in
  // the field chooser - base on the choice 'rows' is called to get the actual market data to display.
  def fieldValues(referenceDataLookup: ReferenceDataLookup = ReferenceDataLookup.Null) :Map[Field,Any]

  def read(slice:MarketDataSlice):marketDataType = cast(slice.read(this))
  def read(observationPoint: ObservationPoint, reader: MarketDataReader): marketDataType =
    cast(reader.read(TimedMarketDataKey(observationPoint, this)))

  private def cast(marketData:MarketData):marketDataType = marketData.asInstanceOf[marketDataType]

  def dataTypeKey = dataType.name

  def unmarshallDB(dbValue: Any): marketDataType = dbValue.asInstanceOf[marketDataType]

  def valueKey(row: Row): MarketDataValueKey = {
    val fields = dataType.keyFields -- fieldValues().keySet

    MarketDataValueKey(-1, row.filterKeys(fields.contains))
  }

  def valueKeys(data: Option[MarketData], referenceDataLookup: ReferenceDataLookup): List[MarketDataValueKey] =
    data.fold(valueKeys(_, referenceDataLookup: ReferenceDataLookup), Nil)

  def valueKeys(data: MarketData, referenceDataLookup: ReferenceDataLookup) =
    castRows(data, referenceDataLookup).map(row => valueKey(row)).toList
}

case class TimedMarketDataKey(observationPoint: ObservationPoint, key: MarketDataKey) {
  def day = observationPoint.day
  def timeOfDay = observationPoint.timeOfDay
  def timeName = observationPoint.timeName

  def dataType = key.dataType
  def fieldValues(referenceDataLookup: ReferenceDataLookup) = key.fieldValues(referenceDataLookup)
  def castRows(marketData: MarketData, referenceDataLookup: ReferenceDataLookup) = key.castRows(marketData, referenceDataLookup)
  def unmarshallDB(dbValue: Any) = key.unmarshallDB(dbValue)

  def asTuple = (observationPoint, key)
  def copyDay(other: Day) = copy(observationPoint.copyDay(other))
}

case class MarketDataValueKey(id: Int, row: Row) {
  lazy val dbMap: Map[String, Any] = Map("valueKey" → row.dbValue)
  def sameValuesAs(that: MarketDataValueKey) = that.copy(id = id) == this
}