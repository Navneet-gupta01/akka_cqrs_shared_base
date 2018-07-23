package com.navneetgupta.cqrs.shared.event

import com.navneetgupta.cqrs.shared.adapter.DatamodelWriter
import java.util.UUID

trait BaseEvent extends Serializable with DatamodelWriter {
  def entityType: String
  val version: Option[UUID] = None // This Should be defined for Strong Consistency
}
