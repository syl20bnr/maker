package starling.services.rpc.valuation

import starling.market.{MarketProvider, TestMarketLookup}
import starling.services.rpc.refdata.FileMockedTitanServices
import starling.services.rabbit.MockTitanRabbitEventServices
import com.trafigura.common.control.PipedControl._
import org.codehaus.jettison.json.JSONArray
import com.trafigura.events.{DemultiplexerClient, EventFactory, PayloadFactory}
import com.trafigura.shared.events.Event._
import org.testng.Assert._
import com.trafigura.edm.trades.{PhysicalTrade => EDMPhysicalTrade}
import com.trafigura.shared.events._
import starling.services.rpc.logistics.FileMockedTitanLogisticsServices
import com.trafigura.edm.logistics.inventory.EDMInventoryItem
import starling.utils.{Stopwatch, StarlingTest, Log}
import org.testng.annotations.{BeforeClass, Test}


/**
 * Valuation service tests
 */
class ValuationServiceTest extends StarlingTest {

  var testMarketLookup : TestMarketLookup = null
  var mockTitanServices : FileMockedTitanServices = null
  var mockTitanTradeService : DefaultTitanTradeService = null
  var mockTitanTradeCache : TitanTradeServiceBasedTradeCache = null
  var mockTitanLogisticsServices : FileMockedTitanLogisticsServices = null
  var mockRabbitEventServices : MockTitanRabbitEventServices = null
  var mockInventoryCache : TitanLogisticsServiceBasedInventoryCache = null
  
  @BeforeClass
  def initMocks() {
    val stopwatch = new Stopwatch()
    println("Starting valuation service tests - initialisation of mock data...")

    testMarketLookup = new TestMarketLookup()
    MarketProvider.registerNewImplForTesting(Some(testMarketLookup))
    mockTitanServices = new FileMockedTitanServices()
    mockTitanTradeService = new DefaultTitanTradeService(mockTitanServices)
    mockTitanTradeCache = new TitanTradeServiceBasedTradeCache(mockTitanTradeService)
    mockTitanLogisticsServices = FileMockedTitanLogisticsServices()
    mockRabbitEventServices = new MockTitanRabbitEventServices()
    mockInventoryCache = new TitanLogisticsServiceBasedInventoryCache(mockTitanLogisticsServices)
    println("Took " + stopwatch)
  }
  
  /**
   * valuation mock event handler for valuation service published events
   */
  class MockEventHandler(handler : (List[String]) => Unit) extends DemultiplexerClient {
    def handle(ev: Event) {
      if (ev == null) Log.warn("Got a null event")
      else {
        // Must be a starling valuation update event from valuation service
        if (StarlingSource == ev.source && StarlingValuationServiceSubject == ev.subject) {

          Log.info("handler: recieved a starling valuation service event to process %s".format(ev.toString))

          val payloads = ev.content.body.payloads.filter(p => Event.RefinedMetalTradeIdPayload == p.payloadType || Event.EDMLogisticsInventoryIdPayload == p.payloadType)
          val ids = payloads.map(p => p.key.identifier)
          Log.info("Trade event received for ids { %s }".format(ids.mkString(", ")))

          ev.verb match {
            case UpdatedEventVerb => {
              println("calling handler with %s".format(ids.mkString(", ")))
              handler(ids)
            }
            case CreatedEventVerb => {
              Log.info("new event received")
            }
            case CancelledEventVerb | RemovedEventVerb => {
              Log.info("cancel / remove event received")
            }
          }
        }
      }
    }
  }

  @Test(enabled=true, groups = Array("ValuationService"))
  def testValuationServiceValuationUpdatedEvents() {

    Log.info("testValuationServiceValuationUpdatedEvents starting...")

    val sw = new Stopwatch()

    var updatedValuationIdList : List[String] = Nil
    val handler = (ids : List[String]) => updatedValuationIdList = ids
    
    val vs = new ValuationService(
      new MockEnvironmentProvider, mockTitanTradeCache, mockTitanServices, mockTitanLogisticsServices, mockRabbitEventServices, mockInventoryCache)

    println("Running valuation service tests")

    //vs.marketDataSnapshotIDs().foreach(println)
    val valuations = vs.valueAllQuotas()

    val (worked, _) = valuations.tradeResults.values.partition(_ isRight)
    val valuedTradeIds = valuations.tradeResults.collect {
      case (id, Right(v)) => id
    }.toList
    val valuedTrades = vs.getTrades(valuedTradeIds)

    // select a trade for testing, take the first trade that was successfully valued as this
    // should ensure that it can be used reliably in this test (some trades may not have completed pricing spec information)
    val firstTrade = valuedTrades.head // mockTitanTradeService.getAllTrades().head // valuedTrades.head
    
    val testEventHandler = new MockEventHandler(handler)

    mockRabbitEventServices.eventDemux.addClient(testEventHandler)

    /**
     * Test that no changes to trade value does not cause a valuation update event when a trade updated event is received
     */
    
    // publish trade updated events...
    val eventArray = createTradeUpdatedEvent(firstTrade.oid.toString)

    // publish our change event
    updatedValuationIdList = Nil
    mockRabbitEventServices.rabbitEventPublisher.publish(eventArray)

    // check the updated valuation event is sent...
    Log.info("updatedTradeValuationList " + updatedValuationIdList.mkString(", "))

    assertTrue(!updatedValuationIdList.contains(firstTrade.oid.toString), "Valuation service raised valuation changed events for unchanged trades")

    /**
     * Test changing a trade value causes valuation update events for those trades
     */
    val updatedTrade = EDMPhysicalTrade.fromJson(firstTrade.toJson())
    updatedTrade.direction = if (updatedTrade.direction == "P") "S" else "P"  // make a change to cause a valuation to change value and cause a valuation updated event

    //updatedTrade.quotas.map(q => q.detail.pricingSpec.quantity.amount = Some(q.detail.pricingSpec.quantity.amount.getOrElse(0.0) + 1.0) )

    // update the underlying dataset
    mockTitanServices.updateTrade(updatedTrade)

    // publish our change event
    updatedValuationIdList = Nil
    mockRabbitEventServices.rabbitEventPublisher.publish(eventArray)

    // check the updated valuation event is sent...
    Log.info("updatedTradeValuationList " + updatedValuationIdList.mkString(", "))

    assertTrue(updatedValuationIdList.contains(updatedTrade.oid.toString), "Valuation service failed to raise valuation changed events for the changed trades")

    Log.info("completed test in " + sw)
  }


  /**
   * very basic tests to check we can value some assignments
   *   will need improvements and to also test events
   */
  @Test(enabled=true, groups = Array("ValuationService"))
  def testValuationServiceValueAssignments {
    
    Log.info("testValuationServiceValueAssignments starting...")

    val sw = new Stopwatch()

    var updatedValuationIdList : List[String] = Nil
    val handler = (ids : List[String]) => {
      println("handler received " + ids.mkString(", "))
      updatedValuationIdList = ids
    }

    //val salesAssignments = mockTitanLogisticsServices.assignmentService.service.getAllSalesAssignments()
    val assignments = mockTitanLogisticsServices.assignmentService.service.getAllSalesAssignments()
    val inventory = mockTitanLogisticsServices.inventoryService.service.getAllInventoryLeaves()
    //println("assignments " + assignments.mkString(", "))
//    val inventory = mockTitanLogisticsServices.inventoryService.service.getInventoryTreeByPurchaseQuotaId()
//    println("inventory " + inventory.mkString(", "))

    val vs = new ValuationService(
      new MockEnvironmentProvider, mockTitanTradeCache, mockTitanServices, mockTitanLogisticsServices, mockRabbitEventServices, mockInventoryCache)

    println("Running valuation service assignment tests")

    //vs.marketDataSnapshotIDs().foreach(println)
    val assignmentValuations = vs.valueAllAssignments()

    println("Valued assignments")

    val (worked, failed) = assignmentValuations.assignmentValuationResults.values.partition(_ isRight)
    val valuedIds = assignmentValuations.assignmentValuationResults.collect {
      case (id, Right(v)) => id
    }.toList

    println("Valued inventory assignments, %d worked, %d failed".format(worked.size, failed.size))

    assertTrue(worked.size > 0, "Assignment valuation service failed to value any assignments")

    val valuedInventoryAssignments = mockInventoryCache.getInventoryByIds(valuedIds)

    val inventoryWithSalesAssignments = mockInventoryCache.getAllInventory().filter(i => i.salesAssignment != null)
    //println("inventory with sales assignment " + inventoryWithSalesAssignments.mkString(",\n"))

    val inventoryWithSalesAssignmentValuationResults = assignmentValuations.assignmentValuationResults.filter(v => inventoryWithSalesAssignments.exists(e => e.oid.contents.toString == v._1))
    //println("\n%s\n".format(inventoryWithSalesAssignmentValuationResults.mkString("\n")))
    val firstInventoryItem = valuedInventoryAssignments.find(i => i.salesAssignment != null).get // if we've no valid canned data for tests this has to fail

    val testEventHandler = new MockEventHandler(handler)

    mockRabbitEventServices.eventDemux.addClient(testEventHandler)

    /**
     * Test that no changes to trade value does not cause a valuation update event when a trade updated event is received
     */

    // publish trade updated events...
    val eventArray = createInventoryUpdatedIDEvents(firstInventoryItem.oid.contents.toString :: Nil)

    // publish our change event
    updatedValuationIdList = Nil
    mockRabbitEventServices.rabbitEventPublisher.publish(eventArray)

    assertTrue(updatedValuationIdList.size == 0, "Valuation service raised valuation changed events for unchanged inventory")

    val updatedInventory = EDMInventoryItem.fromJson(firstInventoryItem.toJson())

    println("updatedInventory before update : " + updatedInventory)
    updatedInventory.salesAssignment = null
    println("updatedInventory after update  : " + updatedInventory)

    // update the underlying dataset
    mockTitanLogisticsServices.inventoryService.updateInventory(updatedInventory)

    // publish our change event
    updatedValuationIdList = Nil
    mockRabbitEventServices.rabbitEventPublisher.publish(eventArray)

    // check the updated valuation event is sent...
    println("updatedValuationList " + updatedValuationIdList.mkString(", "))

    assertTrue(updatedValuationIdList.contains(updatedInventory.oid.contents.toString), "Valuation service failed to raise valuation changed events for the changed assignments")

    Log.info("completed test in " + sw)
  }

  private def createTradeUpdatedEvent(id : String) : JSONArray = createTradeUpdatedEvent(List(id))
  private def createTradeUpdatedEvent(ids : List[String]) : JSONArray =
    createTradeMgmtIDEvents(ids)

  private def createTradeMgmtIDEvents(ids : List[String], source : String = TrademgmtSource : String, subject : String = TradeSubject, verb : EventVerbEnum = UpdatedEventVerb) : JSONArray = {
    val pf = new PayloadFactory()
    val payloads = ids.map(id => pf.createPayload(RefinedMetalTradeIdPayload, source, id))
    createEvents(source, subject, verb, payloads)
  }

  private def createInventoryUpdatedIDEvents(ids : List[String], source : String = LogisticsSource : String, subject : String = EDMLogisticsInventorySubject, verb : EventVerbEnum = UpdatedEventVerb) : JSONArray = {
    val pf = new PayloadFactory()
    val payloads = ids.map(id => pf.createPayload(InventoryIdPayload, source, id))
    createEvents(source, subject, verb, payloads)
  }

  private def createEvents(source : String, subject : String, verb : EventVerbEnum, payloads : List[Payload]) : JSONArray = {
    val ef = new EventFactory()
    val keyIdentifier = System.currentTimeMillis.toString
    val ev = ef.createEvent(subject, verb, source, keyIdentifier, payloads)
    ||> { new JSONArray } { r => r.put(ev.toJson) }
  }

  def main(args : Array[String]) {
    testValuationServiceValuationUpdatedEvents
  }
}