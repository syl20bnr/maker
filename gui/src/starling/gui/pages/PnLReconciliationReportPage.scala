package starling.gui.pages

import starling.daterange.Day
import starling.gui.api._
import starling.gui.{PageContext, PageBuildingContext}
import starling.pivot.{SomeSelection, Selection, Field}
import starling.rmi.StarlingServer

case class PnLReconciliationReportPage(tradeSelectionWithTimestamp: TradeSelectionWithTimestamp, curveIdentifier: CurveIdentifierLabel,
                                       expiryDay: Day, pivotPageState: PivotPageState) extends AbstractPivotPage(pivotPageState) {
  def text = "PnL Trade Reconciliation"
  override def layoutType = Some("PnLTradeReconciliation")

  def selfPage(pivotPageState: PivotPageState) = copy(pivotPageState = pivotPageState)

  def dataRequest(pageBuildingContext: PageBuildingContext) = {
    pageBuildingContext.cachingStarlingServer.pnlReconciliation(tradeSelectionWithTimestamp, curveIdentifier, expiryDay, pivotPageState.pivotFieldParams)
  }

  override def finalDrillDownPage(fields: Seq[(Field, Selection)], pageContext: PageContext, ctrlDown: Boolean) = {
    val selection = fields.find(f => f._1.name == "Trade ID")
    val tradeID = selection match {
      case Some((field, selection)) => {
        selection match {
          case SomeSelection(values) if (values.size == 1) => Some(values.toList(0).asInstanceOf[TradeIDLabel])
          case _ => None
        }
      }
      case None => None
    }
    tradeID match {
      case Some(trID) => {
        pageContext.createAndGoTo(
          (starlingServer: StarlingServer) => {
            SingleTradePage(trID, tradeSelectionWithTimestamp.desk, TradeExpiryDay(expiryDay), tradeSelectionWithTimestamp.intradaySubgroupAndTimestamp.map(_._1))
          }, newTab = ctrlDown)
      }
      case None => None
    }
  }
}