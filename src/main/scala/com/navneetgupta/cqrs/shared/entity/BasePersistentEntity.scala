
package com.navneetgupta.cqrs.shared.entity

import com.navneetgupta.cqrs.shared.command.BaseCommand
import scala.reflect.ClassTag
import akka.persistence.PersistentActor
import akka.actor.ActorLogging
import akka.persistence.SnapshotOffer
import akka.persistence.RecoveryCompleted
import akka.actor.ReceiveTimeout
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.actor.Props
import akka.actor.PoisonPill
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import akka.cluster.sharding.ShardRegion
import akka.actor.ActorSystem
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import com.navneetgupta.cqrs.shared.fo.BaseFieldsObject
import com.navneetgupta.cqrs.shared.event._
import com.navneetgupta.cqrs.shared.model._

object BasePersistentEntity {
  case object StopEntity
  case class GetState(id: String) extends BaseCommand {
    override def entityId = id
  }
  case class MarkAsDeleted(id: String) extends BaseCommand {
    override def entityId = id
  }

  def getPersistentEntityTimeout(config: Config, timeUnit: TimeUnit): Duration =
    Duration.create(config.getDuration("persistent-entity-timeout", TimeUnit.SECONDS), timeUnit)
}

abstract class BasePersistentEntity[FO <: BaseFieldsObject[String, FO]: ClassTag] extends PersistentActor with ActorLogging {

  import BasePersistentEntity._
  import scala.concurrent.duration._

  val id = self.path.name
  val entityType = getClass.getSimpleName
  var state: FO = initialState
  var eventsSinceLastSnapshot = 0

  context.setReceiveTimeout(1 minutes)

  override def persistenceId = s"$entityType-$id"

  override def receiveRecover = standardRecover orElse customRecover

  def standardRecover: Receive = {
    case ev: BaseEvent => {
      log.info("Recovering persisted event: {}", ev)
      handleEvent(ev)
      eventsSinceLastSnapshot += 1
    }

    case SnapshotOffer(metadata, snapshot: FO) => {
      log.info("Recovering entity with a snapshot: {}", snapshot)
      state = snapshot
    }

    case RecoveryCompleted =>
      log.debug("Recovery completed for {} entity with id {}", entityType, id)
  }

  def customRecover: Receive = PartialFunction.empty

  override def receiveCommand = standardCommandHandling orElse additionalCommandHandling

  def standardCommandHandling: Receive = {
    case ReceiveTimeout =>
      log.info("{} entity with id {} is being passivated due to inactivity", entityType, id)
      context.parent ! Passivate(stopMessage = StopEntity)
    //self ! PoisonPill
    case StopEntity =>
      log.info("{} entity with id {} is now being stopped due to inactivity", entityType, id)
      context stop self
    case any if !isAcceptingCommand(any) =>
      log.warning("Not allowing action {} on a deleted entity or an entity in the initial state with id {}", any, id)
      sender() ! stateResponse()

    case GetState(id) =>
      log.info("Getting State for Id : {} for EntityType: {}", id, entityType)
      sender() ! stateResponse()
    case MarkAsDeleted =>
      log.info("MarkAsDelete Event Hitted for Entitytype: {} and id : {}", entityType, id)
      newDeleteEvent match {
        case None =>
          log.info("The entity type {} does not support deletion, ignoring delete request", entityType)
          sender ! stateResponse()

        case Some(event) =>
          persist(event)(handleEventAndRespond(false))

      }
    case s: SaveSnapshotSuccess =>
      log.info("Successfully saved a new snapshot for entity {} and id {}", entityType, id)

    case f: SaveSnapshotFailure =>
      log.error(f.cause, "Failed to save a snapshot for entity {} and id {}, reason was {}", entityType)

  }

  def additionalCommandHandling: Receive = PartialFunction.empty

  def isAcceptingCommand(cmd: Any): Boolean = {
    !state.deleted &&
      !(state == initialState && !isCreateMessage(cmd))
  }

  def isCreateMessage(cmd: Any): Boolean

  def newDeleteEvent: Option[BaseEvent] = None

  //This handler is eventually Consistent. I.E. this will return the current state as response. It will not wait for Read journal to Get updated. So if Some API call is
  // Directly getting data from ReadJournal The state will differ. with Read journal returning old data.

  // We can make some what Strong Consistency using Publish/Subscribe Messaging System to return always readJournal Updated State only
  def handleEventAndRespond[A](respectDeleted: Boolean = true)(event: BaseEvent) = {
    handleEvent(event)
    if (snapshotAfterCount.isDefined) {
      eventsSinceLastSnapshot += 1
      maybeSnapshot
    }
    if (event.version.isDefined) {
      // Set Version(A UUID) For Which Event u need to have strong Consistency. Put a timeout also to wait for the subscription Other wise that request will never complete and will
      // Keep on wiaitng in case of any error.
      // Use a Subscriber of a messaging system to listen for a pattern "entityType-id-evnet.version"
      // This Pattern will be published with updated state after the ReadJournal is updated.
      // This publish Subscriber should Publish/Subscribe across networks. Not Concentrated on Local Machine Only since it Could happen that event is persisted on
      // One node and REad Journal is updated at another node in a clustering Environment.
      // MEssaging System such as Redis Pub/Sub , Apache Kafka etc. Could be used easily.
    } else {
      sender() ! stateResponse(respectDeleted)
    }
  }

  def handleEventAndRespondWithStrongConstency[A](respectDeleted: Boolean = true)(event: BaseEvent) = {
    handleEvent(event)
    if (snapshotAfterCount.isDefined) {
      eventsSinceLastSnapshot += 1
      maybeSnapshot
    }

  }

  def stateResponse(respectDeleted: Boolean = true): ServiceResult[FO] = {
    //If we have not persisted this entity yet, then EmptyResult
    if (state == initialState) EmptyResult

    //If respecting deleted and it's marked deleted, EmptyResult
    else if (respectDeleted && state.deleted) EmptyResult

    //Otherwise, return it as a FullResult
    else FullResult(state)
  }

  def initialState: FO

  def handleEvent(event: BaseEvent): Unit

  def snapshotAfterCount: Option[Int] = None

  def maybeSnapshot: Unit = {
    snapshotAfterCount.
      filter(i => eventsSinceLastSnapshot >= i).
      foreach { i =>
        log.info("Taking snapshot because event count {} is > snapshot event limit of {}", eventsSinceLastSnapshot, i)
        saveSnapshot(state)
        eventsSinceLastSnapshot = 0
      }
  }
}
