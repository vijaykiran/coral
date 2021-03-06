package io.coral.actors

//json

import org.json4s._
import io.coral.actors.transform.GroupByActor
import akka.actor.Props
import scaldi.Injectable._
import scaldi.Injector

object CoralActorFactory {
  
	def getProps(json: JValue)(implicit injector: Injector) = {
    val actorPropFactories = inject [List[ActorPropFactory]] (by default List())
    
		implicit val formats = org.json4s.DefaultFormats

		// check for grouping, if so generate a group actor and move on ...
		// otherwise, generate the proper actor
		val groupByProps = (json \ "attributes" \ "group" \ "by").extractOpt[String] match {
			case Some(x) => GroupByActor(json)
			case None => None
		}

		val actorProps = for {
			actorType <- (json \ "attributes" \ "type").extractOpt[String]
      props <- getActorProps(actorType, json, actorPropFactories)
    } yield props

		groupByProps orElse actorProps
	}
  
  private def getActorProps(actorType: String, json: JValue, actorPropFactories: List[ActorPropFactory]): Option[Props] = {
    actorPropFactories match {
      case head :: tail => {
        head.getProps(actorType, json) orElse getActorProps(actorType, json, tail)
      } 
      case Nil => None
    }
  }
}