package io.coral.actors.transform

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.Timeout
import io.coral.actors.CoralActorFactory
import io.coral.actors.Messages.{GetFieldBy, GetField}
import io.coral.api.DefaultModule
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.duration._

class ZscoreActorSpec(_system: ActorSystem)
  extends TestKit(_system)
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  implicit val injector = new DefaultModule(system.settings.config)

  def this() = this(ActorSystem("ZscoreActorSpec"))

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(1000.millis)
  implicit val formats = org.json4s.DefaultFormats

  class MockStatsActor(var count: Long, var avg: Double, var sd: Double) extends Actor {
    def receive = {
      case GetFieldBy("count", "") => sender ! render(count)
      case GetFieldBy("avg", "") => sender ! render(avg)
      case GetFieldBy("sd", "") => sender ! render(sd)
      case GetFieldBy(other, "") => throw new UnsupportedOperationException(other.toString)
    }
  }

  def createMockStats(name: String, count: Long, avg: Double, sd: Double): MockStatsActor = {
    val ref = TestActorRef[MockStatsActor](Props(new MockStatsActor(count, avg, sd)), name)
    ref.underlyingActor
  }

  def createZscoreActor(n: Int, by: String, field: String, score: Double): ZscoreActor = {
    val createJson = parse(
      s"""{ "type": "actors",
         |"attributes": {"type": "zscore",
         |"params": { "by": "${by}",
         |"field": "${field}",
         |"score": ${score} } } }""".stripMargin)
      .asInstanceOf[JObject]
    val props = CoralActorFactory.getProps(createJson).get
    val actorRef = TestActorRef[ZscoreActor](props, s"${n}")
    actorRef.underlyingActor
  }

  "ZscoreActor" should {

    "obtain correct values from create json" in {
      val actor = createZscoreActor(1, "field1", "field2", 6.1)
      actor.by should be("field1")
      actor.field should be("field2")
      actor.score should be(6.1)
    }

    // this should be better separated, even if only from a unit testing point of view
    "emit only when outlier is true" in {
      val zscore = createZscoreActor(4, by = "", field = "val", score = 6.1)
      val mockStats = createMockStats("mock1", count = 20L, avg = 3.0, sd = 2.0)
      zscore.collectSources = Map("stats" -> "/user/mock1")
      val future1 = zscore.trigger(parse( s"""{ "dummy": "", "val": 50.0 }""").asInstanceOf[JObject])
      val result1 = Await.result(future1, timeout.duration)
      assert(result1 == Some(JNothing))

      mockStats.count = 21L // count > 20 before considering outlyer
      val future2 = zscore.trigger(parse( s"""{ "dummy": "", "val": 4.0 }""").asInstanceOf[JObject])
      val result2 = Await.result(future2, timeout.duration)
      assert(result2 == Some(JNothing))

      val future3 = zscore.trigger(parse( s"""{ "dummy": "", "val": 50.0 }""").asInstanceOf[JObject])
      val result3 = Await.result(future3, timeout.duration)
      assert(result3 == Some(parse("""{ "dummy": "", "val": 50.0, "outlier": true}""")))
    }
  }

}
