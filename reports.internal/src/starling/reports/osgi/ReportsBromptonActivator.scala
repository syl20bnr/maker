package starling.reports.osgi

import starling.reports.pivot.{ReportServiceInternal, ReportContextBuilder}
import starling.tradestore.TradeStores
import starling.calendar.BusinessCalendars
import starling.reports.ReportService
import starling.rmi.{MarketDataPageIdentifierReaderProvider, UserSettingsDatabase}
import starling.gui.api.{ReportMarketDataPageIdentifier, MarketDataPageIdentifier}
import starling.reports.internal.{ReportHandler, ReportServiceImpl, UserReportsService}
import starling.db.{DB, MarketDataStore}
import starling.manager.{ExportGuiRMIProperty, ExportExcelProperty, BromptonContext, BromptonActivator}

class ReportsBromptonActivator extends BromptonActivator {

  def start(context: BromptonContext) {
    val props = context.awaitService(classOf[starling.props.Props])
    val marketDataStore = context.awaitService(classOf[MarketDataStore])
    val tradeStores = context.awaitService(classOf[TradeStores])
    val businessCalendars = context.awaitService(classOf[BusinessCalendars])
    val userSettingsDatabase = context.awaitService(classOf[UserSettingsDatabase])
    val eaiStarlingDB = DB(props.StarlingDatabase())

    val reportContextBuilder = new ReportContextBuilder(marketDataStore)
    val reportServiceInternal = new ReportServiceInternal(reportContextBuilder,tradeStores)
    val userReportsService = new UserReportsService(businessCalendars.UK, tradeStores, marketDataStore, userSettingsDatabase, reportServiceInternal)

    val reportService = new ReportServiceImpl(reportServiceInternal, userReportsService, tradeStores, userSettingsDatabase, eaiStarlingDB)

    val reportRecordingMarketDataReaderProvider = new MarketDataPageIdentifierReaderProvider() {
      def readerFor(identifier: MarketDataPageIdentifier) = {
        identifier match {
          case ReportMarketDataPageIdentifier(rp) => Some(reportServiceInternal.recordedMarketDataReader(rp))
          case _ => None
        }
      }
    }

    context.registerService(classOf[AnyRef], new ReportHandler(userReportsService), ExportExcelProperty::Nil)
    context.registerService(classOf[ReportService], reportService, ExportGuiRMIProperty::Nil)
    context.registerService(classOf[MarketDataPageIdentifierReaderProvider], reportRecordingMarketDataReaderProvider)
  }

  def stop(context: BromptonContext) {}
}