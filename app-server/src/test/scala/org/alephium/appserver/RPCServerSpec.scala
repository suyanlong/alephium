package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.{MethodRejection, Route, UnsupportedRequestContentTypeRejection}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Json}
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues}

import org.alephium.appserver.RPCModel._
import org.alephium.flow.client.Miner
import org.alephium.flow.platform.PlatformProfile
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{AlephiumSpec, AVector, EventBus}

object RPCServerSpec {
  import RPCServer._

  case object Dummy extends EventBus.Event

  class RPCServerDummy(implicit val config: PlatformProfile) extends RPCServerAbstract {
    implicit val system: ActorSystem                = ActorSystem()
    implicit val materializer: ActorMaterializer    = ActorMaterializer()
    implicit val executionContext: ExecutionContext = system.dispatcher
    implicit val rpcConfig: RPCConfig               = RPCConfig.load(config.aleph)
    implicit val askTimeout: Timeout                = Timeout(rpcConfig.askTimeout.asScala)

    def successful[T](t: T): FutureTry[T] = Future.successful(Right(t))

    val dummyFetchResponse  = FetchResponse(Seq.empty)
    val dummyPeers          = PeersResult(AVector.empty)
    val dummyBalance        = Balance(1, 1)
    val dummyTransferResult = TransferResult("foobar")

    def doBlockflowFetch(req: Request): FutureTry[FetchResponse] = successful(dummyFetchResponse)
    def doGetPeers(req: Request): FutureTry[PeersResult]         = successful(dummyPeers)
    def doGetBalance(req: Request): FutureTry[Balance]           = successful(dummyBalance)
    def doTransfer(req: Request): FutureTry[TransferResult]      = successful(dummyTransferResult)

    def runServer(): Future[Unit] = Future.successful(())
  }
}

class RPCServerSpec extends AlephiumSpec with ScalatestRouteTest with EitherValues { Spec =>
  import RPCServerSpec._

  behavior of "RPCServer"

  implicit val config: PlatformProfile = PlatformProfile.loadDefault()

  val rpcSuccess = Response.Success(Json.fromInt(42), 1)

  trait RouteHTTP {
    implicit lazy val askTimeout = Timeout(server.rpcConfig.askTimeout.asScala)

    lazy val server: RPCServerDummy = new RPCServerDummy {}
    lazy val route: Route           = server.routeHttp(TestProbe().ref)

    def checkCall[T](method: String)(f: Response.Success => T): T = {
      rpcRequest(method, Json.obj(), 0) ~> route ~> check {
        status.intValue is 200
        f(responseAs[Response.Success])
      }
    }

    def checkCallResult[T: Decoder](method: String)(expected: T): Assertion =
      checkCall(method)(json => json.result.as[T].right.value is expected)

    def rpcRequest(method: String, params: Json, id: Long): HttpRequest = {
      val jsonRequest = Request(method, Some(params), id).asJson.noSpaces
      HttpRequest(HttpMethods.POST,
                  "/",
                  entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))
    }
  }

  trait MiningMock extends RouteHTTP {
    val miner                      = TestProbe()
    override lazy val route: Route = server.routeHttp(miner.ref)
  }

  trait RouteWS {
    val client   = WSProbe()
    val server   = new RPCServerDummy {}
    val eventBus = system.actorOf(EventBus.props())
    val route    = server.routeWs(eventBus)

    def sendEventAndCheck: Assertion = {
      eventBus ! Dummy
      val TextMessage.Strict(message) = client.expectMessage()

      val json         = parse(message).right.value
      val notification = json.as[NotificationUnsafe].right.value.asNotification.right.value

      notification.method is "events_fake"
    }

    def checkWS[A](f: => A): A =
      WS("/events", client.flow) ~> route ~> check {
        isWebSocketUpgrade is true
        f
      }
  }

  behavior of "http"

  it should "call mining_start" in new MiningMock {
    checkCallResult("mining_start")(true)
    miner.expectMsg(Miner.Start)
  }

  it should "call mining_stop" in new MiningMock {
    checkCallResult("mining_stop")(true)
    miner.expectMsg(Miner.Stop)
  }

  it should "call blockflow_fetch" in new RouteHTTP {
    checkCallResult("blockflow_fetch")(server.dummyFetchResponse)
  }

  it should "call clique_info" in new RouteHTTP {
    checkCallResult("clique_info")(server.dummyPeers)
  }

  it should "call get_balance" in new RouteHTTP {
    checkCallResult("get_balance")(server.dummyBalance)
  }

  it should "call transfer" in new RouteHTTP {
    checkCallResult("transfer")(server.dummyTransferResult)
  }

  it should "reject GET" in new RouteHTTP {
    Get() ~> route ~> check {
      rejections is List(
        MethodRejection(HttpMethods.OPTIONS),
        MethodRejection(HttpMethods.POST)
      )
    }
  }

  it should "reject wrong content type" in new RouteHTTP {
    val request = HttpRequest(HttpMethods.POST,
                              "/",
                              entity =
                                HttpEntity(ContentTypes.`text/plain(UTF-8)`, Json.Null.noSpaces))

    request ~> route ~> check {
      val List(rejection: UnsupportedRequestContentTypeRejection) = rejections
      rejection.supported is Set(ContentTypeRange(ContentTypes.`application/json`))
    }
  }

  behavior of "ws"

  it should "receive one event" in new RouteWS {
    checkWS {
      sendEventAndCheck
    }
  }

  it should "receive multiple events" in new RouteWS {
    checkWS {
      (0 to 3).foreach { _ =>
        sendEventAndCheck
      }
    }
  }
}