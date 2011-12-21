package starling.neptune

import starling.manager.Broadcaster
import java.lang.String
import starling.richdb.{RichInstrumentResultSetRow, RichDB}
import starling.eai.TreeID
import starling.db.RefinedFixationTradeSystem
import starling.utils.ImplicitConversions._
import starling.pivot._
import starling.instrument.TradeAttributes

import starling.pivot.Field._
import starling.instrument.{TradeableType}
import collection.immutable.TreeMap
import starling.gui.api.Desk
import starling.tradeimport.ClosedDesks
import starling.tradestore.{RichTradeStore, TradeStore}
import starling.daterange.Timestamp


case class RefinedFixationTradeAttributes (
  groupCompany : String,
  exchange : String,
  contractNo : String,
  pricingType : String
)
  extends TradeAttributes
{
  def details = Map(
    groupCompany_str -> groupCompany,
    exchange_str -> exchange,
    contractNo_str -> contractNo,
    pricingType_str -> pricingType
  )
}


object RefinedFixationTradeStore {
}

class RefinedFixationTradeStore(db: RichDB, broadcaster:Broadcaster, closedDesks: ClosedDesks)
  extends RichTradeStore(db, RefinedFixationTradeSystem, closedDesks)
{

  def deskOption = Some(Desk.Titan)
  override val tradeAttributeFieldDetails = List(groupCompany_str, exchange_str, contractNo_str, pricingType_str).map(n=>FieldDetails(n))
  private val List(groupCompany_col, exchange_col, contractNo_col, pricingType_col) = tradeAttributeFieldsAsSQLColumnNames

  def createTradeAttributes(row: RichInstrumentResultSetRow) = {

    RefinedFixationTradeAttributes(
      row.getString(groupCompany_col),
      row.getString(exchange_col),
      row.getString(contractNo_col),
      row.getString(pricingType_col)
    )

  }

  def pivotDrillDownGroups() = List()

  def pivotInitialState(tradeableTypes:Set[TradeableType[_]]) = {
    val pfs = PivotFieldsState(
        List(Field("Trade Count"))
      )
    DefaultPivotState(pfs)
  }

  protected def closesFrom(from:Timestamp, to:Timestamp) = {
    (to :: allTimestamps).distinct.filter(t => t > from && t <= to).sortWith(_ < _)
  }

  val tableName = "RefinedFixation"
}
