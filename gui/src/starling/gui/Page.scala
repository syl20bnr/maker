package starling.gui

import api._
import java.awt.image.BufferedImage
import pages.{ExceptionPageComponent, TimestampChooser, PageResponse}
import starling.rmi.StarlingServer
import swing.event.Event
import starling.utils.HeterogeneousMap
import javax.imageio.ImageIO
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, BufferedOutputStream}
import java.util.concurrent.{Callable, Future, Executors, CountDownLatch}
import swing.{Publisher, Button, Component}
import ref.SoftReference
import javax.swing.border.Border
import java.awt.{Dimension, Graphics2D}
import starling.daterange.{Day, Timestamp}
import collection.immutable.TreeSet
import collection.SortedSet
import javax.swing.BorderFactory
import starling.eai.Book
import starling.auth.User
import java.awt.{Component=>AWTComp}

/**
 * The StarlingBrowser models everything as a Page (defined here)
 *
 * IMPORTANT - I page should usually be a case class with the whole page described by parameters passed into it. No other vals should exist
 * and everything else in the page class should be a def.
 */
trait Page {
  def icon:BufferedImage
  def text:String
  def shortText:String = text
  def createComponent(context:PageContext, data:PageData, bookmark:Bookmark, browserSize:Dimension):PageComponent
  def build(reader:PageBuildingContext):PageData
  def refreshFunctions:Iterable[PartialFunction[Event,Page]] = Nil
  def bookmark(server:StarlingServer):Bookmark = new PageBookmark(this)
}

trait Bookmark {
  def daySensitive:Boolean
  def createPage(day:Option[Day], server:StarlingServer, context:PageContext):Page
}

case class BookmarkData(name:String, bookmark:Bookmark)

case class PageBookmark(page:Page) extends Bookmark {
  def daySensitive = false
  def createPage(day:Option[Day], server:StarlingServer, context:PageContext) = page
}

trait PageContext {
	def goTo(page:Page, newTab:Boolean=false, compToFocus:Option[AWTComp]=None)
  def createAndGoTo(buildPage:StarlingServer=>Page, onException:PartialFunction[Throwable, Unit] = { case e:UnsupportedOperationException => {}}, newTab:Boolean = false, compToFocus:Option[AWTComp]=None)
  def submit[R](submitRequest:SubmitRequest[R], onComplete:R=>Unit=(r:R)=>(), keepScreenLocked:Boolean = false, awaitRefresh:R=>Boolean=(r:R)=>false): Unit
  def submitF[R](f: StarlingServer => R, onComplete:R=>Unit=(r:R)=>(), keepScreenLocked:Boolean = false, awaitRefresh:R=>Boolean=(r:R)=>false) {
    submit(new SubmitRequest[R] {
      def submit(server: StarlingServer) = f(server)
    }, onComplete, keepScreenLocked, awaitRefresh)
  }
  def submitYesNo[R](message:String, description:String, submitRequest:SubmitRequest[R], awaitRefresh:R=>Boolean, onComplete:R=>Unit, keepScreenLocked:Boolean = false)
  def clearCache()
  def setContent(content:Component, cancelAction:Option[()=> Unit])
  def setErrorMessage(title:String, error:String)
  def clearContent()
  def setDefaultButton(button:Option[Button])
  def getDefaultButton:Option[Button]
  def localCache:LocalCache
  val remotePublisher:Publisher
  def requestFocusInCurrentPage()
  def getSetting[T](key:Key[T])(implicit m:Manifest[T]):T
  def getSetting[T](key:Key[T], default: => T)(implicit m:Manifest[T]):T
  def getSettingOption[T](key:Key[T])(implicit m:Manifest[T]):Option[T]
  def putSetting[T](key:Key[T], value:T)(implicit m:Manifest[T])
}

case class LocalCache(localCache:HeterogeneousMap[Key]) {
  import starling.gui.LocalCacheKeys._
  def userPivotLayouts = localCache(UserPivotLayouts)
  def bookmarks = localCache(Bookmarks)
  def pricingGroups(maybeDesk:Option[Desk]):List[PricingGroup] = localCache(PricingGroups).intersect(validGroups(maybeDesk))
  def excelDataSets = localCache(ExcelDataSets)

  def latestTimestamp(desk: Desk): Option[TradeTimestamp] = deskCloses(desk).sortWith(_.timestamp > _.timestamp).headOption
  def latestTimestamp(groups: IntradayGroups): Timestamp = {
    val dTS = TimestampChooser.defaultUnitialisedValue.closeDay.toTimestamp
    // A path can be passed in so in that case get the max value of all it's children.
    val map = localCache(IntradayLatest)
    val availableGroups = map.keySet
    val groupsToUse = groups.subgroups.flatMap(g => {
      if (availableGroups.contains(g)) {
        List(g)
      } else {
        availableGroups.filter(_.startsWith(g + "/")).toList
      }
    })
    groupsToUse.map(n => localCache(IntradayLatest).getOrElse(n, (User.Dev, dTS))._2) match {
      case Nil => dTS
      case l => l.max
    }
  }

  def deskCloses(desk: Option[Desk]): List[TradeTimestamp] = desk.map(deskCloses).getOrElse(Nil)
  def deskCloses(desk: Desk): List[TradeTimestamp] = {
    localCache(DeskCloses).get(desk).map(closes => closes.values.flatten.toList.sortWith(_.timestamp > _.timestamp)).getOrElse(Nil)
  }

  def traderBookLookup: Map[User, (Book, Desk)] = localCache(TradersBookLookup)

  def curveTypes = localCache(CurveTypes)

  def intradaySubgroups = localCache(IntradayLatest)

  def currentUser = localCache(CurrentUser)

  def snapshots(maybeDesk:Option[Desk]) = {
    val vg = validGroups(maybeDesk)
    localCache(Snapshots).filter{ case(MarketDataSelection(pg, excel), snapshots) => !pg.isDefined || vg.contains(pg.get) }
  }
  def environmentRulesForPricingGroup(pricingGroup:Option[PricingGroup]) = {
    pricingGroup match {
      case Some(pg) => localCache(EnvironmentRules)(pg)
      case None => localCache(EnvironmentRules).values.flatten.toSet.toList
    }
  }

  def populatedDays(selection:MarketDataSelection):SortedSet[Day] = {
    val pricingGroupDays = selection.pricingGroup match {
      case Some(pg) => populatedObservationDaysForPricingGroup.getOrElse(pg, Set())
      case None => Set()
    }
    val excelDays = selection.excel match {
      case Some(name) => populatedObservationDaysForExcel.getOrElse(name, Set())
      case None => Set()
    }
    TreeSet[Day]() ++ pricingGroupDays ++ excelDays
  }
  def populatedObservationDaysForPricingGroup = localCache(ObservationDaysForPricingGroup)
  def populatedObservationDaysForExcel = localCache(ObservationDaysForExcel)
  def latestMarketDataVersion(selection:MarketDataSelection) = latestMarketDataVersionIfValid(selection).get
  def latestMarketDataVersionIfValid(selection:MarketDataSelection) = {
    if (selection.excel.isDefined && !localCache(ExcelLatestMarketDataVersion).contains(selection.excel.get)) {
      None
    } else {
      val versions = selection.pricingGroup.toList.map { pg =>
        localCache(PricingGroupLatestMarketDataVersion)(pg)
      } ::: selection.excel.toList.map { excel =>
        localCache(ExcelLatestMarketDataVersion)(excel)
      }
      Some(if (versions.isEmpty) 0 else versions.max)
    }
  }
  def reportOptionsAvailable = localCache(ReportOptionsAvailable)
  def userNotifications = localCache(UserNotifications)
  def removeUserNotification(notification:Notification) = localCache(UserNotifications) = localCache(UserNotifications).filterNot(_ == notification)
  def removeAllUserNotifications = localCache(UserNotifications) = List()
  def version = localCache(Version)
  def ukBusinessCalendar = localCache(UKBusinessCalendar)
  def allUserNames = localCache(AllUserNames)
  def desks = localCache(Desks)
  def groupToDesksMap = localCache(GroupToDesksMap)
  def isStarlingDeveloper = localCache(IsStarlingDeveloper)

  private def validGroups(maybeDesk:Option[Desk]) = maybeDesk.map(_.pricingGroups).getOrElse(PricingGroup.values)
}

trait PageData
trait OldPageData
trait ComponentState
trait ComponentTypeState
trait ComponentRefreshState
trait TypeFocusInfo
trait PageComponent extends Component {
  def getBorder:Option[Border] = Some(BorderFactory.createMatteBorder(1, 0, 0, 0, GuiUtils.BorderColour))
  def restoreToCorrectViewForBack() {}
  def getState:Option[ComponentState] = None
  def setState(state:Option[ComponentState]) {}
  def resetDynamicState() {}
  def pageHidden() {}
  def pageShown() {}
  def getTypeState:Option[ComponentTypeState] = None
  def setTypeState(typeState:Option[ComponentTypeState]) {}
  def getTypeFocusInfo:Option[TypeFocusInfo] = None
  def setTypeFocusInfo(focusInfo:Option[TypeFocusInfo]) {}
  def getOldPageData:Option[OldPageData] = None
  def getRefreshState:Option[ComponentRefreshState] = None
  def setOldPageDataOnRefresh(pageData:Option[OldPageData], refreshState:Option[ComponentRefreshState], componentState:Option[ComponentState]) {}
  def pageResized(newSize:Dimension) {}
  def defaultComponentForFocus:Option[java.awt.Component] = None

  override def paintChildren(g:Graphics2D) {
    try {
      super.paintChildren(g)
    } catch {
      case e:Exception => {
        e.printStackTrace()
        peer.removeAll()
        peer.add(new ExceptionPageComponent("Exception during paint", e).peer, "push, grow")
        revalidate()
        repaint()
      }
    }
  }
}
class PageInfo(val page: Page, val pageResponse:PageResponse, val bookmark:Bookmark, var pageComponent:Option[PageComponent],
               var pageComponentSoft:SoftReference[PageComponent], var componentState:Option[ComponentState],
               var refreshPage:Option[Page], var autoRefresh:Option[CountDownLatch]=None,
               var componentForFocus:Option[java.awt.Component]=None) {
  def image:BufferedImage = {
    if (future == null) {
      null
    } else {
      val byteArrayInputStream = new ByteArrayInputStream(future.get)
      val i = ImageIO.read(byteArrayInputStream)
      byteArrayInputStream.close
      i
    }
  }

  private val backgroundThread = Executors.newSingleThreadExecutor
  private var future:Future[Array[Byte]] = null

  def image_=(i:BufferedImage) {
    // I don't want the future to hold onto the BufferedImage so we have to put it in an array and null the element later.
    val iA = Array(i)
    future = backgroundThread.submit(new Callable[Array[Byte]] {
      def call = {
        val out = new ByteArrayOutputStream
        val bOut = new BufferedOutputStream(out)
        ImageIO.write(iA(0), "png", bOut)
        iA(0) = null
        val bytes = out.toByteArray
        bOut.close
        bytes
      }
    })
  }
}