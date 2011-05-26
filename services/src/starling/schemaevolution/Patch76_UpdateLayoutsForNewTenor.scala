package starling.schemaevolution

import system.Patch
import starling.db.DBWriter
import starling.richdb.RichDB
import starling.services.StarlingInit

class Patch76_UpdateLayoutsForNewTenor extends Patch {
  def patchDescription = "Updates the Tenor in the report specific choices in saved layouts"

  protected def runPatch(starlingInit: StarlingInit, starling: RichDB, writer: DBWriter) = {
    val sql = "select layout from PivotLayouts"
    writer.queryForUpdate(sql) {
      rs => {
        val layout = rs.getString("layout")

        val startIndex = layout.indexOf("<reportSpecificChoices ")
        val endIndex = layout.indexOf("</reportSpecificChoices>", startIndex)
        val newLayout = if (startIndex != -1 && endIndex != -1){
          val reportSpecificChoicesText = layout.substring(startIndex, endIndex)

          val tStartIndex = reportSpecificChoicesText.indexOf("<string>Tenor</string>")
          val tEndIndex = reportSpecificChoicesText.indexOf("</entry>", tStartIndex)
          if (tStartIndex != -1 && tEndIndex != -1){
            val tenorText = reportSpecificChoicesText.substring(tStartIndex, tEndIndex)

            val updatedTenorText = tenorText.replace("Month", "M").replace("Day", "D").replace("Week", "W")

            val updatedReportSpecificChoicesText = reportSpecificChoicesText.replace(tenorText, updatedTenorText)

            layout.replace(reportSpecificChoicesText, updatedReportSpecificChoicesText)
          } else {
            layout
          }
        } else {
          layout
        }
        rs.update(Map("layout" -> newLayout))

      }
    }
  }
}