package db.impl.model.common

import scala.concurrent.{ExecutionContext, Future}

import db.{Model, ModelService, ObjectReference}
import db.access.ModelAccess
import db.impl.table.common.VisibilityColumn
import models.project.VisibilityTypes
import models.project.VisibilityTypes.Visibility
import cats.data.OptionT

/**
  * Represents a [[Model]] that has a toggleable visibility.
  */
trait Hideable extends Model { self =>

  override type M <: Hideable { type M = self.M }
  override type T <: VisibilityColumn[M]
  type ModelVisibilityChange <: VisibilityChange

  /**
    * Returns true if the [[Model]] is visible.
    *
    * @return True if model is visible
    */
  def visibility: Visibility

  def isDeleted: Boolean = visibility == VisibilityTypes.SoftDelete

  /**
    * Sets whether this project is visible.
    *
    * @param visibility True if visible
    */
  def setVisibility(visibility: Visibility, comment: String, creator: ObjectReference)(implicit ec: ExecutionContext, service: ModelService): Future[(M, ModelVisibilityChange)]

  /**
    * Get VisibilityChanges
    */
  def visibilityChanges(implicit service: ModelService): ModelAccess[ModelVisibilityChange]

  def visibilityChangesByDate(implicit ec: ExecutionContext, service: ModelService): Future[Seq[ModelVisibilityChange]] =
    visibilityChanges.all.map(_.toSeq.sortWith(byCreationDate))

  def byCreationDate(first: ModelVisibilityChange, second: ModelVisibilityChange): Boolean =
    first.createdAt.value.getTime < second.createdAt.value.getTime

  def lastVisibilityChange(implicit ec: ExecutionContext, service: ModelService): OptionT[Future, ModelVisibilityChange] =
    OptionT(visibilityChanges.all.map(_.toSeq.filter(cr => !cr.isResolved).sortWith(byCreationDate).headOption))

  def lastChangeRequest(implicit ec: ExecutionContext, service: ModelService): OptionT[Future, ModelVisibilityChange] =
    OptionT(visibilityChanges.all.map(_.toSeq.filter(cr => cr.visibility == VisibilityTypes.NeedsChanges.id).sortWith(byCreationDate).lastOption))

}
