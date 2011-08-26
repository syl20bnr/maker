package starling.services.trinity

import collection.immutable.List
import java.io.{PrintWriter, FileWriter}

import starling.daterange._
import starling.gui.api._
import starling.props.Props
import starling.utils._

import starling.utils.ClosureUtil._
import starling.utils.ImplicitConversions._
import com.trafigura.services.trinity.TrinityService

class TrinityUploader(fclGenerator: FCLGenerator, xrtGenerator: XRTGenerator, trinityService: TrinityService, props: Props) {
  def uploadCurve(label: CurveLabel) = {
    val toUpload = fclGenerator.generate(label)

    if (toUpload.isEmpty) Log.info("No Trinity data to upload")

    toUpload.map { case (trinityKey, commodityRates) => {
      Log.info("Uploading " + trinityKey)
      commodityRates.foreach(println)
      trinityService.commodityRates.putRates(trinityKey.exchange, trinityKey.commodity, trinityKey.currency, "Full Curve", commodityRates.toList)
    } }
  }
  def uploadLibor(observationDay: Day) = upload(xrtGenerator.generate(observationDay), "libor%s.xrt" % observationDay)

  private def upload(lines: List[String], fileName: String) {
    Log.debug("Uploading trinity file: %s/%s (lines: %d)" % (props.TrinityUploadDirectory(), "starling-" + fileName, lines.size))

    using(new FileWriter(props.TrinityUploadDirectory() + "starling-" + fileName, true)) { fileWriter =>
      using(new PrintWriter(fileWriter)) { printWriter => printWriter.println(lines.mkString("\n")) }
    }
  }
}