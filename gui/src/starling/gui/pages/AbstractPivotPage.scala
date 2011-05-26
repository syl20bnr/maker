package starling.gui.pages

import scala.swing._
import event.ButtonClicked
import starling.pivot.model.{CollapsedState, AxisCell}
import starling.gui._
import api.UserSettingUpdated
import starling.pivot.view.swing._
import starling.pivot._
import controller.{PivotTable, PivotTableConverter}
import java.awt.Dimension
import starling.rmi.{StarlingServer, PivotData}
import collection.mutable.HashSet
import starling.utils.ImplicitConversions._

/**
 * An abstract page which holds a pivot table
 * The subclasses define:
 *  -how to get the PivotTableDataSource
 *  -the custom component
 *  -the title
 *  -what page to goto on drilldown (after the groups have been exausted)
 *
 */

abstract class AbstractPivotPage(pivotPageState:PivotPageState) extends Page {
  val icon = StarlingIcons.im("/icons/stock_chart-reorganize.png")
  def dataRequest(pageBuildingContext:PageBuildingContext):PivotData
  def save(starlingServer:StarlingServer, edits:Set[PivotEdit]):Boolean = throw new Exception("No implementation of save for this page")
  def selfPage(pivotPageState:PivotPageState):Page
  def layoutType:Option[String] = None
  def subClassesPageData(pageBuildingContext:PageBuildingContext):Option[PageData] = None
  def finalDrillDownPage(fields:Seq[(Field,Selection)], pageContext:PageContext, ctrlDown:Boolean):Unit = ()
  def toolbarButtons(pageContext: PageContext, data:PageData):List[Button] = List()
  def configPanel(pageContext:PageContext, data:PageData):Option[ConfigPanels] = None
  def build(reader: PageBuildingContext) = PivotTablePageData(dataRequest(reader), subClassesPageData(reader), layoutType)
  def createComponent(pageContext:PageContext, data:PageData, browserSize:Dimension) : PageComponent = {
    PivotComponent(text, pageContext, toolbarButtons(pageContext, data), configPanel(pageContext, data), finalDrillDownPage, selfPage,
      data, pivotPageState, save, browserSize, false)
  }
}

trait Revertable {
  def revert()
}

trait ConfigPanel extends Component with Revertable {
  def displayName:String
  def revert() = this match {
    case container: Container => container.contents.filterCast[Revertable].foreach(_.revert)
    case _ => throw new Exception("No implemented")
  }
}

case class ConfigPanels(configPanels:List[ConfigPanel], extraComponent:Component, extraComponentAction:Action) extends Revertable {
  def revert() = configPanels.foreach(_.revert)
}

case class PivotPageState(showChart:Boolean=false, pivotFieldParams:PivotFieldParams=PivotFieldParams(true, None),
                          otherLayoutInfo:OtherLayoutInfo = OtherLayoutInfo(totals = Totals.Null)) {
  def copyPivotFieldsState(pivotFieldsState : PivotFieldsState) =
    copy(pivotFieldParams = pivotFieldParams.copy(pivotFieldState = Some(pivotFieldsState)))

  def copyLayout(pivotLayout: PivotLayout) =
    copy(pivotFieldParams = pivotFieldParams.copy(pivotFieldState = Some(pivotLayout.pivotFieldState)),
         otherLayoutInfo = pivotLayout.otherLayoutInfo)

  def copyTotals(totalsF : Totals => Totals) = copy(otherLayoutInfo = otherLayoutInfo.copy(totals = totalsF(otherLayoutInfo.totals)))

  def flipCalculate =
    copy(pivotFieldParams = pivotFieldParams.copy(calculate = !pivotFieldParams.calculate))
}
object PivotPageState {
  def default(pivotFieldsState:PivotFieldsState) = {
    PivotPageState(pivotFieldParams = PivotFieldParams(true, Some(pivotFieldsState)))
  }
}
case class PivotTablePageData(pivotData:PivotData,subClassesPageData:Option[PageData], layoutType:Option[String]) extends PageData

object PivotComponent {
  def apply(text:String,
        pageContext:PageContext,
        toolbarButtons:List[Button],
        configPanel:Option[ConfigPanels],
        finalDrillDown:(Seq[(Field,Selection)],PageContext,Boolean)=>Unit,
        selfPage:(PivotPageState=>Page),
        pageData:PageData,
        pivotPageState:PivotPageState,
        save:(StarlingServer, Set[PivotEdit]) => Boolean,
        browserSize:Dimension,
        embedded:Boolean = true):PivotComponent = {

    val data = pageData.asInstanceOf[PivotTablePageData]

    if (pivotPageState.showChart) {
      PivotTablePageGraphComponent(data.pivotData.pivotTable)
    } else {
      new PivotTablePageComponent(text, pageContext, toolbarButtons, configPanel, finalDrillDown, selfPage, data,
        pivotPageState, save, browserSize, embedded)
    }
  }
}

abstract class PivotComponent extends MigPanel("insets 0", "[fill,grow]", "[fill,grow]") with PageComponent {
  def getSelection : (List[(Field, Selection)], List[scala.List[(Field, Selection)]])
}

case class PivotTablePageGraphComponent(table:PivotTable) extends PivotComponent {
  val pivotGrid = new PivotTableConverter(OtherLayoutInfo(), table).createGrid(addExtraColumnRow = false)
  val pivotChart = new PivotChartView(pivotGrid)
  add(pivotChart, "push, grow")

  def getSelection = (List(), List(List()))
}

class PivotTablePageComponent(
        text:String,
        pageContext:PageContext,
        toolbarButtons:List[Button],
        configPanel:Option[ConfigPanels],
        finalDrillDown:(Seq[(Field,Selection)],PageContext,Boolean)=>Unit,
        selfPage:(PivotPageState=>Page),
        pivotTablePageData:PivotTablePageData,
        pivotPageState:PivotPageState,
        save:(StarlingServer, Set[PivotEdit]) => Boolean,
        browserSize:Dimension,
        embedded:Boolean) extends PivotComponent {

  val data = pivotTablePageData.pivotData
  val extraFormatInfo = pageContext.getSetting(StandardUserSettingKeys.ExtraFormattingInfo, PivotFormatter.DefaultExtraFormatInfo)
  val pivotTableComponent = PivotTableView.createWithLayer(data, pivotPageState.otherLayoutInfo, browserSize, configPanel, extraFormatInfo, embedded)
  val pivotComp = pivotTableComponent.getScalaComponent

  val currentFieldState = data.pivotFieldsState
  val drillDownGroups = data.drillDownGroups
  val user = pageContext.localCache.currentUser

  val toggleCalculateButton = new ToggleToolBarButton {
    selected = !pivotPageState.pivotFieldParams.calculate
    tooltip = "Enable or disable calculation on the fly"
    icon = StarlingIcons.Calculate
    // If the button is deselected it means we need to calculate with the new layout.
    reactions += {
      case ButtonClicked(_) => {
        pageContext.goTo(selfPage(pivotPageState.flipCalculate))
      }
    }
  }

  val toolBar = new MigPanel("insets 1 1 1 1, gap 1") {
    val lockScreenButton = new ToggleToolBarButton {
      icon = StarlingIcons.Lock
      tooltip = "Freeze or unfreeze the column headers"
      selected = pivotPageState.otherLayoutInfo.frozen
      reactions += {case ButtonClicked(b) => {
        pageContext.goTo(selfPage(pivotPageState.copy(
          otherLayoutInfo = pivotPageState.otherLayoutInfo.copy(frozen = !pivotPageState.otherLayoutInfo.frozen))))
      }}
    }

    val rotateButton = new ToolBarButton {
      icon = StarlingIcons.Rotate
      tooltip = "Switch the row and column fields"
      reactions += {
        case ButtonClicked(_) => { pageContext.goTo(selfPage(pivotPageState.copyPivotFieldsState(data.pivotFieldsState.rotate))) }
      }
      enabled = data.pivotFieldsState.hasRowOrColumnFields
    }

    val bottomTotalsButton = new ToggleToolBarButton {
      icon = StarlingIcons.RowGrandTotals
      tooltip = "Display or hide the row grand totals"
      reactions += { case ButtonClicked(_) => {pageContext.goTo(selfPage(pivotPageState.copyTotals(_.toggleRowGrandTotal)))} }
    }
    val rightTotalsButton = new ToggleToolBarButton {
      icon = StarlingIcons.ColumnTotals
      tooltip = "Display or hide the column grand totals"
      reactions += { case ButtonClicked(_) => {pageContext.goTo(selfPage(pivotPageState.copyTotals(_.toggleColumnGrandTotal)))} }
    }
    val rowSubTotalsButton = new ToggleToolBarButton {
      icon = StarlingIcons.RowSubTotals
      tooltip = "Display or hide the row sub totals"
      reactions += { case ButtonClicked(_) => {
        val newOtherLayoutInfo = pivotPageState.otherLayoutInfo.copy(rowSubTotalsDisabled = List())
        val newPivotPageState = pivotPageState.copy(otherLayoutInfo = newOtherLayoutInfo)
        pageContext.goTo(selfPage(newPivotPageState.copyTotals(_.toggleRowSubTotals)))
      } }
    }
    val columnSubTotalsButton = new ToggleToolBarButton {
      icon = StarlingIcons.ColumnSubTotals
      tooltip = "Display or hide the column sub totals"
      reactions += { case ButtonClicked(_) => {
        val newOtherLayoutInfo = pivotPageState.otherLayoutInfo.copy(columnSubTotalsDisabled = List())
        val newPivotPageState = pivotPageState.copy(otherLayoutInfo = newOtherLayoutInfo)
        pageContext.goTo(selfPage(newPivotPageState.copyTotals(_.toggleColumnSubTotals)))
      } }
    }
    val chartButton = new ToolBarButton {
      icon = StarlingIcons.Chart
      tooltip = "Display a chart of table data"
      reactions += { case ButtonClicked(_) => { pageContext.goTo(selfPage(pivotPageState.copy(showChart = true))) } }
    }
    val clearCacheButton = new ToolBarButton {
      icon = StarlingIcons.icon("/icons/16x16_edit-clear.png")
      tooltip = "Clear local and server side caches - this could cause major problems and is only available in dev"
      reactions += {
        case ButtonClicked(b) => {
          // Clear the local cache.
          pageContext.clearCache
          pageContext.submit(ClearServerSideCache)
        }
      }
    }
    val copyButton = new ToolBarButton {
      icon = StarlingIcons.Copy
      tooltip = "Copy the selected cells to the clipboard"
      reactions += {case ButtonClicked(b) => pivotComp.copyReportToClip}
    }
    val saveEditsButton = new ToolBarButton {
      icon = StarlingIcons.icon("/icons/16x16_save.png")
      tooltip = "Save the edits made to the data"
      enabled = false

      def saveEdits {
        val edits = pivotComp.edits
        println("Saving edits: " + edits)
        pageContext.submit(new SubmitRequest[Boolean] {
          def submit(server:StarlingServer) = {
            save(server, edits)
          }
        }, awaitRefresh = (a:Boolean) => a)
      }
      reactions += {case ButtonClicked(b) => saveEdits}
    }
    val resetEditsButton = new ToolBarButton {
      icon = StarlingIcons.icon("/icons/16x16_undo.png")
      tooltip = "Reset all unsaved edits"
      enabled = false
      reactions += {case ButtonClicked(b) => {
        pivotComp.resetEdits
        saveEditsButton.enabled = false
        enabled = false
      }}
    }

    val layoutComponent = pivotTablePageData.layoutType match {
      case None => None
      case Some(layoutType) => Some(new SaveLayoutPanel(
        pageContext, pivotTablePageData, pivotPageState, layoutType, pps => selfPage(pps), pivotComp.giveDefaultFocus))
    }
    layoutComponent match {
      case None =>
      case Some(lc) => {
        add(lc)
        addSeparator
      }
    }

    add(lockScreenButton)
    add(rotateButton)
    addSeparator
    add(bottomTotalsButton)
    add(rightTotalsButton)
    add(rowSubTotalsButton)
    add(columnSubTotalsButton)
    addSeparator
    add(chartButton)
    addSeparator
    add(toggleCalculateButton)
    addSeparator
    if (data.pivotTable.editableInfo == None) {
      if (pageContext.localCache.version.production) {
        add(copyButton, "pushx")
      } else {
        add(copyButton)
        addSeparator
        add(clearCacheButton, "pushx")
      }
    } else {
      add(copyButton)
      addSeparator
      if (pageContext.localCache.version.production) {
        add(saveEditsButton)
        add(resetEditsButton, "pushx")
      } else {
        add(saveEditsButton)
        add(resetEditsButton)
        addSeparator
        add(clearCacheButton, "pushx")
      }
    }

    for (button <- toolbarButtons) {
      add(button)
    }

    def addSeparator {
      val separator = new Separator(Orientation.Vertical)
      add(separator, "pushy, growy, gapleft 2, gapright 1")
    }

    private def resetTotals {
      bottomTotalsButton.selected = pivotPageState.otherLayoutInfo.totals.rowGrandTotal
      rightTotalsButton.selected = pivotPageState.otherLayoutInfo.totals.columnGrandTotal
      rowSubTotalsButton.selected = pivotPageState.otherLayoutInfo.totals.rowSubTotals
      columnSubTotalsButton.selected = pivotPageState.otherLayoutInfo.totals.columnSubTotals
    }

    def resetToolbarState {
      layoutComponent.foreach(_.reverse)
      lockScreenButton.selected = pivotPageState.otherLayoutInfo.frozen
      toggleCalculateButton.selected = !pivotPageState.pivotFieldParams.calculate
      resetTotals
    }

    resetTotals
  }
  pivotComp.setCustomToolBar(toolBar, () => toolBar.resetToolbarState)

  reactions += {
    case FieldsChangedEvent(pivotFieldState) => pageContext.goTo(selfPage(pivotPageState.copyPivotFieldsState(pivotFieldState)))
    case TableDoubleClickEvent(filterFields, drillDownFields, ctrlDown) => {

      def getAxis(fields:Seq[(Field,Selection)], drillDownInfo:DrillDownInfo) = {
        val axisToUse = drillDownInfo.fallBack
        drillDownInfo.filteredDrillDown match {
          case None => axisToUse
          case Some(ddInfo) => {
            val dDF = fields.map(_._1)
            val dDFMap = Map() ++ fields
            if (dDF.contains(ddInfo.filterField)) {
              // We are pivoting on something we know more information about. Look at the selection and get the fields for it.
              val selection = dDFMap(ddInfo.filterField)
              selection match {
                case AllSelection => {throw new IllegalStateException("This case hasn't been coded for yet: " + selection)}
                case SomeSelection(values) if values.isEmpty => {throw new IllegalStateException("This case hasn't been coded for yet: " + selection)}
                case SomeSelection(values) => {
                  val fieldsToUse = values.flatMap{
                    _ match {
                      case s:String => {
                        (Map() ++ ddInfo.fieldToFields)(s)
                      }
                      case _ => throw new IllegalStateException("This case hasn't been coded for yet (selection not a string): " + selection)
                    }}.toList
                  axisToUse.copy(dataFields = axisToUse.dataFields.intersect(fieldsToUse))
                }
                case _ => throw new IllegalStateException("This case hasn't been coded for yet: " + selection)
              }
            } else {
              axisToUse
            }
          }
        }
      }

      val allFieldsUsed = currentFieldState.allFieldsUsed
      val possibleGroups = drillDownGroups.map(group=>{
        // Generate the group to remove all the fields from.
        val axisToUse = getAxis(filterFields, group).removeAll(allFieldsUsed)
        getAxis(drillDownFields, DrillDownInfo(axisToUse, group.filteredDrillDown))
      }).filterNot(_.isEmpty)

      if (!possibleGroups.isEmpty) {
        val newFieldState = currentFieldState.withFiltersAndRowFields(drillDownFields, possibleGroups.head)
        val newPPS = pivotPageState.copy(pivotFieldParams = pivotPageState.pivotFieldParams.copy(pivotFieldState = Some(newFieldState)))
        pageContext.goTo(selfPage(newPPS), ctrlDown)
      } else {
        finalDrillDown(filterFields ++ drillDownFields, pageContext, ctrlDown)
      }
    }
    case FullScreenSelectedEvent(data) => pageContext.goTo(new FullScreenReportPage(text, data))
    case ShowErrorsEvent(errors) => {pageContext.goTo(new ReportCellErrorsPage(errors.toList))}
    case PivotEditEvent(hasEdits, hasErrors, hasAdded) => {
      if (hasErrors) {
        toolBar.saveEditsButton.enabled = false
        toolBar.resetEditsButton.enabled = true
      } else {
        val en = (hasEdits | hasAdded)
        toolBar.saveEditsButton.enabled =  en
        toolBar.resetEditsButton.enabled = en
      }
    }
    case SavePivotEdits if toolBar.saveEditsButton.enabled => toolBar.saveEditsButton.saveEdits
    case FieldPanelEvent(collapse) => {
      val newOtherLayoutInfo = pivotPageState.otherLayoutInfo.copy(fieldPanelCollapsed = collapse)
      pageContext.goTo(selfPage(pivotPageState.copy(otherLayoutInfo = newOtherLayoutInfo)))
    }
    case CollapsedStateUpdated(rowOption, columnOption) => {
      val newOtherLayoutInfo = rowOption match {
        case Some(r) => pivotPageState.otherLayoutInfo.copy(rowCollapsedState = r)
        case None => pivotPageState.otherLayoutInfo.copy(columnCollapsedState = columnOption.get)
      }
      pageContext.goTo(selfPage(pivotPageState.copy(otherLayoutInfo = newOtherLayoutInfo)))
    }
    case SubTotalToggled(field, location) => {
      val newOtherLayoutInfo = location match {
        case FieldChooserType.Rows => {
          val subTotalsDisabled = pivotPageState.otherLayoutInfo.rowSubTotalsDisabled
          val newSubTotalsDisabled = if (subTotalsDisabled.contains(field)) {
            subTotalsDisabled.filterNot(_ == field)
          } else {
            field :: subTotalsDisabled
          }
          pivotPageState.otherLayoutInfo.copy(rowSubTotalsDisabled = newSubTotalsDisabled)
        }
        case FieldChooserType.Columns => {
          val subTotalsDisabled = pivotPageState.otherLayoutInfo.columnSubTotalsDisabled
          val newSubTotalsDisabled = if (subTotalsDisabled.contains(field)) {
            subTotalsDisabled.filterNot(_ == field)
          } else {
            field :: subTotalsDisabled
          }
          pivotPageState.otherLayoutInfo.copy(columnSubTotalsDisabled = newSubTotalsDisabled)
        }
      }
      pageContext.goTo(selfPage(pivotPageState.copy(otherLayoutInfo = newOtherLayoutInfo)))
    }
    case UserSettingUpdated(StandardUserSettingKeys.ExtraFormattingInfo) => {
      val newExtraFormatInfo = pageContext.getSetting(StandardUserSettingKeys.ExtraFormattingInfo)
      pivotComp.extraFormatInfoUpdated(newExtraFormatInfo)
    }
  }
  listenTo(pivotComp, pageContext.remotePublisher)

  add(pivotTableComponent, "push, grow")

  override def restoreToCorrectViewForBack  {pivotComp.reverse()}
  override def resetDynamicState  {pivotComp.resetDynamicState()}
  def selection = {pivotComp.selection}

  override def setState(state:Option[ComponentState]) {
    state match {
      case Some(AbstractPivotComponentState(filterText, colScrollPos, mainScrollPos, rsScrollPos, selectedCells, configPanelState)) => {
        pivotComp.filterText = filterText
        pivotComp.setColScrollPos(colScrollPos)
        pivotComp.setMainScrollPos(mainScrollPos)
        pivotComp.setRSScrollPos(rsScrollPos)
        pivotComp.setSelectedCells(selectedCells)
        pivotComp.configPanelState = configPanelState
      }
      case _ =>
    }
  }

  override def getState = {
    Some(AbstractPivotComponentState(pivotComp.filterText, pivotComp.getColScrollPos,
      pivotComp.getMainScrollPos, pivotComp.getRSScrollPos, pivotComp.getSelectedCells, pivotComp.configPanelState))
  }

  override def getTypeState = {
    Some(AbstractPivotComponentTypeState(pivotComp.filterText, pivotComp.getRSScrollPos, pivotComp.configPanelState))
  }

  override def setTypeState(typeState:Option[ComponentTypeState]) = {
    typeState match {
      case Some(AbstractPivotComponentTypeState(filterText, rsScrollPos, configPanelState)) => {
        pivotComp.filterText = filterText
        pivotComp.setRSScrollPos(rsScrollPos)
        pivotComp.configPanelState = configPanelState
      }
      case _ =>
    }
  }
  override def pageResized(newSize:Dimension) = pivotComp.pageResized(newSize)
  override def getOldPageData = Some(pivotComp.getOldPageData)
  override def getRefreshState = Some(pivotComp.getRefreshState)
  override def setOldPageDataOnRefresh(pageData:Option[OldPageData], refreshState:Option[ComponentRefreshState], componentState:Option[ComponentState]) = {
    pageData match {
      case None =>
      case Some(AbstractPivotComponentOldPageData(oldRow,oldCol,oldMain)) => {
        val newOldPageData = pivotComp.getOldPageData

        val canUpdate = {
          def ok[T](oldData:Array[Array[T]], newData:Array[Array[T]]) = {
            (oldData.nonEmpty && (oldData.length == newData.length)) &&
                    (oldData(0).nonEmpty && (oldData(0).length == newData(0).length))
          }
          ok(oldRow,newOldPageData.rowData) && ok(oldCol,newOldPageData.colData) && ok(oldMain,newOldPageData.mainData)
        }

        if (canUpdate) {
          refreshState match {
            case None =>
            case Some(p:PivotTableViewRefreshState) => pivotComp.setRefreshState(p)
            case _ =>
          }

          def getDifferences(oldData:Array[Array[TableCell]], newData:Array[Array[TableCell]]) = {
            val indices = new HashSet[(Int,Int)]
            for (j <- 0 until oldData.length) {
              val oldRow = oldData(j)
              val currentRow = newData(j)
              for (i <- 0 until oldRow.length) {
                if ((oldRow(i).value != currentRow(i).value) && (currentRow(i).totalState == NotTotal)) {
                  indices += ((i,j))
                }
              }
            }
            indices.toSet
          }
          def headerDifferences(oldData:Array[Array[AxisCell]], newData:Array[Array[AxisCell]]) = {
            val indices = new HashSet[(Int,Int)]
            for (j <- 0 until oldData.length) {
              val oldRow = oldData(j)
              val currentRow = newData(j)
              for (i <- 0 until oldRow.length) {
                if ((oldRow(i).value.value.value != currentRow(i).value.value.value) && (currentRow(i).totalState == NotTotal)) {
                  indices += ((i,j))
                }
              }
            }
            indices.toSet
          }

          val mainDiff = getDifferences(oldMain,newOldPageData.mainData)
          val rowDiff = headerDifferences(oldRow, newOldPageData.rowData)
          val colDiff = headerDifferences(oldCol, newOldPageData.colData)
          pivotComp.updateRefreshHighlighter(rowDiff, colDiff, mainDiff)
        }
      }
      case _ =>
    }
  }

  def getSelection = selection
}

case object ClearServerSideCache extends SubmitRequest[Unit] {
  def submit(server:StarlingServer) = {server.clearCache}
}

case class SavePivotLayoutRequest(pivotLayout:PivotLayout) extends SubmitRequest[Unit] {
  def submit(server:StarlingServer) {server.saveLayout(pivotLayout)}
}
case class DeletePivotLayoutRequest(layoutName:String) extends SubmitRequest[Unit] {
  def submit(server:StarlingServer) = {
    server.deleteLayout(layoutName)
  }
}

case class ReplacePivotLayoutRequest(layout:PivotLayout) extends SubmitRequest[Unit] {
  def submit(server:StarlingServer) = {
    server.deleteLayout(layout.layoutName)
    server.saveLayout(layout)
  }
}

case class AbstractPivotComponentState(filterText:String,
                                       colScrollPosition:Int,
                                       mainScrollPosition:Point,
                                       reportSpecificScrollPosition:Int,
                                       selectedCells:Either[List[(Int,Int)], (List[(Int,Int)],List[(Int,Int)],List[(Int,Int)])],
                                       configPanelState:Option[NTabbedPaneState]) extends ComponentState
case class AbstractPivotComponentTypeState(filterText:String, reportSpecificScrollPosition:Int,
                                           configPanelState:Option[NTabbedPaneState]) extends ComponentTypeState