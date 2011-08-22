package starling.gui.pages

import starling.gui._
import api._
import custom.painters.StripedCornerPainter
import scala.swing.Swing._
import java.awt.{Font, Color, Dimension, Cursor}
import java.awt.event.KeyEvent
import org.jdesktop.swingx.painter.{CompoundPainter, GlossPainter}
import scala.swing._
import swing.event._
import starling.daterange.Day
import starling.pivot.view.swing._
import starling.pivot._
import javax.swing.{JComponent, KeyStroke}

case class StarlingHomePage() extends Page {
  def build(reader:PageBuildingContext) = {HomePagePageData(reader.cachingStarlingServer.version, reader.cachingStarlingServer.desks.headOption)}
  def createComponent(context: PageContext, data: PageData, bookmark:Bookmark, browserSize:Dimension) = new StarlingHomePageComponent(context, browserSize, data)
  def text = "Starling"
  def icon = StarlingIcons.im("/icons/weather-clear.png")
}

case class HomePagePageData(version:Version, initialDesk:Option[Desk]) extends PageData

class StarlingHomePageComponent(context:PageContext, browserSize:Dimension, pageData:PageData) extends MigPanel("insets 0") with PageComponent {
  private val data = pageData match {case d:HomePagePageData => {d}}

  private val bookmarksPanel = new BookmarksPanel(context)
  bookmarksPanel.background = GuiUtils.TaskPageButtonBackgroundColour
  bookmarksPanel.border = LineBorder(GuiUtils.TaskPageButtonBorderColour)
  val componentsBkColour = new Color(228, 231, 246)
  bookmarksPanel.bookmarksListView.background = componentsBkColour
  bookmarksPanel.dayPicker.background = componentsBkColour
  bookmarksPanel.goToBookmarkButton.background = GuiUtils.TaskPageBackgroundColour

  override def getState = {
    Some(StarlingHomePageComponentState(bookmarksPanel.bookmarksListView.selectedOption))
  }

  override def setState(state:Option[ComponentState]) {
    state match {
      case Some(StarlingHomePageComponentState(selectedBookmark)) => {
        bookmarksPanel.bookmarksListView.selectedOption = selectedBookmark
      }
      case _ =>
    }
  }

  private val versionPanel = new MigPanel("") {
    border = LineBorder(GuiUtils.TaskPageButtonBorderColour)
    background = GuiUtils.TaskPageButtonBackgroundColour
   
    val ver = data.version
    if (ver.production) {
      add(new Label("<html><b>Production</b></html>"), "ax center, gapleft 100lp, gapright 100lp")
    } else {
      add(new Label("<html><b>" + data.version.name + " (" + data.version.hostname + ")</b></html>"), "ax center, wrap")
      add(new Label(data.version.database), "span, ax center")
    }
  }
    
  val c = new MigPanel("insets 0", "[grow,fill]", "[p]0[grow,fill]") {
    val banner = new MigXPanel("insets 0", "[p][p][p]push[p]") {
      background = GuiUtils.BannerColour
      val gp = new GlossPainter
      val sp = StripedCornerPainter(new Color(0,0,200))
      backgroundPainter = new CompoundPainter(sp,gp)
      
      val logoImage = StarlingIcons.im("/icons/small_sunny_bird2.png")
      val logo = new FixedImagePanel(logoImage)

      val nameLabel = new Label {
        text = "Starling"
        font = new Font("Lucida Sans", Font.PLAIN, 30)
      }
      val welcomeLabel = new Label {
        text = "W E L C O M E !"
        font = new Font("Dialog", Font.BOLD, 60)
        foreground = new Color(255,221,138)
      }
      val userImage = StarlingIcons.im("/icons/32x32_user_dark.png")
      val userButton = new ReferenceDataButton(context.localCache.currentUser.name, userImage,
        ctrlDown => context.goTo(UserDetailsPage(context.localCache.currentUser), ctrlDown), false)
      userButton.label.font = new Font("Serif", Font.PLAIN, 20)

      add(logo)
      add(nameLabel, "ay bottom, gapbottom 5lp")
      add(welcomeLabel, "ay center, gapleft 20lp")
      add(userButton, "ay center, gapright " + GuiUtils.StandardLeftIndent)
    }

    val actionsPanelHolder = new MigPanel("insets dialog") {
      val actionsPanel = new StripedPanel("insets 0", "[grow][p][p][p][p][grow]", "[grow][p][p][grow 150][p]") {
        val tradeDataImage = StarlingIcons.im("/icons/32x32_trades.png")
        val tradeDataButton = new ReferenceDataButton("Trades", tradeDataImage, gotoTradePage, number = Some("1."))

        val refDataImage = StarlingIcons.im("/icons/32x32_ref_data.png")
        val refDataButton = new ReferenceDataButton("Reference Data", refDataImage, ctrlDown => {context.goTo(ReferenceDataIndexPage, ctrlDown)}, number = Some("2."))

        val marketDataImage = StarlingIcons.im("/icons/32x32_market_data.png")
        val marketDataButton = new ReferenceDataButton("Market Data", marketDataImage, gotoMarketDataPage, number = Some("3."))

        val curveViewerImage = StarlingIcons.im("/icons/32x32_curve_viewer.png")
        val curveViewerButton = new ReferenceDataButton("Curve Viewer", curveViewerImage, gotoCurvePage, number = Some("4."))

        val helpLabelHolder = new MigPanel {
          background = new Color(0,0,0,0)
          opaque = false

          val helpImage = StarlingIcons.im("/icons/32x32_Help.png")
          add(new FixedImagePanel(helpImage) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            reactions += {
              case MouseClicked(_,_,_,_,_) => {
                context.goTo(HelpPage)
              }
            }
            listenTo(mouse.clicks)
          }, "push,grow")
        }

        add(helpLabelHolder, "split, spanx, ax right, ay top, wrap")
        add(tradeDataButton, "sg, skip 1")
        add(refDataButton, "sg")
        add(marketDataButton, "sg")
        add(curveViewerButton, "sg, wrap unrel")

        add(bookmarksPanel, "skip 1, spanx 4, growx, wrap")
        add(versionPanel, "newline, split, spanx, ax center, gapbottom 5lp")
      }
      add(actionsPanel, "push,grow")
    }

    add(banner, "wrap")
    add(actionsPanelHolder)
  }

  peer.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "tradesAction")
  peer.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "refDataAction")
  peer.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_3, 0), "marketDataAction")
  peer.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_4, 0), "curveAction")
  peer.getActionMap.put("tradesAction", Action("tradesAction"){gotoTradePage(false)}.peer)
  peer.getActionMap.put("refDataAction", Action("refDataAction"){context.goTo(ReferenceDataIndexPage)}.peer)
  peer.getActionMap.put("marketDataAction", Action("marketDataAction"){gotoMarketDataPage(false)}.peer)
  peer.getActionMap.put("curveAction", Action("curveAction"){gotoCurvePage(false)}.peer)

  override def defaultComponentForFocus = Some(bookmarksPanel.bookmarksListView.peer)

  private val runAct = bookmarksPanel.goToBookmarkAction
  peer.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), runAct.title)
  peer.getActionMap.put(runAct.title, runAct.peer)

  private def gotoTradePage(ctrlDown:Boolean) {
    context.goTo( {
      val initial = {
        val defaultSelection = (data.initialDesk, None) 
        val lastSelection = context.getSetting(StandardUserSettingKeys.InitialTradeSelection, defaultSelection)
        lastSelection match {
          case (_, Some(groups)) => {
            val validGroups = context.localCache.intradaySubgroups.keySet
            if (groups.subgroups.forall(g => validGroups.exists(vg => vg.startsWith(g)))) lastSelection else defaultSelection
          }
          case _ => lastSelection
        }
      }

      val deskWithTime = initial._1.flatMap(d => context.localCache.latestTimestamp(d).map(ts => (d, ts)))
      val intradayWithTime = initial._2.map(groups => (groups, context.localCache.latestTimestamp(groups)))

      TradeSelectionPage(TradePageParameters(
        deskWithTime, intradayWithTime,
        TradeExpiryDay(Day.today())), PivotPageState(false, PivotFieldParams(true, None)))
    }, ctrlDown)
  }

  def gotoMarketDataPage(ctrlDown:Boolean) {
    MarketDataPage.goTo(context, StandardMarketDataPageIdentifier(defaultMarketDataIdentifier), None, None, ctrlDown)
  }

  def gotoCurvePage(ctrlDown: Boolean) {
    val curveLabel = CurveLabel(CurveTypeLabel("Price"), defaultMarketDataIdentifier, EnvironmentSpecificationLabel(
      context.localCache.populatedDays(defaultMarketDataIdentifier.selection).lastOption.getOrElse(Day.today()),
      context.localCache.environmentRulesForPricingGroup(defaultMarketDataIdentifier.selection.pricingGroup).head
    ))

    val initialState = PivotFieldsState(
      dataFields = List(Field("Price"), Field("Input")),
      columnFields = List(Field("Observation Time"), Field("Market")),
      rowFields = List(Field("Period"))
    )

    context.goTo(new CurvePage(curveLabel, PivotPageState.default(initialState)), ctrlDown)
  }

  private def defaultMarketDataIdentifier: MarketDataIdentifier = {
    val initialSelection = context.getSetting(StandardUserSettingKeys.InitialMarketDataSelection,
      MarketDataSelection(context.localCache.pricingGroups(None).headOption))
    val latestMarketDataVersion = context.localCache.latestMarketDataVersion(initialSelection)

    MarketDataIdentifier(initialSelection, latestMarketDataVersion)
  }

  add(c, "push,grow")

//  context.setDefaultButton(Some(reportsPanel.runButton))
}

case class StarlingHomePageComponentState(bookmarkSelected:Option[BookmarkData]) extends ComponentState