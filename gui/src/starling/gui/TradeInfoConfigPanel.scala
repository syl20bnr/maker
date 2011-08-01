package starling.gui

import api.{TradePageParameters, TradeExpiryDay, ReportParameters, TradeSelection}
import pages.{PivotPageState, TradeSelectionPage, ConfigPanel}
import starling.pivot.view.swing.MigPanel
import starling.gui.GuiUtils._
import swing.{Button, Label}
import swing.event.ButtonClicked
import starling.pivot._

class TradeInfoConfigPanel(context:PageContext, rp:ReportParameters) extends MigPanel() with ConfigPanel{
  val tradeSelection = rp.tradeSelectionWithTimestamp.asTradeSelection
  val displayName = "Trade Info"
  val c = new MigPanel() {
    border = RoundedBorder(colour = PivotTableBackgroundColour)

    val boldFont = new Label().font.deriveFont(java.awt.Font.BOLD)
    val desk = tradeSelection.desk match {
      case None => "Not Used"
      case Some(ts) => ts.name
    }
    val complex = "Complex (View Trade Selection)"
    val excelTrades = tradeSelection.intradaySubgroup match {
      case None => "Not Used"
      case Some(i) => {
        if (i.subgroups.size == 1) {
          i.subgroups.head
        } else if (i.subgroups.isEmpty) {
          "None"
        } else {
          complex
        }
      }
    }

    val strategyInSelection = tradeSelection.tradePredicate.selection.flatten.filter{case (f,_) => f == Field("Strategy")}
    val strategyText = if (strategyInSelection.nonEmpty) {
      complex
    } else {
      val strategyInFilters = tradeSelection.tradePredicate.filter.filter{case (f,_) => f == Field("Strategy")}
      if (strategyInFilters.size == 1) {
        strategyInFilters.head._2 match {
          case AllSelection => "All Strategies"
          case SomeSelection(values) if values.isEmpty => "None"
          case SomeSelection(values) if values.size == 1 => values.head.toString
          case _ => complex
        }
      } else if (strategyInFilters.isEmpty) {
        "No Strategy filter or selection"
      } else {
        complex
      }
    }

    val fullDescriptionButton = new Button("View Trade Selection") {
      reactions += {
        case ButtonClicked(b) => {
          val allFilters = (tradeSelection.tradePredicate.filter ::: tradeSelection.tradePredicate.selection.flatten).groupBy(_._1).map{case (k,v) => {
            val selections = v.map(_._2)
            if (selections.exists(s => {
              s match {
                case AllSelection => true
                case _ => false
              }
            })) {
              (k, AllSelection)
            } else {
              val allValues = selections.flatMap(s => {
                s match {
                  case SomeSelection(values) => values
                }
              }).toSet
              (k, SomeSelection(allValues))
            }
          }}.toList
          val filtersToUse = if (allFilters.nonEmpty) {
            allFilters
          } else {
            List((Field("Stragegy"), AllSelection))
          }

          val rowFields = List(Field("Instrument"), Field("Market"))

          val pfs = Some(new PivotFieldsState(filters = filtersToUse, rowFields = rowFields, columns = ColumnTrees(Field("Trade Count"), true)))
          val pfp = PivotFieldParams(true, pfs)
          val pps = PivotPageState(pivotFieldParams = pfp)

          context.goTo(TradeSelectionPage(TradePageParameters(rp.tradeSelectionWithTimestamp.deskAndTimestamp,
            rp.tradeSelectionWithTimestamp.intradaySubgroupAndTimestamp,
            TradeExpiryDay(rp.expiryDay)), pps
          ))
        }
      }
    }

    add(new Label("Desk:"))
    add(new Label(desk){font = boldFont})
    add(fullDescriptionButton, "wrap, spany, ay top")
    add(new Label("Excel:"))
    add(new Label(excelTrades){font = boldFont}, "wrap")
    add(new Label("Strategy:"))
    add(new Label(strategyText){font = boldFont})
  }
  add(c)
}