package starling.gui.pages

import starling.pivot.view.swing.MigPanel
import swing.event.{SelectionChanged, ButtonClicked}
import java.awt.Dimension
import swing._
import javax.swing.DefaultComboBoxModel
import swing.Swing._
import starling.pivot.{PivotFieldsState, PivotLayout}
import starling.gui._
import api.PivotLayoutUpdate

class SaveLayoutPanel(pageContext:PageContext, pageData:PivotTablePageData, pivotPageState:PivotPageState, layoutType:String,
                      selfPage:(PivotPageState)=>Page, selectFocus: =>Unit, bookmark:Bookmark) extends MigPanel("insets 0, gap 1") {
  val data = pageData.pivotData
  val blankLayout = PivotLayout("Blank", PivotFieldsState(reportSpecificChoices = data.pivotFieldsState.reportSpecificChoices),
    false, pivotPageState.otherLayoutInfo, layoutType, List())

  def layouts = {
    val userLayouts = (blankLayout :: pageContext.localCache.userPivotLayouts).filter(_.layoutType == layoutType)
    userLayouts.filter(layout => {
      val allFieldsUsedInLayout = layout.pivotFieldState.allFieldsUsed.toSet
      val allFieldsAreValid = allFieldsUsedInLayout == data.allFields.toSet.intersect(allFieldsUsedInLayout)
      val savedReportChoices = layout.pivotFieldState.reportSpecificChoices.keySet
      val availableReportChoices = data.reportSpecificOptions.map(_._1).toSet
      val allReportChoicesAreValid = savedReportChoices.forall(availableReportChoices.contains)
      allFieldsAreValid && allReportChoicesAreValid
    })
  }

  def allUserLayouts = blankLayout :: pageContext.localCache.userPivotLayouts

  def checkCustomLayout = {
    !layouts.map(l => {(l.pivotFieldState, l.otherLayoutInfo)}).contains((data.pivotFieldsState, pivotPageState.otherLayoutInfo))
  }

  def selectedItem = {
    generateAllLayouts.find(l => {
      ((l.pivotFieldState == data.pivotFieldsState) && (l.otherLayoutInfo == pivotPageState.otherLayoutInfo))}).get
  }

  var customLayout = checkCustomLayout
  def generateAllLayouts = {
    val l = layouts
    if (customLayout) {
      PivotLayout("Custom", data.pivotFieldsState, true, pivotPageState.otherLayoutInfo, layoutType, List()) :: l
    } else {
      l
    }
  }
  val allLayouts = generateAllLayouts
  val layoutChooserModel = new DefaultComboBoxModel(allLayouts.toArray.asInstanceOf[Array[Object]])
  val layoutSelector = new ComboBox(allLayouts) {
    peer.setModel(layoutChooserModel)
    renderer = ListView.Renderer(_.layoutName)
    focusable = false
    selection.item = selectedItem
  }
  val saveOrDeleteButton = new ToolBarButton

  def refresh = {
    if (customLayout) {
      saveOrDeleteButton.icon = StarlingIcons.SaveLayout
      saveOrDeleteButton.tooltip = "Save the current layout and associate it with this report (if any)"
      saveOrDeleteButton.enabled = true
    } else {
      val currentLayout = selectedItem
      saveOrDeleteButton.icon = StarlingIcons.icon("/icons/16x16_layout_delete.png")
      saveOrDeleteButton.tooltip = "Delete the current layout"
      saveOrDeleteButton.enabled = currentLayout.userLayout
    }
  }

  def reverse {
    deafTo(layoutSelector.selection)
    layoutSelector.selection.item = selectedItem
    listenTo(layoutSelector.selection)
  }

  refresh

  add(layoutSelector, "growy")
  add(saveOrDeleteButton)

  reactions += {
    case ButtonClicked(b) => {
      if (customLayout) {
        val oldDefaultButton = pageContext.getDefaultButton
        savePanel.oldDefaultButton = oldDefaultButton
        setSavePanel(false)
        pageContext.setContent(holderPanel, Some(savePanel.clearUp))
        pageContext.setDefaultButton(Some(savePanel.okButton))
        savePanel.nameField.requestFocusInWindow()
      } else {
        pageContext.submitYesNo(
          "Delete layout?",
          "Are you sure you want to delete the \"" + layoutSelector.selection.item.layoutName + "\" layout?",
          DeletePivotLayoutRequest(layoutSelector.selection.item.layoutName), (u:Unit) => {false}, (u:Unit) => {onEDT(selectFocus)})
      }      
    }
    case SelectionChanged(`layoutSelector`) => {
      pageContext.goTo(selfPage(pivotPageState.copyLayout(layoutSelector.selection.item)))
    }
    case PivotLayoutUpdate(user, userLayouts) if (user == pageContext.localCache.currentUser.username) => {
      customLayout = checkCustomLayout
      deafTo(layoutSelector.selection)
      layoutChooserModel.removeAllElements()
      generateAllLayouts.foreach(layoutChooserModel.addElement(_))
      layoutSelector.selection.item = selectedItem
      listenTo(layoutSelector.selection)
      refresh
    }
  }
  listenTo(saveOrDeleteButton, layoutSelector.selection, pageContext.remotePublisher)

  class SaveLayoutHolderPanel extends MigPanel("insets 0") {
    def update(c:Component, setSize:Boolean) {
      removeAll
      if (setSize) {
        val widthToUse = math.max(c.preferredSize.width, size.width)
        val heightToUse = math.max(c.preferredSize.height, size.height)
        c.preferredSize = new Dimension(widthToUse, heightToUse)
      }
      add(c, "push,grow")
      revalidate
      repaint
    }
  }

  val holderPanel = new SaveLayoutHolderPanel

  def setSavePanel(setup:Boolean) {
    holderPanel.update(savePanel, setup)
    if (setup) {
      savePanel.nameField.requestFocusInWindow
      savePanel.nameField.selectAll
      pageContext.setDefaultButton(Some(savePanel.okButton))
    }
  }

  def getText:String = savePanel.nameField.text
  def clearUp:Unit = savePanel.clearUp

  val replacePanel = new MigPanel {
    border = LineBorder(new Color(158,16,40), 2)
    private val questionIcon = new Label {
      icon = StarlingIcons.icon("/icons/128x128_question.png")
    }
    val label = new Label("A layout already exists with that name") {
      horizontalAlignment = Alignment.Left
      font = font.deriveFont(java.awt.Font.BOLD)
    }
    val standardText = "Would you like to replace it?"
    val textArea = starling.gui.GuiUtils.LabelTextArea(standardText)
    val yesButton = new Button {
      text = "Yes"
      reactions += {
        case ButtonClicked(e) => {
          def saveLayout(a:Unit) {
            pageContext.submit(SavePivotLayoutRequest(PivotLayout(getText.trim, data.pivotFieldsState, true,
              pivotPageState.otherLayoutInfo, layoutType, Nil)))
            clearUp
          }
          pageContext.submit(DeletePivotLayoutRequest(getText.trim), saveLayout, keepScreenLocked = true)
        }
      }
    }
    val noButton = new Button {
      text = "No"
      reactions += {
        case ButtonClicked(e) => {setSavePanel(true)}
      }
    }

    add(questionIcon, "spany")
    add(label, "pushx, growx, wrap unrel, w " + label.preferredSize.width)
    add(textArea, "push, grow, wrap unrel")
    add(yesButton, "skip 1, split, al right, sg button")
    add(noButton, "al right, sg button")
  }

  val savePanel = new MigPanel {
    var oldDefaultButton:Option[Button] = None

    val nameField = new TextField(20)
    val infoIcon = new Label {
      icon = StarlingIcons.icon("/icons/128x128_info.png")
    }
    border = LineBorder(new Color(158,16,40), 2)
    val label = new Label("Please Enter the Layout Details") {
      font = font.deriveFont(java.awt.Font.BOLD)
    }
    val nameLabel = new Label("Layout Name:") {
      peer.setDisplayedMnemonic(java.awt.event.KeyEvent.VK_L)
    }
    nameLabel.peer.setLabelFor(nameField.peer)
    val okButton = new Button {
      text = "OK"
      reactions += {
        case ButtonClicked(e) => {
          val newLayoutName = nameField.text
          if (newLayoutName.nonEmpty) {
            val currentLayouts = allUserLayouts.map(_.layoutName.trim)
            if (!currentLayouts.contains(newLayoutName.trim)) {
              pageContext.submit(SavePivotLayoutRequest(PivotLayout(newLayoutName.trim, data.pivotFieldsState, true,
                pivotPageState.otherLayoutInfo, layoutType, Nil)))
              clearUp()
            } else {
              replacePanel.textArea.text = replacePanel.standardText
              holderPanel.update(replacePanel, true)
              pageContext.setDefaultButton(Some(replacePanel.yesButton))
              replacePanel.yesButton.requestFocusInWindow()
            }
          }
        }
      }
    }
    val cancelButton = new Button {
      text = "Cancel"
      reactions += {
        case ButtonClicked(e) => {clearUp()}
      }
    }
    add(infoIcon, "spany")
    add(label, "spanx, wrap unrel")
    add(nameLabel)
    add(nameField, "wrap unrel")
    add(okButton, "split, spanx, al right bottom, sg button")
    add(cancelButton, "al right bottom, sg button")

    def clearUp() {
      pageContext.clearContent()
      pageContext.setDefaultButton(oldDefaultButton)
      // Give the pivot component focus so that you can do things like ctrl t and ctrl f4.
      selectFocus
    }
  }
}