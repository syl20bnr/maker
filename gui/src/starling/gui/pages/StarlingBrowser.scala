package starling.gui.pages

import scala.collection.mutable.ArrayBuffer
import scala.swing._
import scala.swing.Action
import javax.swing._
import starling.pivot.view.swing._
import org.jdesktop.animation.timing.{TimingTargetAdapter, Animator}
import java.awt.image.BufferedImage
import starling.gui._
import starling.gui.api._
import custom.painters.VerticalGradientPaint
import java.awt.event.{InputEvent, KeyEvent, ActionListener, ActionEvent}
import scala.swing.Swing._
import java.awt.{Dimension, Graphics2D, Color, Graphics, RenderingHints, Point, KeyboardFocusManager}
import net.miginfocom.swing.MigLayout
import starling.utils.cache.ThreadSafeCachingProxy
import starling.rmi.StarlingServer
import java.util.concurrent.{CountDownLatch, ThreadFactory, Executors}
import ref.SoftReference
import org.jboss.netty.handler.codec.frame.TooLongFrameException
import PageLogger.logPageView
import net.miginfocom.layout.LinkHandler
import starling.utils.{Log, StackTraceToString}
import swing.event.{UIElementResized, MouseClicked, ButtonClicked}
import java.lang.reflect.UndeclaredThrowableException
import starling.bouncyrmi.{ClientOfflineException, ServerUpgradeException, OfflineException}
import java.awt.{Component=>AWTComp}
import javax.swing.JTabbedPane._

/**
 * All on the swing thread
 */
class StarlingBrowser(pageBuilder:PageBuilder, lCache:LocalCache, userSettings:UserSettings,
                      val remotePublisher:Publisher, homePage:Page, initialAddressText:String,
                      windowMethods:WindowMethods, tabComponent:TabComponent,
                      openTab:(Boolean,Either[Page,(StarlingServer=>Page, PartialFunction[Throwable, Unit])])=>Unit,
                      extraInfo:Option[String])
        extends MigPanel("insets 0") { starBrows:StarlingBrowser =>

  reactions += {
    case UIElementResized(`starBrows`) => {
      val newSize = size
      history.foreach(pageInfo => {
        pageInfo.pageComponent match {
          case Some(pc) => pc.pageResized(newSize)
          case None => pageInfo.pageComponentSoft.get match {
            case Some(pc) => pc.pageResized(newSize)
            case None =>
          }
        }
      })
    }
  }
  listenTo(starBrows)

  private val componentCaching = true

  private val starlingBrowserUI = new StarlingBrowserUI
  val starlingBrowserLayer = new SXLayerScala(this, starlingBrowserUI)
  var threadSequence = 0
  private val componentBufferWindow = 1
  val history = new ArrayBuffer[PageInfo]
  var current = -1

  private val mainPanel = new BorderPanel
  private val genericLockedUI = new GenericLockedUI
  private val greyClient = new GreyedLockedUIClient(genericLockedUI)

  private val solidClient = new SolidColourClient(genericLockedUI)

  private val backSlideClient = new BackSlideClient(genericLockedUI)
  private val forwardSlideClient = new ForwardSlideClient(genericLockedUI)
  private val imageClient = new ImageClient(genericLockedUI)
  private val mainPanelLayer = new SXLayerScala(mainPanel, genericLockedUI)

  private val slideTime = 250
  private val slideAcceleration = 0.2f

  private val publisherForPageContext = new Publisher() {}

  reactions += {
    case EventBatch(events) => {

      events.foreach { e => publisherForPageContext.publish(e) }

      events.foreach { e =>
        history.foreach(pageInfo => {
          val pageToCheck = pageInfo.refreshPage.getOrElse(pageInfo.page) //Without this multiple updates are lost
          val matchingCallbacks = pageToCheck.refreshFunctions.filter(f=>f.isDefinedAt(e))
          if (matchingCallbacks.size > 1) {
            println("Warning: An event matched more than one function. Only the first will be applied")
            println("  Page: " + pageToCheck.getClass + " " + pageToCheck)
            println("  Event: " + e)
            println()
          }
          matchingCallbacks.headOption match {
            case None =>
            case Some(f) => {
              val newPage = f(e)
              if (newPage != pageInfo.page) {
                println("Update for page " + newPage)
                pageInfo.refreshPage=Some(newPage)
                if (pageInfo.autoRefresh.isDefined) {
                  val autoRefresh = pageInfo.autoRefresh.get
                  pageInfo.autoRefresh = None
                  refresh(lockAndUnlockScreen=false, latch = Some(autoRefresh))
                }
              }
            }
          }
        })
      }
      if ((current >= 0) && (current < history.length)) {
        if (history(current).refreshPage.isDefined) {
          if (liveUpdateCheckbox.selected) {
            refresh()
          } else {
            refreshButton.enabled = true
          }
        }
      }
    }
    case e@UserSettingUpdated(_) => publisherForPageContext.publish(e)
  }
  listenTo(remotePublisher)

  private val pageContext = new PageContext {
    def goTo(page:Page, newTab:Boolean=false, compToFocus:Option[AWTComp]) {
      val cf = if (compToFocus.isDefined) compToFocus else currentFocus
      history(current).componentForFocus = cf
      if (newTab) openTab(true, Left(page)) else StarlingBrowser.this.goTo(page, false)
    }
    def createAndGoTo(buildPage:StarlingServer=>Page, onException:PartialFunction[Throwable, Unit], newTab:Boolean, compToFocus:Option[AWTComp]) { if (newTab) openTab(true, Right((buildPage, onException))) else StarlingBrowser.this.goTo(Right((buildPage, onException)), false) }
    def submit[R](submitRequest:SubmitRequest[R], onComplete:R => Unit, keepScreenLocked:Boolean, awaitRefresh:R=>Boolean=(r:R)=>false) {StarlingBrowser.this.submit(submitRequest, awaitRefresh, onComplete, keepScreenLocked)}
    def submitYesNo[R](message:String, description:String, submitRequest:SubmitRequest[R], awaitRefresh:R=>Boolean, onComplete:R => Unit, keepScreenLocked:Boolean) = {StarlingBrowser.this.submitYesNo(message, description, submitRequest, awaitRefresh, onComplete, keepScreenLocked)}
    def clearCache() {StarlingBrowser.this.clearCache}
    def setContent(content:Component, cancelAction:Option[()=> Unit]) {StarlingBrowser.this.setContent(content, cancelAction)}
    def setErrorMessage(title:String, error:String) {StarlingBrowser.this.setError(title, error)}
    def clearContent() {StarlingBrowser.this.clearContent}
    def setDefaultButton(button:Option[Button]) {StarlingBrowser.this.setDefaultButton(button)}
    def getDefaultButton = {StarlingBrowser.this.getDefaultButton}
    def localCache = lCache
    val remotePublisher = publisherForPageContext
    def requestFocusInCurrentPage() {StarlingBrowser.this.requestFocusInCurrentPage()}
    def getSetting[T](key:Key[T])(implicit m:Manifest[T]) = userSettings.getSetting(key)
    def getSetting[T](key:Key[T], default: => T)(implicit m:Manifest[T]) = userSettings.getSetting(key, default)
    def getSettingOption[T](key:Key[T])(implicit m:Manifest[T]) = userSettings.getSettingOption(key)
    def putSetting[T](key:Key[T], value:T)(implicit m:Manifest[T]) {
      val oldValue = getSettingOption(key)
      userSettings.putSetting(key, value)
      oldValue match {
        case None if value != None => StarlingBrowser.this.remotePublisher.publish(UserSettingUpdated(key))
        case Some(old) => if (value != old) {
          StarlingBrowser.this.remotePublisher.publish(UserSettingUpdated(key))
        }
        case _ =>
      }
    }
  }
    
  private val undoAction = Action("undoAction") {
    history(current).componentForFocus = currentFocus
    currentComponent.resetDynamicState()
    val oldIndex = current
    current-=1
    val pageInfo = history(current)
    onEDT(showPageBack(pageInfo, oldIndex))
  }

  private val backPageAction = new AbstractAction {
    def actionPerformed(e:ActionEvent) {
      history(current).componentForFocus = currentFocus
      currentComponent.resetDynamicState()

      // Look for a page that is different to the current one.
      val oldIndex = current
      val currentText = history(current).page.text
      val testIndex = history.toList.reverse.indexWhere(_.page.text != currentText, history.length - current)
      current = if (testIndex == -1) {
        0
      } else {
        history.length - testIndex - 1
      }
      if ((current + 1) != oldIndex) {
        current += 1
      }
      val pageInfo = history(current)
      showPageBack(pageInfo, oldIndex)
    }
  }

  private def setButtonsEnabled(enabled:Boolean) {
    backButton.enabled = enabled    
    undoButton.enabled = enabled
    forwardButton.enabled = enabled
    redoButton.enabled = enabled
    refreshButton.enabled = enabled
    stopButton.enabled = enabled
    homeButton.enabled = enabled
    settingsButton.enabled = enabled
    liveUpdateCheckbox.enabled = enabled
    bookmarkButton.enabled = enabled
    bookmarkDropDownButton.enabled = enabled
    enableAddressBar(enabled)
  }

  private def setTitleText(text:String) {
    addressBar.titleText.text = text
    addressBar.titleText.peer.setCaretPosition(0)
  }

  // pageInfo is the page you are going back to. indexGoingFrom is the "current" page.
  private def showPageBack(pageInfo: PageInfo, indexGoingFrom:Int) {
    val page = pageInfo.page
    if (page.text == (history(indexGoingFrom).page.text)) {
      // Move back without sliding.
      showPage(pageInfo, indexGoingFrom)
      refreshButtonStatus
    } else {
      setButtonsEnabled(false)
      setTitleText(page.text)
      addressIcon.image = page.icon
      tabComponent.setTextFromPage(page)

      // Slide back.
      genericLockedUI.setLocked(true)
      genericLockedUI.setClient(backSlideClient)
      val width = currentComponent.peer.getWidth
      val image1 = new BufferedImage(width, currentComponent.peer.getHeight, BufferedImage.TYPE_INT_RGB)
      val graphics = image1.getGraphics.asInstanceOf[Graphics2D]
      currentComponent.peer.paint(graphics)
      graphics.dispose

      history(indexGoingFrom).image = image1

      val image2 = pageInfo.image
      backSlideClient.setImages(image1, image2)
      val animator = new Animator(slideTime)
      animator.setAcceleration(slideAcceleration)
      animator.setDeceleration(slideAcceleration)
      val timingTarget = new TimingTargetAdapter {
        override def timingEvent(fraction: Float) = backSlideClient.setX(fraction * width)

        override def end = {
          backSlideClient.setX(width)
          refreshButtonStatus
          onEDT({
            showPage(pageInfo, indexGoingFrom)
            setScreenLocked(false)
            backSlideClient.reset
          })
        }
      }
      animator.addTarget(timingTarget)
      animator.start
    }
  }

  peer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
    KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "utilsPage")
  peer.getActionMap.put("utilsPage", new AbstractAction(){def actionPerformed(e:ActionEvent) = {
    openTab(true, Left(UtilsPage()))
  }})

  peer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "reload")
  peer.getActionMap.put("reload", new AbstractAction () {
    def actionPerformed(p1: ActionEvent) = {
      val info = history(current)
      val currentPage = info.page
      val currentBookmark = info.bookmark
      val currentState = info.pageComponent match {
        case None => None
        case Some(pc) => pc.getState
      }
      val rebuiltPageComponent = createPopulatedComponent(currentPage, info.pageResponse)
      rebuiltPageComponent.setState(currentState)
      val pageInfo = new PageInfo(currentPage, info.pageResponse, currentBookmark, Some(rebuiltPageComponent),
        new SoftReference(rebuiltPageComponent), currentState, info.refreshPage)
      history(current) = pageInfo
      showPage(pageInfo, current)
    }
  })

  val backButton: Button = new NavigationButton {
    icon = StarlingIcons.icon("/icons/22x22/actions/go-previous.png")
    peer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "backPageAction")
    peer.getActionMap.put("backPageAction", backPageAction)
    peer.setMnemonic(java.awt.event.KeyEvent.VK_LEFT)
    tooltip = "Back to previous page (Backspace or Alt+Left)"
	  reactions += {
      case ButtonClicked(button) => {
        starlingBrowserUI.clearContentPanel
        backPageAction.actionPerformed(null)
      }
    }
  }

  val undoButton = new NavigationButton {
    icon = StarlingIcons.icon("/icons/22x22/actions/22x22_edit_undo_n.png")
    tooltip = "Undo the last action (Ctrl+z)"

    val mar = peer.getMargin
    peer.setMargin(new Insets(mar.top,0,mar.bottom,0))

    peer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undoAction")
    peer.getActionMap.put("undoAction", undoAction.peer)

    reactions += {
      case ButtonClicked(button) => {
        starlingBrowserUI.clearContentPanel
        undoAction.peer.actionPerformed(null)
      }
    }
  }

  private val redoAction = Action("redoAction") {
    history(current).componentForFocus = currentFocus
    starlingBrowserUI.clearContentPanel
    currentComponent.resetDynamicState
    val oldIndex = current
    current+=1
    val pageInfo = history(current)
    showPageForward(pageInfo, oldIndex)
  }

  val redoButton = new NavigationButton {
    icon = StarlingIcons.icon("/icons/22x22/actions/edit-redo.png")
    tooltip = "Redo the last action (Ctrl+Shift+z)"

    val mar = peer.getMargin
    peer.setMargin(new Insets(mar.top,0,mar.bottom,0))

    peer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redoAction")
    peer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redoAction")
    peer.getActionMap.put("redoAction", redoAction.peer)

    reactions += {
      case ButtonClicked(button) => {
        starlingBrowserUI.clearContentPanel
        redoAction.peer.actionPerformed(null)
      }
    }
  }

  private def showPageForward(pageInfo: PageInfo, indexGoingFrom:Int) {
    if (history(indexGoingFrom).page.text == pageInfo.page.text) {
      showPage(pageInfo, indexGoingFrom)
      refreshButtonStatus
    } else {
      setButtonsEnabled(false)
      setTitleText(pageInfo.page.text)
      addressIcon.image = pageInfo.page.icon
      tabComponent.setTextFromPage(pageInfo.page)

      // Slide forward.
      genericLockedUI.setLocked(true)
      genericLockedUI.setClient(forwardSlideClient)
      val width = currentComponent.peer.getWidth
      val image1 = new BufferedImage(width, currentComponent.peer.getHeight, BufferedImage.TYPE_INT_RGB)
      val graphics = image1.getGraphics.asInstanceOf[Graphics2D]
      currentComponent.peer.paint(graphics)
      graphics.dispose

      history(indexGoingFrom).image = image1

      val image2 = pageInfo.image
      forwardSlideClient.setImages(image1, image2)
      val animator = new Animator(slideTime)
      animator.setAcceleration(slideAcceleration)
      animator.setDeceleration(slideAcceleration)
      val timingTarget = new TimingTargetAdapter {
        override def timingEvent(fraction: Float) = forwardSlideClient.setX(-fraction * width)

        override def end = {
          forwardSlideClient.setX(-width)
          refreshButtonStatus
          onEDT({
            showPage(pageInfo, indexGoingFrom)
            setScreenLocked(false)
            forwardSlideClient.reset
          })
        }
      }
      animator.addTarget(timingTarget)
      animator.start
    }
  }

  val forwardButton:Button = new NavigationButton {
    icon = StarlingIcons.icon("/icons/22x22/actions/go-next.png")
    peer.setMnemonic(java.awt.event.KeyEvent.VK_RIGHT)
    tooltip = "Forward to next page (Alt+Right)"
	  reactions += {
         case ButtonClicked(button) => {
           starlingBrowserUI.clearContentPanel
           currentComponent.resetDynamicState

           val oldIndex = current
           val currentText = history(current).page.text
           val testIndex = history.toList.indexWhere(_.page.text != currentText, current)
           current = if (testIndex == -1) {
             history.length - 1
           } else {
             testIndex
           }

           val pageInfo = history(current)
        	 showPageForward(pageInfo, oldIndex)
         }
    }
  }

  val liveUpdateCheckbox = new CheckBox("Live") {
    background = new Color(0,0,0,0)
    opaque = false
    focusable = false
    tooltip = "Automatically update page when relavent events occur"
    selected = pageContext.getSetting(StandardUserSettingKeys.LiveDefault, false)
  }
  listenTo(liveUpdateCheckbox)
  reactions += { case ButtonClicked(`liveUpdateCheckbox`) => { if (refreshButton.enabled) refresh() } }

  
  val refreshButton = new NavigationButton {
    icon = StarlingIcons.icon(StarlingIcons.Refresh)
    tooltip = "Refresh"
    enabled = false
    reactions += {
      case ButtonClicked(button) => {
        refresh()
      }
    }
    var fadeColour = GuiUtils.ClearColour
    override protected def paintComponent(g:Graphics2D) = {
      super.paintComponent(g)
      g.setColor(fadeColour)
      g.fillRect(0,0,size.width,size.height)
    }
  }
  val stopButton = new NavigationButton {
    icon = StarlingIcons.icon("/icons/22x22/actions/process-stop.png")
    tooltip = "Stop"
		reactions += {
         case ButtonClicked(button) => {
           waitingFor = Set()
           val pageInfo = history(current)
           pageInfo.pageComponent.get.restoreToCorrectViewForBack()
           showPage(pageInfo, current)
           peer.setEnabled(false)
           setScreenLocked(false)
           refreshButtonStatus
           refreshButton.enabled = history(current).refreshPage.isDefined
         }
    }
    peer.setEnabled(false)
  }
  val homeButton = new NavigationButton {
    icon = StarlingIcons.icon("/icons/22x22/actions/go-home.png")
    tooltip = "Home"
		reactions += {
         case ButtonClicked(button) => {
           starlingBrowserUI.clearContentPanel
        	 goTo(homePage, false)
         }
    }
  }

  val enabledBorderColour = GuiUtils.BorderColour
  val disabledBorderColour = GuiUtils.DisabledBorderColour

  private val addressIcon = new FixedImagePanel(StarlingIcons.im("/icons/weather-clear.png"))
  private val arrowPanel = new FixedImagePanel(StarlingIcons.im("/icons/16x16_bullet_arrow_down.png"))
  private val arrowPanelHolder = new MigPanel("insets 0") {
    background = Color.WHITE
    border = MatteBorder(1, 0, 1, 1, GuiUtils.BorderColour)
    add(arrowPanel, "ay center, pushy")

    override def enabled_=(b:Boolean) = {
      super.enabled = b
      if (b) {
        border = MatteBorder(1, 0, 1, 1, enabledBorderColour)
      } else {
        border = MatteBorder(1, 0, 1, 1, disabledBorderColour)
      }
      repaint
    }
  }

  private val addressBar = new MigPanel("insets 0", "[p]0[p]0[p]") {
    opaque = false

    val titleText = new TextField {
      text = initialAddressText
      focusable = false

      override protected def paintBorder(g:Graphics2D) = {
        super.paintBorder(g)
        val width = size.width - 1
        val height = size.height - 2
        g.setColor(GuiUtils.BorderColour.brighter)
        g.drawLine(0, 1, 0, height)
        g.setColor(Color.WHITE)
        g.drawLine(width, 1, width, height)
      }
    }

    override def enabled_=(b:Boolean) = {
      titleText.enabled = b
      titleIconHolder.setEnabled(b)
    }

    val titleIconHolder = new JPanel(new MigLayout("insets 0 7lp 0 3lp")) {
      add(addressIcon.peer, "push, grow")
      setOpaque(false)
      setMinimumSize(getPreferredSize())

      override def paintBorder(g:Graphics) = {
        val g2 = g.asInstanceOf[Graphics2D]
        if (isEnabled) {
          g2.setColor(enabledBorderColour)
        } else {
          g2.setColor(disabledBorderColour)
        }
        val width = getWidth
        val height = getHeight - 1
        g2.drawLine(width, 0, width - 19, 0)
        g2.drawLine(width, height, width - 19, height)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.drawArc(0, 0, width - 14, height, 270, -180)
      }
    }

    add(titleIconHolder, "grow")
    add(titleText, "pushx, growx")
    add(arrowPanelHolder, "grow, ay center")
  }

  def enableAddressBar(enable:Boolean) {
    addressBar.enabled = enable
    addressIcon.enabled = enable
    arrowPanel.enabled = enable
    arrowPanelHolder.enabled = enable
    if (enable) {
      listenTo(arrowPanelHolder.mouse.clicks, addressBar.titleText.mouse.clicks)
    } else {
      deafTo(arrowPanelHolder.mouse.clicks, addressBar.titleText.mouse.clicks)
    }
  }

  reactions += {
    case MouseClicked(`arrowPanelHolder`, _,_,_,_) => showAddressPopup
    case MouseClicked(addressBar.titleText, _,_,_,_) => showAddressPopup
  }
  listenTo(arrowPanelHolder.mouse.clicks, addressBar.titleText.mouse.clicks)

  private def showAddressPopup {
    val historySelector = new JPopupMenu
    historySelector.setBorder(LineBorder(GuiUtils.BorderColour))
    for ((pageInfo, index) <- history.zipWithIndex.reverse) {
      val page = pageInfo.page
      val ac = new Action(page.text) {
        icon = new ImageIcon(page.icon)
        def apply() = {
          if (index != current) {
            if (index > current) {
              goForwardToPage(pageInfo, index)
            } else {
              goBackToPage(pageInfo, index)
            }
          }
        }
      }
      val menuItem = new MenuItem(ac) {
        if (current == index) {
          background = new Color(220, 245, 224)
        } else {
          background = Color.WHITE
        }
      }
      historySelector.add(menuItem.peer)
    }
    val prefSize = historySelector.getPreferredSize
    historySelector.setPreferredSize(new Dimension(addressBar.size.width - 5, prefSize.height))
    historySelector.show(addressBar.peer, 5, addressBar.size.height-1)
  }

  private val settingsButton = new NavigationButton {
    icon = StarlingIcons.icon("/icons/22x22/categories/preferences-system.png")
    tooltip = "Go to the settings page"
    reactions += {
      case ButtonClicked(e) => {
        pageContext.goTo(SettingsPage(), true)
      }
    }
  }
  println("XXXXXXX " + this.getClass().getClassLoader())
  var currentComponent:PageComponent = new NullPageComponent()
  mainPanel.peer.add(currentComponent.peer)
  var waitingFor:Set[Option[Int]] = Set()

  def setScreenLocked(locked: Boolean) {
    genericLockedUI.setLocked(locked)
    tabComponent.setBusy(locked)
    busy = locked
    windowMethods.setBusy(locked)

    if (!locked) genericLockedUI.resetDefault
  }

  private val extraInfoLabel = new Label {
    extraInfo match {
      case None => {
        text = ""
        visible = false
      }
      case Some(t) => {
        text = t
        visible = true
      }
    }

    foreground = Color.RED
  }

  def currentBookmark = history(current).bookmark
  private val bookmarkButton = new BookmarkButton(currentBookmark, pageContext)
  private val bookmarkDropDownButton = new BookmarkDropDownButton(currentBookmark, pageContext)

  refreshButtonStatus

  private val actionPanel = new MigXPanel("", "[p]0[p][p]0[p][p]") {
    add(backButton)
    add(undoButton)
    add(redoButton)
    add(forwardButton)
    add(stopButton)
    add(homeButton)
    add(refreshButton)
    add(liveUpdateCheckbox, "ay center")
    add(addressBar, "pushx, growx, ay center")
    add(extraInfoLabel, "hidemode 3")
    add(settingsButton)
    add(bookmarkButton, "split 2, gap after 0")
    add(bookmarkDropDownButton, "gap before 0, growy")

    val version = lCache.version
    val (topColour,bottomColour) = version.colour match {
      case None => (GuiUtils.StarlingBrowserTopFadeColour, GuiUtils.StarlingBrowserBottomFadeColour)
      case Some(c) => (c, c)
    }     
    backgroundPainter = new VerticalGradientPaint(topColour, bottomColour)
  }

  peer.add(actionPanel.peer, "pushx, growx, wrap 0")
  peer.add(mainPanelLayer.peer, "pushy, grow")

  def clearCache {
    pageBuilder.clearCaches
    ThreadSafeCachingProxy.clearCache
  }

  private def refreshButtonStatus {
    backButton.enabled = (current > 0)
    undoButton.enabled = (current > 0)
    forwardButton.enabled = (current < (history.size-1))
    redoButton.enabled = (current < (history.size-1))
    settingsButton.enabled = true
    bookmarkButton.enabled = true
    bookmarkDropDownButton.enabled = true
    enableAddressBar(true)
    homeButton.enabled = true
    liveUpdateCheckbox.enabled = true
    stopButton.enabled = false
  }

  def submitYesNo[R](message0:String, description:String, submitRequest:SubmitRequest[R], awaitRefresh:R=>Boolean, onComplete:R => Unit, keepScreenLocked:Boolean) {
    genericLockedUI.setClient(greyClient)
    genericLockedUI.setLocked(true)
    setButtonsEnabled(false)
    def userSelected(b:Boolean) {
      if (b) {
        submit(submitRequest, awaitRefresh, onComplete, keepScreenLocked)
      } else {
        setScreenLocked(false)
        refreshButtonStatus
        refreshButton.enabled = history(current).refreshPage.isDefined
      }
    }
    val messageLength = 70
    val message = if (message0.length() < messageLength) {
      message0 + List.fill(messageLength - message0.length())(" ").mkString
    } else {
      message0.take(messageLength)
    }
    starlingBrowserUI.setYesNoMessage(message, description, b => userSelected(b), windowMethods)
  }

  def setContent(content:Component, cancelAction:Option[()=> Unit]) {
    genericLockedUI.setClient(greyClient)
    genericLockedUI.setLocked(true)
    setButtonsEnabled(false)
    starlingBrowserUI.setContent(content, cancelAction)
  }

  def setError(title:String="Error", error:String="") {
    genericLockedUI.setClient(greyClient)
    genericLockedUI.setLocked(true)
    setButtonsEnabled(false)

    def reset() {
      setScreenLocked(false)
      refreshButtonStatus
      refreshButton.enabled = history(current).refreshPage.isDefined
    }

    starlingBrowserUI.setError(title, error, reset)
  }

  def clearContent {
    starlingBrowserUI.clearContentPanel
    setScreenLocked(false)
    refreshButtonStatus
    refreshButton.enabled = history(current).refreshPage.isDefined
  }

  def setDefaultButton(button:Option[Button]) {windowMethods.setDefaultButton(button)}
  def getDefaultButton = windowMethods.getDefaultButton
  def requestFocusInCurrentPage() {currentComponent.requestFocusInWindow()}

  def submit[R](submitRequest:SubmitRequest[R], awaitRefresh:R=>Boolean, onComplete:R => Unit, keepScreenLocked:Boolean) {
    genericLockedUI.setLocked(true)
    tabComponent.setBusy(true)
    setButtonsEnabled(false)
    threadSequence+=1
    val threadID = threadSequence
    waitingFor += Some(threadID)
    val timer = createTimer(threadID)
    timer.start
    val currentPageInfo = history(current)
    currentPageInfo.autoRefresh=Some(new CountDownLatch(1))
    pageBuilder.submit(submitRequest, history(current).autoRefresh.get, awaitRefresh, (didWait:Boolean, submitResponse:SubmitResponse) => {
      if (!didWait) {
        currentPageInfo.autoRefresh = None
      }
      if (waitingFor.contains(Some(threadID))) {
        waitingFor -= Some(threadID)
        //wait for needsRefresh
        submitResponse match {
          case SuccessSubmitResponse(data) => {
            timer.stop
            onComplete(data.asInstanceOf[R])
            starlingBrowserUI.clearContentPanel
            if (!keepScreenLocked) {
              setScreenLocked(false)
            }
            if (!didWait && !keepScreenLocked) {
              setScreenLocked(false)
              refreshButtonStatus
              refreshButton.enabled = history(current).refreshPage.isDefined
            }
            // This is a hack for the portfolio table at the moment.
            if (didWait && !keepScreenLocked) {
              setScreenLocked(false)
              refreshButtonStatus
              refreshButton.enabled = history(current).refreshPage.isDefined
            }
          }
          case FailureSubmitResponse(t) => {
            timer.stop
            t.printStackTrace
            starlingBrowserUI.setError("There was an error when processing your request", StackTraceToString.string(t), {
              setScreenLocked(false)
              refreshButtonStatus
              refreshButton.enabled = history(current).refreshPage.isDefined
            })
          }
        }
      }
    })
  }

  var busy = false
  private def createTimer(threadID:Int) = new Timer(1000, new ActionListener {
    def actionPerformed(e: ActionEvent) = {
      if (waitingFor.contains(Some(threadID))) {
        greyClient.setLocked(true)
        if (genericLockedUI.client != greyClient) greyClient.startDisplay
        genericLockedUI.setClient(greyClient)
        busy = true
        windowMethods.setBusy(true)
      }
    }
  }) {
    setRepeats(false)
  }

  def goBackToPage(pageInfo:PageInfo, index:Int) {
    val page = pageInfo.page
    val oldCurrent = current
    if (page.text == (history(current).page.text)) {
      // Move back without sliding.
      current = index
      showPage(pageInfo, oldCurrent)
      refreshButtonStatus
    } else {
      setButtonsEnabled(false)
      setTitleText(page.text)
      addressIcon.image = page.icon
      tabComponent.setTextFromPage(page)

      // Slide back.
      genericLockedUI.setLocked(true)
      genericLockedUI.setClient(backSlideClient)
      val width = currentComponent.peer.getWidth
      val image1 = new BufferedImage(width, currentComponent.peer.getHeight, BufferedImage.TYPE_INT_RGB)
      val graphics = image1.getGraphics.asInstanceOf[Graphics2D]
      currentComponent.peer.paint(graphics)
      graphics.dispose

      history(current).image = image1
      current = index

      val image2 = pageInfo.image
      backSlideClient.setImages(image1, image2)
      val animator = new Animator(slideTime)
      animator.setAcceleration(slideAcceleration)
      animator.setDeceleration(slideAcceleration)
      val timingTarget = new TimingTargetAdapter {
        override def timingEvent(fraction: Float) = backSlideClient.setX(fraction * width)

        override def end = {
          backSlideClient.setX(width)
          refreshButtonStatus
          onEDT({
            showPage(pageInfo, oldCurrent)
            setScreenLocked(false)
            backSlideClient.reset
          })
        }
      }
      animator.addTarget(timingTarget)
      animator.start
    }
  }

  def goForwardToPage(pageInfo:PageInfo, index:Int) {
    val page = pageInfo.page
    val oldCurrent = current
    if (page.text == (history(current).page.text)) {
      // Move forward without sliding.
      current = index
      showPage(pageInfo, oldCurrent)
      refreshButtonStatus
    } else {
      setButtonsEnabled(false)
      setTitleText(page.text)
      addressIcon.image = page.icon
      tabComponent.setTextFromPage(page)

      // Slide forward.
      genericLockedUI.setLocked(true)
      genericLockedUI.setClient(forwardSlideClient)
      val width = currentComponent.peer.getWidth
      val image1 = new BufferedImage(width, currentComponent.peer.getHeight, BufferedImage.TYPE_INT_RGB)
      val graphics = image1.getGraphics.asInstanceOf[Graphics2D]
      currentComponent.peer.paint(graphics)
      graphics.dispose

      history(current).image = image1
      current = index

      val image2 = pageInfo.image
      forwardSlideClient.setImages(image1, image2)
      val animator = new Animator(slideTime)
      animator.setAcceleration(slideAcceleration)
      animator.setDeceleration(slideAcceleration)
      val timingTarget = new TimingTargetAdapter {
        override def timingEvent(fraction: Float) = forwardSlideClient.setX(-fraction * width)

        override def end = {
          forwardSlideClient.setX(-width)
          refreshButtonStatus
          onEDT({
            showPage(pageInfo, oldCurrent)
            setScreenLocked(false)
            forwardSlideClient.reset
          })
        }
      }
      animator.addTarget(timingTarget)
      animator.start
    }
  }

  def goTo(page:Page, firstPageInBrowser:Boolean) { goTo(Left(page), firstPageInBrowser) }

  def goTo(pageOrPageBuilder:Either[Page,(StarlingServer=>Page, PartialFunction[Throwable, Unit])], firstPageInBrowser:Boolean) {
    genericLockedUI.setLocked(true)
    tabComponent.setBusy(true)
    setButtonsEnabled(false)
    stopButton.enabled = true
    history.reduceToSize(math.max(0, current+1))
    threadSequence+=1
    val threadID = threadSequence
    waitingFor += Some(threadID)
    val timer = createTimer(threadID)
    timer.start

    def updateTitle(page:Page) {
      onEDT({
        setTitleText(page.text)
        addressIcon.image = page.icon
        tabComponent.setTextFromPage(page)
      })
    }
    def withBuiltPage(page:Page, pageResponse:PageResponse) {
      timer.stop
      stopButton.enabled = false
      if (waitingFor.contains(Some(threadID))) {
        waitingFor -= Some(threadID)
        onEDT({
          def showError(title: String, message: String) {
            starlingBrowserUI.setError(title, message, {
              setScreenLocked(false)
              refreshButtonStatus
              refreshButton.enabled = history(current).refreshPage.isDefined
            })
          }

          // If we have an error and apply special processing here if required, otherwise display page as desired.
          pageResponse match {
            case FailurePageResponse(t:OfflineException) => showError("Cannot Connect to Starling", "Starling is currently offline, please try again later or contact a developer.")
            case FailurePageResponse(t:ClientOfflineException) => showError("Not connected to Starling", "The client can't connect to Starling, trying restarting the client, if that fails contact a developer.")
            case FailurePageResponse(t:ServerUpgradeException) => showError("Starling has been Upgraded", "Starling has been upgraded. Please restart your gui.")
            case FailurePageResponse(t:Exception) => t match {
              case e: UndeclaredThrowableException => {
                e.printStackTrace()
                showError("Error", StackTraceToString.messageAndThenString(e.getUndeclaredThrowable))
              }
              case e => {
                e.printStackTrace()
                showError("Error", StackTraceToString.messageAndThenString(e))
              }
            }
            case SuccessPageResponse(_,bookmark) => {
              // Generate the image here.
              val currentTypeState = currentComponent.getTypeState
              val currentTypeFocusInfo = currentComponent.getTypeFocusInfo
              val width = currentComponent.peer.getWidth
              val height = currentComponent.peer.getHeight
              val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
              val graphics = image.getGraphics.asInstanceOf[Graphics2D]
              currentComponent.peer.paint(graphics)
              graphics.dispose

              if (!history.isEmpty) {
                history(current).image = image
              }

              imageClient.image = image
              genericLockedUI.setClient(imageClient)

              val pageComponent = createPopulatedComponent(page, pageResponse)
              pageComponent.setTypeState(currentTypeState)
              val pageInfo = new PageInfo(page, pageResponse, bookmark, Some(pageComponent), new SoftReference(pageComponent), None, None)
              history.append(pageInfo)
              val oldCurrent = current
              current+=1

              currentComponent.restoreToCorrectViewForBack

              // Do we need to slide?
              if ((history.size > 1) && (history(oldCurrent).page.text != page.text)) {
                showPage(pageInfo, oldCurrent, false, false)
                setTitleText(page.text)
                addressIcon.image = page.icon
                tabComponent.setTextFromPage(page)

                onEDT({
                  val image2 = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                  val graphics = image2.getGraphics
                  pageComponent.peer.paint(graphics)
                  graphics.dispose

                  forwardSlideClient.setImages(image, image2)

                  genericLockedUI.setClient(forwardSlideClient)
                  imageClient.reset


                  val animator = new Animator(slideTime)
                  animator.setAcceleration(slideAcceleration)
                  animator.setDeceleration(slideAcceleration)
                  val timingTarget = new TimingTargetAdapter {
                    override def timingEvent(fraction: Float) = forwardSlideClient.setX(-fraction * width)
                    override def end = {
                      forwardSlideClient.setX(-width)
                      refreshButtonStatus
                      onEDT({
                        bookmarkButton.refresh()
                        setScreenLocked(false)
                        forwardSlideClient.reset
                        sortOutFocus()
                      })
                    }
                  }
                  animator.addTarget(timingTarget)
                  animator.start
                })
              } else {
                onEDT({
                  val shouldDoFocus = if (currentTypeFocusInfo.isDefined) false else true
                  showPage(pageInfo, oldCurrent, shouldDoFocus = shouldDoFocus)
                  onEDT(currentComponent.setTypeFocusInfo(currentTypeFocusInfo))
                  refreshButtonStatus
                  setScreenLocked(false)
                  imageClient.reset
                })
              }
            }
          }
        })
      }
    }
    
    pageOrPageBuilder match {
      case Left(page) => pageBuilder.build(page, (page:Page,pageResponse:PageResponse) => withBuiltPage(page, pageResponse))
      case Right((createPage,onException)) => {
        def unanticipatedException(t:Throwable) = {
          timer.stop
          if (waitingFor.contains(Some(threadID))) {
            waitingFor -= Some(threadID)
          }
          t.printStackTrace
          starlingBrowserUI.setError("There was an error when processing your request", StackTraceToString.string(t), {
            setScreenLocked(false)
            refreshButtonStatus
            refreshButton.enabled = history(current).refreshPage.isDefined
          })
        }
        def resetScreen {
          timer.stop
          if (waitingFor.contains(Some(threadID))) {
            waitingFor -= Some(threadID)
          }
          setScreenLocked(false)
          refreshButtonStatus
          refreshButton.enabled = history(current).refreshPage.isDefined
        }
        pageBuilder.build(unanticipatedException, resetScreen, createPage, onException, withBuiltPage, updateTitle, firstPageInBrowser)
      }
    }
  }

  private var refreshButtonAnimator:Animator = null

  def refresh(lockAndUnlockScreen:Boolean=true, latch:Option[CountDownLatch] = None) {
    if (refreshButtonAnimator != null) refreshButtonAnimator.stop
    refreshButtonAnimator = new Animator(PivotTableView.RefreshFadeTime, new TimingTargetAdapter {
      override def timingEvent(fraction:Float) = {
        refreshButton.fadeColour = new Color(87,206,255,math.round((1.0f-fraction) * 128))
        refreshButton.repaint
      }
    })
    refreshButtonAnimator.start

    val pageInfo = history(current)
    val currentPageComponent = pageInfo.pageComponent.get
    val currentBookmark = pageInfo.bookmark
    val currentState = currentPageComponent.getState
    val currentTypeState = currentPageComponent.getTypeState
    val currentOldPageData = currentPageComponent.getOldPageData
    val currentRefreshState = currentPageComponent.getRefreshState
    val newPage = pageInfo.refreshPage.get
    if (lockAndUnlockScreen) {
      genericLockedUI.setLocked(true)
      tabComponent.setBusy(true)
      setButtonsEnabled(false)
      stopButton.enabled = true
      refreshButtonStatus
    }

    threadSequence+=1
    val threadID = threadSequence
    waitingFor += Some(threadID)
    val timer = createTimer(threadID)
    if (lockAndUnlockScreen) {
      timer.start
    }
    pageBuilder.refresh(newPage, (pageResponse:PageResponse) => {
      timer.stop
      if (waitingFor.contains(Some(threadID))) {
        waitingFor -= Some(threadID)
        // TODO - is this really the thing I want to do if I can't get a bookmark?
        val newBookmark = pageResponse match {
          case SuccessPageResponse(_,b) => b
          case _ => currentBookmark
        }
        val pageComponent = createPopulatedComponent(newPage, pageResponse)
        pageComponent.setState(currentState)
        pageComponent.setTypeState(currentTypeState)
        pageComponent.setOldPageDataOnRefresh(currentOldPageData, currentRefreshState, currentState)
        val pageInfo = new PageInfo(newPage, pageResponse, newBookmark, Some(pageComponent), new SoftReference(pageComponent), currentState, None)
        history(current) = pageInfo
        showPage(pageInfo, current)
        if (lockAndUnlockScreen) {
          setScreenLocked(false)
          refreshButtonStatus
        }
        latch match {
          case Some(l) => l.countDown
          case None =>
        }
      }
    })
  }

  private var focusedComponent:Option[java.awt.Component] = None

  def unselected() {
    focusedComponent = currentFocus
  }

  private def currentFocus = {
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager.getFocusOwner
    if (focusOwner != null) {
      Some(focusOwner)
    } else {
      None
    }
  }

  def selected() {
    focusedComponent.foreach(fc => {
      onEDT(onEDT(fc.requestFocusInWindow()))
    })
  }

  def createPopulatedComponent(page:Page, pageResponse:PageResponse) = {
    pageResponse match {
      case SuccessPageResponse(pageData, bookmark) => {
        try {
          page.createComponent(pageContext, pageData, bookmark, size)
        } catch {
          case t => {
            t.printStackTrace()
            new ExceptionPageComponent("Component creation", t)
          }
        }
      }
      case FailurePageResponse(t) => {
        t.printStackTrace()
        new ExceptionPageComponent("Task execution", t)
      }
    }
  }

  def showPage(pageInfo: PageInfo, indexFrom:Int, buildingPage:Boolean=true, shouldDoFocus:Boolean=true) {
    // Get any state from the component and save it.
    for (pI <- history) {
      if (pI != pageInfo) {
        pI.pageComponent match {
          case None =>
          case Some(c) => {
            c.pageHidden()
            pI.componentState = c.getState
          }
        }
      }
    }

    val page = pageInfo.page
    setTitleText(page.text)
    addressIcon.image = page.icon
    tabComponent.setTextFromPage(page)
    refreshButton.enabled = pageInfo.refreshPage.isDefined
    mainPanel.peer.removeAll()
    LinkHandler.clearLayouts()

    // If we are not caching (for debug reasons), remove the component here so it has to be regenerated.
    if (!componentCaching) {
      pageInfo.pageComponent = None
      pageInfo.pageComponentSoft.clear()
    }

    currentComponent = pageInfo.pageComponent match {
      case Some(pc) => pc
      case None => {
        pageInfo.pageComponentSoft.get match {
          case Some(c) => {
            pageInfo.pageComponent = Some(c)            
            c
          }
          case None => {
            val c = createPopulatedComponent(page, pageInfo.pageResponse)
            pageInfo.pageComponent = Some(c)
            pageInfo.pageComponentSoft = new SoftReference(c)
            c.setState(pageInfo.componentState)
            c
          }
        }
      }
    }
    currentComponent.getBorder match {
      case Some(b) => currentComponent.border = b
      case None =>
    }
    mainPanel.peer.add(currentComponent.peer)
    mainPanel.revalidate()
    mainPanel.repaint()

    if (buildingPage) {
      bookmarkButton.refresh()
    }

    // We don't want to keep components hanging about as they take up a lot of memory.
    for (i <- 0 to (current - componentBufferWindow)) {
      history(i).pageComponent = None
    }
    for (i <- (current + componentBufferWindow) until history.size) {
      history(i).pageComponent = None
    }

    // Let the page know that it has been shown.
    if (shouldDoFocus) {
      onEDT(sortOutFocus())
    }
  }

  private def sortOutFocus() {
    val pageInfo = history(current)
    val compToFocus = (if (pageInfo.componentForFocus.isDefined) {
      pageInfo.componentForFocus
    } else {
      currentComponent.defaultComponentForFocus
    }).getOrElse(currentComponent.peer)

    compToFocus.requestFocusInWindow()
  }
}
//Caches all page data and launches threads calling back with swing thread when completed
class PageBuilder(val pageBuildingContext:PageBuildingContext) {
  val pageDataCache = new scala.collection.mutable.HashMap[Page,PageResponse]
  val threads = Executors.newFixedThreadPool(10, new ThreadFactory {
    def newThread(r:Runnable) = {
      val t = new Thread(r)
      t.setPriority(Thread.currentThread.getPriority - 1)
      t
    }
  })

  def clearCaches() {
    pageDataCache.clear()
    pageToThens = Map()
  }

  def submit[R](submitRequest:SubmitRequest[R], waitFor:CountDownLatch, awaitRefresh:R=>Boolean, then:((Boolean, SubmitResponse) => Unit)) {
    threads.execute(new Runnable() {
      def run() {
        val (submitResponse, didWait) = Log.infoWithTime("Submit request " + submitRequest) {
          try {
            val r = pageBuildingContext.submit(submitRequest)
            val dw = awaitRefresh(r)
            if (dw) waitFor.await
            (SuccessSubmitResponse(r),dw)
          } catch {
            case t => (FailureSubmitResponse(t),false)
          }
        }
        onEDT(then(didWait, submitResponse))
      }
    })
  }

  def buildNoCache(page:Page, then:(PageResponse=>Unit)) {
    threads.execute(new Runnable{
      def run = {
        val pageResponse:PageResponse = Log.infoWithTime("BuildNoCache page " + page.text) {
          try {
            logPageView(page, pageBuildingContext.starlingServer)
            val builtPage = page.build(pageBuildingContext)
            val bookmark = page.bookmark(pageBuildingContext.starlingServer)
            SuccessPageResponse(builtPage, bookmark)
          } catch {
            case t => FailurePageResponse(t)
          }
        }
        onEDT(try {
          then(pageResponse)
        } catch {
          case t => FailurePageResponse(t)
        })
      }
    })
  }

  def refresh(newPage:Page, then:PageResponse=>Unit) {
    threads.execute(new Runnable{
      def run = {
        val pageResponse:PageResponse =
          try {
            logPageView(newPage, pageBuildingContext.starlingServer)
            val builtPage = newPage.build(pageBuildingContext)
            val bookmark = newPage.bookmark(pageBuildingContext.starlingServer)
            SuccessPageResponse(builtPage, bookmark)
          } catch {
            case t => FailurePageResponse(t)
          }
        onEDT(then(pageResponse))
      }
    })
  }

  private var pageToThens:Map[Page, List[(Page,PageResponse) => Unit]] = Map()

  def build(unanticipatedException: Throwable => Unit, resetScreen: =>Unit, createPage:StarlingServer=>Page,
            onException:PartialFunction[Throwable, Unit],then:(Page,PageResponse)=>Unit, thenWithPage:Page => Unit,
            firstPageInBrowser:Boolean) {
    threads.execute(new Runnable() { def run() {
      try {
        val page = createPage(pageBuildingContext.starlingServer)
        // You only want to do this if this is the first page for the tab/window.
        if (firstPageInBrowser) {
          thenWithPage(page)
        }
        build(page, then)
      } catch {
        case t:Throwable => {
          if (onException.isDefinedAt(t)) {
            onEDT({
              resetScreen
              onException(t)
            })
          } else {
            onEDT(unanticipatedException(t))
          }
        }
      }
    }})
  }

  def build(page:Page, then:(Page,PageResponse)=>Unit) {
    if (pageDataCache.contains(page)) {
      then(page,pageDataCache(page))
    } else {
      pageToThens.get(page) match {
        case None => {
          pageToThens += (page -> List(then))          
          threads.execute(new Runnable() { def run() {
            val pageResponse:PageResponse = Log.infoWithTime("Build page " + page.text) {
              try {
                logPageView(page, pageBuildingContext.starlingServer)
                val builtPage = page.build(pageBuildingContext)
                val bookmark = page.bookmark(pageBuildingContext.starlingServer)
                SuccessPageResponse(builtPage, bookmark)
              } catch {
                case t:TooLongFrameException => FailurePageResponse(new Exception("Too much data, try refining pivot view", t))
                case t => FailurePageResponse(t)
              }
            }
            onEDT({
              if(pageResponse.isInstanceOf[SuccessPageResponse])
                pageDataCache(page) = pageResponse
              pageToThens(page).foreach(_(page,pageResponse))
              pageToThens -= page
            })
          }})
        }
        case Some(list) => {
          pageToThens -= page
          pageToThens += (page -> (then :: list))
        }
      }
    }
  }
  def readCached(page:Page) = pageDataCache(page)
}

class PageResponse
case class SuccessPageResponse(data:PageData, bookmark:Bookmark) extends PageResponse
case class FailurePageResponse(t:Throwable) extends PageResponse
case object NullPageResponse extends PageResponse

class SubmitResponse
case class SuccessSubmitResponse[R](data:R) extends SubmitResponse
case class FailureSubmitResponse(t:Throwable) extends SubmitResponse

class NullPageComponent extends FlowPanel with PageComponent {
  contents += new Label("Loading...")
  // This is used as the initial size if the user has no settings. It is also used when splitting tabs.
  preferredSize = new Dimension(1250, 750)
}

class NavigationButton extends Button {
  focusable = false
  background = GuiUtils.ClearColour
  opaque = false
}

class ExceptionPageComponent(errorType:String, t:Throwable)  extends MigPanel("") with PageComponent {
  private val stackTraceComponent = new ScrollPane(new TextArea() {
    text = errorType + "\n\n" + StackTraceToString.string(t)
    editable = false
    wordWrap = true
  })
  add(new Label() {
    icon = StarlingIcons.icon("/icons/22x22/emblems/emblem-important.png")
  })
  add(new Label("The previous action failed. Please contact a developer."), "wrap")
  add(stackTraceComponent, "skip 1, push, grow")
  onEDT(stackTraceComponent.peer.getViewport.setViewPosition(new Point(0,0)))
}