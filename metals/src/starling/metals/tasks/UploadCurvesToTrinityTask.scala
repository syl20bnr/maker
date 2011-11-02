package starling.metals.tasks

import starling.daterange.Day
import starling.gui.api._
import starling.curves.ClosesEnvironmentRule
import starling.scheduler.ScheduledTask


class UploadCurvesToTrinityTask(uploader: TrinityUploader, marketDataIdentifier: => MarketDataIdentifier) extends ScheduledTask {
  def execute(observationDay: Day) = {
    uploader.uploadCurve(CurveLabel(CurveTypeLabel("Price"), marketDataIdentifier,
      EnvironmentSpecificationLabel(observationDay, ClosesEnvironmentRule.label())))
  }
}