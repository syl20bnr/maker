package starling.gui.pages

import starling.pivot.view.swing.MigPanel
import starling.gui._
import api._
import starling.pivot._
import starling.gui.GuiUtils._
import java.awt.Dimension
import swing.event.{Event, ButtonClicked, SelectionChanged}
import swing._
import javax.swing.DefaultComboBoxModel
import utils.RichReactor
import RichReactor._
import starling.rmi.StarlingServer
import starling.daterange.Day

/**
 * For viewing (and uploading?) market data.
 */
case class MarketDataPage(
        marketDataIdentifier:MarketDataPageIdentifier,
        pageState : MarketDataPageState
        ) extends AbstractPivotPage(pageState.pivotPageState) {
  def this(mdi:MarketDataIdentifier, pageState : MarketDataPageState) = this(StandardMarketDataPageIdentifier(mdi), pageState)

  def text = "Market Data Viewer"
  override def layoutType = Some("MarketData")
  override val icon = StarlingIcons.im("/icons/16x16_market_data.png")

  def selfPage(pivotPageState: PivotPageState) = new MarketDataPage(marketDataIdentifier, MarketDataPageState(pivotPageState, pageState.marketDataType))

  def dataRequest(pageBuildingContext: PageBuildingContext) = {
    pageBuildingContext.starlingServer.readAllMarketData(marketDataIdentifier, pageState.marketDataType, pageState.pivotPageState.pivotFieldParams)
  }

  override def save(starlingServer:StarlingServer, edits:Set[PivotEdit]) = {
    starlingServer.saveMarketData(marketDataIdentifier, pageState.marketDataType, edits)
  }

  override def refreshFunctions = marketDataIdentifier match {
    case StandardMarketDataPageIdentifier(c@MarketDataIdentifier(MarketDataSelection(pricingGroup, name), SpecificMarketDataVersion(_))) => {
      PricingGroupMarketDataUpdate.matching(pricingGroup).andThen(update => copy(marketDataIdentifier=StandardMarketDataPageIdentifier(c.copyVersion(update.version)))) ::
      ExcelMarketDataUpdate.matching(name).andThen(update => copy(marketDataIdentifier=StandardMarketDataPageIdentifier(c.copyVersion(update.version)))) ::
      Nil
    }
    case _ => Nil
  }

  //private def copyVersion(version : Int) = copy(marketDataIdentifier = marketDataIdentifier.copyVersion(version))

  override def subClassesPageData(pageBuildingContext:PageBuildingContext) = {
    val avaliableMarketDataTypes = pageBuildingContext.starlingServer.marketDataTypeLabels(marketDataIdentifier)
    val selected = pageState.marketDataType match {
      case Some(mdt) => Some(mdt)
      case None => avaliableMarketDataTypes.headOption
    }
    Some(MarketDataPagePageData(avaliableMarketDataTypes, selected))
  }

  override def createComponent(pageContext: PageContext, data: PageData, browserSize:Dimension) = {
    val marketDataPagePageData = data match {
      case v:PivotTablePageData => v.subClassesPageData match {
        case x:Option[_] => x.get.asInstanceOf[MarketDataPagePageData]
      }
    }
    new MarketDataPageComponent(
      pageContext,
      this,
      PivotComponent(text, pageContext, toolbarButtons(pageContext, data), None, finalDrillDownPage, selfPage, data,
        pageState.pivotPageState, save, browserSize),
      pageState, marketDataPagePageData)
  }
}

case class MarketDataSelectionChanged(selection:MarketDataSelection) extends Event

object MarketDataSelectionComponent {
  def storeMarketDataSelection(pageContext:PageContext, selection:MarketDataSelection) = {
    pageContext.putSetting(StandardUserSettingKeys.InitialMarketDataSelection, selection)
    selection.pricingGroup match {
      case Some(pg) => pageContext.putSetting(StandardUserSettingKeys.PricingGroupDefault, pg)
      case None =>
    }
    selection.excel match {
      case Some(excel) => pageContext.putSetting(StandardUserSettingKeys.ExcelMarketDataDefault, excel)
      case None =>
    }
  }
}
class MarketDataSelectionComponent(pageContext:PageContext, maybeDesk:Option[Desk],
                                   marketDataSelection:MarketDataSelection,
                                   orientation:scala.swing.Orientation.Value=scala.swing.Orientation.Horizontal)
        extends MigPanel("insets 0") with Revertable {


  def revert() = this.suppressingSelf(selection = marketDataSelection)

  private val minWidthForComboBox = 200

  private val pricingGroups = pageContext.localCache.pricingGroups(maybeDesk)
  private val pricingGroupCheckBox = new CheckBox {
    text = "Pricing Group:"
    selected = marketDataSelection.pricingGroup.isDefined
    enabled = !pricingGroups.isEmpty
  }

  private val pricingGroupCombo = if (pricingGroups.isEmpty) {
    new ComboBox(List(PricingGroup(""))) {
      enabled=false
      minimumSize = new Dimension(minWidthForComboBox, minimumSize.height)
    }
  } else {
    new ComboBox(pricingGroups) {
      minimumSize = new Dimension(minWidthForComboBox, minimumSize.height)
      marketDataSelection.pricingGroup match {
        case Some(pg) => selection.item = pg
        case None => {
          pageContext.getSettingOption(StandardUserSettingKeys.PricingGroupDefault) match {
            case Some(pg) => selection.item = pg
            case None =>
          }
        }
      }
      enabled = marketDataSelection.pricingGroup.isDefined
    }
  }

  private val excelCheckBox = new CheckBox {
    text = "Excel Market Data:"
    enabled = !pageContext.localCache.excelDataSets.isEmpty
    selected = marketDataSelection.excel.isDefined && enabled
  }
  private val excelCombo : ComboBox[String] = createExcelCombo(pageContext.localCache.excelDataSets)

  private def createExcelCombo(values:List[String]) = {
    if (pageContext.localCache.excelDataSets.isEmpty) {
      new ComboBox(List("")) {
        enabled = false
        minimumSize = new Dimension(minWidthForComboBox, minimumSize.height)
      }
    } else {
      new ComboBox(values) {
        minimumSize = new Dimension(minWidthForComboBox, minimumSize.height)
        if (marketDataSelection.excel.isDefined) {
          selection.item = marketDataSelection.excel.get
        } else {
          pageContext.getSettingOption(StandardUserSettingKeys.ExcelMarketDataDefault) match {
            case Some(excel) => selection.item = excel
            case None =>
          }
        }
        enabled = marketDataSelection.excel.isDefined
      }
    }
  }

  def selection_= (se:MarketDataSelection) {
    se.pricingGroup match {
      case Some(pg) => {
        pricingGroupCheckBox.selected = true
        pricingGroupCombo.selection.item = pg
        pricingGroupCombo.enabled = true
      }
      case None => {
        pricingGroupCheckBox.selected = false
        pricingGroupCombo.enabled = false
      }
    }
    se.excel match {
      case Some(name) => {
        excelCheckBox.selected = true
        excelCombo.selection.item = name
        excelCombo.enabled = true
      }
      case None => {
        excelCheckBox.selected = false
        excelCombo.enabled = false
      }
    }
  }

  def selection = MarketDataSelection(
    if (pricingGroupCheckBox.selected) Some(pricingGroupCombo.selection.item.asInstanceOf[PricingGroup]) else None,
    if (excelCheckBox.selected) Some(excelCombo.selection.item) else None
  )

  def fireNewSelection {
    val se = selection
    MarketDataSelectionComponent.storeMarketDataSelection(pageContext, se)
    publish(MarketDataSelectionChanged(se))
  }

  listenTo(pageContext.remotePublisher)
  reactions += {
    case ExcelMarketListUpdate(values) => {
      val currentSelection = excelCombo.selection.item
      excelCheckBox.enabled = true
      this.suppressing(excelCombo.selection) {
        excelCombo.peer.setModel(ComboBox.newConstantModel(values))
        if (values.contains(currentSelection)) {
          excelCombo.selection.item = currentSelection
        }
      }
    }
  }

  reactions += {
    case ButtonClicked(`pricingGroupCheckBox`) => { pricingGroupCombo.enabled = pricingGroupCheckBox.selected; fireNewSelection }
    case SelectionChanged(`pricingGroupCombo`) => fireNewSelection
    case ButtonClicked(`excelCheckBox`) => { excelCombo.enabled = excelCheckBox.selected; fireNewSelection }
    case SelectionChanged(`excelCombo`) => fireNewSelection
  }

  listenTo(pricingGroupCheckBox, pricingGroupCombo.selection, excelCheckBox, excelCombo.selection)

  val layoutInfo = orientation match {
    case scala.swing.Orientation.Vertical => "wrap"
    case scala.swing.Orientation.Horizontal => "gapright rel"
  }
  add(pricingGroupCheckBox)
  add(pricingGroupCombo, layoutInfo)
  add(excelCheckBox, excelCombo)
}

case class SnapshotComboValue(maybeSnapshot:Option[SnapshotIDLabel]) {
  override def toString = maybeSnapshot match {
    case Some(ss) => ss.shortString
    case None => "Current"
  }
}
class MarketDataPageComponent(
        pageContext : PageContext,
        thisPage:MarketDataPage,
        pivotComponent : PageComponent,
        pageState : MarketDataPageState,
        pageData:PageData
        ) extends MigPanel("insets n n 0 0", "[" + StandardLeftIndent + "][p][p][grow][p]") with PageComponent {

  //Save the layout as the default for use the next time this market data type is selected
  (thisPage.pageState.marketDataType, thisPage.pageState.pivotPageState.pivotFieldParams.pivotFieldState) match {
    case (Some(mdt), Some(pfs)) => pageContext.putSetting(StandardUserSettingKeys.UserMarketDataTypeLayout,
      pageContext.getSetting(StandardUserSettingKeys.UserMarketDataTypeLayout, Map[MarketDataTypeLabel,PivotFieldsState]()) +
      (mdt -> pfs)
    )
    case _ =>
  }


  val data = pageData match {case v:MarketDataPagePageData => v}

  private val marketDataSelectionComponent = new MarketDataSelectionComponent(pageContext, None, thisPage.marketDataIdentifier.selection)

  private val snapshotsComboBoxModel = new DefaultComboBoxModel
  private val snapshotsComboBox = new ComboBox[SnapshotComboValue](List(SnapshotComboValue(None))) { // Got to pass a list in - not very good but we remove it straight away.
    peer.setModel(snapshotsComboBoxModel)
    snapshotsComboBoxModel.removeAllElements
    
    {val snapshots = pageContext.localCache.snapshots(None).getOrElse(thisPage.marketDataIdentifier.selection, List())
      SnapshotComboValue(None) :: snapshots.map(ss=>SnapshotComboValue(Some(ss))).toList}.foreach(snapshotsComboBoxModel.addElement(_))

    def initialize() {
      selection.item = {
        val mdi = thisPage.marketDataIdentifier.marketDataIdentifier
        mdi.marketDataVersion match {
          case SnapshotMarketDataVersion(ss) => SnapshotComboValue(Some(ss))
          case SpecificMarketDataVersion(version) => SnapshotComboValue(None)
        }
      }
    }
    initialize()
    def value = {
      selection.item match {
        case SnapshotComboValue(Some(ss)) => SnapshotMarketDataVersion(ss)
        case SnapshotComboValue(None) => SpecificMarketDataVersion(pageContext.localCache.latestMarketDataVersion(marketDataSelectionComponent.selection))
      }
    }
  }

  pageContext.remotePublisher.reactions += {
    case MarketDataSnapshot(snapshots) => {
      this.suppressing(snapshotsComboBox.selection) {
        val itemSelected = snapshotsComboBox.selection.item
        snapshotsComboBoxModel.removeAllElements

        {val newSnapshots = snapshots.getOrElse(thisPage.marketDataIdentifier.selection, List())
          SnapshotComboValue(None) :: newSnapshots.map(ss=>SnapshotComboValue(Some(ss))).toList}.foreach(snapshotsComboBoxModel.addElement(_))
        snapshotsComboBox.selection.item = itemSelected
      }
    }
  }

  private val labels = if (data.marketDataTypeLabels.isEmpty) List(MarketDataTypeLabel("")) else data.marketDataTypeLabels
  private val dataTypeCombo = new ComboBox(labels) {
    renderer = ListView.Renderer(_.toString)
    data.selection match {
      case Some(mdt) => selection.item = mdt
      case None =>
    }
    enabled = data.marketDataTypeLabels.nonEmpty
    minimumSize = new Dimension(100, preferredSize.height)
  }

  private val filterDataCheckbox = new CheckBox("Filter Market Data For Report") {
    reactions += {
      case ButtonClicked(b) => { pageContext.goTo(thisPage.copy(marketDataIdentifier = StandardMarketDataPageIdentifier(thisPage.marketDataIdentifier.marketDataIdentifier)))}
    }
  }

  private val importButton = new Button {
    val observationDay = Day.today.previousWeekday
    enabled = true
    tooltip = "Import and snapshot market data for previous weekday"
    icon = StarlingIcons.icon("/icons/14x14_download_data.png")

    reactions += {
      case ButtonClicked(_) => {
        val day = observationDay
        pageContext.submit(SnapshotSubmitRequest(thisPage.marketDataIdentifier.selection, day.asInstanceOf[Day]))
      }
    }
  }

  override def restoreToCorrectViewForBack = {
    this.suppressing(dataTypeCombo.selection, marketDataSelectionComponent, snapshotsComboBox.selection, filterDataCheckbox) {
      pivotComponent.restoreToCorrectViewForBack
      data.selection match {
        case Some(mdt) => dataTypeCombo.selection.item = mdt
        case None =>
      }
      marketDataSelectionComponent.selection = thisPage.marketDataIdentifier.selection
      snapshotsComboBox.initialize()
      filterDataCheckbox.selected = thisPage.marketDataIdentifier.filteredMarketData
    }
  }

  override def resetDynamicState = pivotComponent.resetDynamicState

  override def getState = pivotComponent.getState
  override def setState(state:Option[ComponentState]) = pivotComponent.setState(state)
  override def getTypeState = pivotComponent.getTypeState
  override def setTypeState(typeState:Option[ComponentTypeState]) = pivotComponent.setTypeState(typeState)  

  reactions += {
    case SelectionChanged(`dataTypeCombo`) => {
      var fieldsState = pageContext.getSetting(StandardUserSettingKeys.UserMarketDataTypeLayout, Map[MarketDataTypeLabel,PivotFieldsState]()).get(dataTypeCombo.selection.item)
      fieldsState.foreach { fs => {
        thisPage.pageState.pivotPageState.pivotFieldParams.pivotFieldState.foreach {
          pfs => {
            pfs.fieldSelection(Field("Observation Day")).foreach { observationDays => {
              fieldsState = Some(fs.addFilter(Field("Observation Day") -> observationDays))
            } }
          }
        }
      } }

      pageContext.goTo(new MarketDataPage(thisPage.marketDataIdentifier, MarketDataPageState(
        marketDataType = Some(dataTypeCombo.selection.item),
        pivotPageState = PivotPageState(false, PivotFieldParams(true, fieldsState))
      )))
    }
    case SelectionChanged(`snapshotsComboBox`) =>
      pageContext.goTo(thisPage.copy(marketDataIdentifier=StandardMarketDataPageIdentifier(thisPage.marketDataIdentifier.marketDataIdentifier.copy(marketDataVersion = snapshotsComboBox.value))))
    case MarketDataSelectionChanged(selection) => pageContext.goTo(
      thisPage.copy(marketDataIdentifier=StandardMarketDataPageIdentifier(MarketDataIdentifier(selection, SpecificMarketDataVersion(pageContext.localCache.latestMarketDataVersion(selection)))))
    )
  }
  listenTo(dataTypeCombo.selection, marketDataSelectionComponent, snapshotsComboBox.selection)

  val snapshotsPanel = new MigPanel("insets 0") {
    add(new Label("Market Data as of:"))
    add(snapshotsComboBox)
  }

  if (thisPage.marketDataIdentifier.filteredMarketData) {
    filterDataCheckbox.selected = true
    filterDataCheckbox.visible = true
  } else {
    filterDataCheckbox.visible = false
  }


  add(LabelWithSeparator("Market Data Selection"), "spanx, growx, wrap")
  add(importButton, "skip 1")
  add(marketDataSelectionComponent, "skip 1")
  add(snapshotsPanel, "skip 1, ay top, wrap unrel, gapright " + RightPanelSpace)
  add(LabelWithSeparator("Select Market Data Type"), "spanx, growx, wrap")
  add(new Label("Data Type:"), "skip 1, split, spanx")
  add(dataTypeCombo)
  add(filterDataCheckbox, "wrap unrel")
  add(pivotComponent, "spanx, push, grow")
}

case class MarketDataPageState(
        pivotPageState : PivotPageState = PivotPageState(false, PivotFieldParams(true, None)),
        marketDataType : Option[MarketDataTypeLabel] = None)

case class MarketDataPagePageData(marketDataTypeLabels:List[MarketDataTypeLabel], selection:Option[MarketDataTypeLabel]) extends PageData