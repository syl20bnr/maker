package starling.marketdata

import starling.pivot.{Field, SomeSelection, PivotFieldsState, FieldDetails}
import starling.curves.SpreadStdDevSurfaceDataType
import starling.market.EquityPricesDataType

/**
 * There is one of these for each time of market data. eg. prices, spotfx, forward rates...
 */
trait MarketDataType {
  type dataType <: MarketData

  val name: String = getClass.getName.substring(getClass.getName.lastIndexOf(".") + 1).stripSuffix("DataType$")
  def keyFields:Set[Field]
  def valueFields:Set[Field] // TODO [08 Jun 2011] Shouldn't valueFields be everything other than the keyFields ?
  def createKey(values:Map[Field,Any]):MarketDataKey
  def createValue(values:List[Map[Field,Any]]):dataType
  override val toString = name

  //The fields to show in the market data viewer when pivoting on this type of market data
  //must be consistent with the rows method in the associated MarketDataKey
  val fields:List[FieldDetails]

  //The initial state to use in the market data viewer for this type of market data
  val initialPivotState:PivotFieldsState

  def splitByFieldType[T](map: Map[Field, T]) = map.filterKeys(keyFields) → map.filterKeys(f => !keyFields.contains(f))
}

object MarketDataTypes {
  //This list is indirectly used to populate the drop down list in the market data viewer
  val types = List(
    PriceDataType,
    BradyFXVolSurfaceDataType,
    BradyMetalVolsDataType,
    OilVolSurfaceDataType,
    ForwardRateDataType,
    SpotFXDataType,
    PriceFixingsHistoryDataType,
    SpreadStdDevSurfaceDataType,
    EquityPricesDataType)

  def fromName(name: String) = types.find(_.name == name).getOrElse(
    throw new Exception("No market data type found for name: " + name + ", available: " + types.map(_.name).mkString(", ")))
}

