package com.gettickets

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Route

import scala.concurrent.ExecutionContext

class RestApi(system: ActorSystem, timeout: Timeout) extends RestRoutes {
  implicit val requestTimeout = timeout
  implicit def executionContext = system.dispatcher

  def createBoxOffice = system.actorOf(BoxOffice.props, BoxOffice.name)
}

trait RestRoutes extends BoxOfficeApi with EventMarshalling{

  import  StatusCodes._

  def routes: Route = eventsRoutes ~ eventRoute ~ ticketRoute

  def eventsRoutes =
    pathPrefix("events"){
      pathEndOrSingleSlash{
        get{
          onSuccess(getEvents()){ events =>
            complete(OK, events)
          }
        }
      }
    }

  def eventRoute =
    pathPrefix("events" / Segment) { event =>
      pathEndOrSingleSlash{
        // POST /events/:event
        post{
          entity(as[EventDescription]){ ed =>
            onSuccess(createEvent(event, ed.tickets)){
              case BoxOffice.EventCreated(event) => complete(Created, event)
              case BoxOffice.EventExists =>
                val err = Error(s"$event event exists already.")
                complete(BadRequest, err)
            }
          }
        } ~

        get{
          // GET /events/:event
          onSuccess(getEvent(event)){
            _.fold(complete(NotFound))(e => complete(OK, e))
          }
        } ~

        delete{
          // DELETE /events/:event
          onSuccess(cancelEvent(event)){
            _.fold(complete(NotFound))(e => complete(OK, e))
          }
        }
      }
    }

  def ticketRoute =
    pathPrefix("events" / Segment / "tickets"){ event =>
      post{
        pathEndOrSingleSlash{
          // POST /events/:event/tickets
          entity(as[TicketRequest]){  request =>
            onSuccess(requestTickets(event, request.tickets)){ tickets =>
              if (tickets.entries.isEmpty) complete(NotFound)
              else complete(Created, tickets)
            }
          }
        }
      }
    }
}

trait BoxOfficeApi{
  import BoxOffice._

  def createBoxOffice(): ActorRef

  implicit def executionContext: ExecutionContext
  implicit def requestTimeout: Timeout

  lazy val boxOffice = createBoxOffice()

  def createEvent(event: String, numberTickets: Int) =  boxOffice.ask(CreatEvent(event,numberTickets)).mapTo[EventResponse]

  def getEvents() = boxOffice.ask(GetEvents).mapTo[Events]

  def getEvent(event: String) = boxOffice.ask(GetEvent(event)).mapTo[Option[Event]]

  def cancelEvent(event: String) = boxOffice.ask(CancelEvent(event)).mapTo[Option[Event]]

  def requestTickets(event: String, tickets: Int) =
    boxOffice.ask(GetTickets(event, tickets)).mapTo[TicketSeller.Tickets]
}
