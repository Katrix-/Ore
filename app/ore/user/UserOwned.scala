package ore.user

import db.impl.access.UserBase
import models.user.User
import cats.instances.future._
import scala.concurrent.{ExecutionContext, Future}

import db.ObjectReference

/** Represents anything that has a [[User]]. */
trait UserOwned {
  /** Returns the User ID */
  def userId: ObjectReference
  /** Returns the User */
  def user(implicit users: UserBase, ec: ExecutionContext): Future[User] =
    users.get(this.userId).getOrElse(throw new NoSuchElementException("None on get"))
}
