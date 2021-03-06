package models.user

import java.sql.Timestamp

import com.google.common.base.Preconditions._

import db.{Model, ModelFilter, ModelService, Named, ObjectId, ObjectReference, ObjectTimestamp}
import db.access.{ModelAccess, ModelAssociationAccess}
import db.impl.OrePostgresDriver.api._
import db.impl._
import db.impl.access.{OrganizationBase, UserBase}
import models.project.{Flag, Project, Version, VisibilityTypes}
import models.user.role.{OrganizationRole, ProjectRole}
import ore.OreConfig
import ore.permission._
import ore.permission.role.RoleType
import ore.permission.role._
import ore.permission.scope._
import ore.user.Prompts.Prompt
import ore.user.UserOwned
import play.api.mvc.Request
import security.pgp.PGPPublicKeyInfo
import security.spauth.{SpongeAuthApi, SpongeUser}
import slick.lifted.TableQuery
import util.StringUtils._
import cats.instances.future._
import cats.syntax.all._
import cats.data.OptionT
import scala.concurrent.{ExecutionContext, Future}

import play.api.i18n.Lang

/**
  * Represents a Sponge user.
  *
  * @param id           External ID provided by authentication.
  * @param createdAt    Date this user first logged onto Ore.
  * @param fullName     Full name of user
  * @param name         Username
  * @param email        Email
  * @param tagline      The user configured "tagline" displayed on the user page.
  */
case class User(id: ObjectId = ObjectId.Uninitialized,
                createdAt: ObjectTimestamp = ObjectTimestamp.Uninitialized,
                fullName: Option[String] = None,
                name: String = null,
                email: Option[String] = None,
                tagline: Option[String] = None,
                globalRoles: List[RoleType] = List(),
                joinDate: Option[Timestamp] = None,
                readPrompts: List[Prompt] = List(),
                pgpPubKey: Option[String] = None,
                lastPgpPubKeyUpdate: Option[Timestamp] = None,
                isLocked: Boolean = false,
                lang: Option[Lang] = None)
                extends Model
                  with UserOwned
                  with ScopeSubject
                  with Named {

  //TODO: Check this in some way
  //checkArgument(tagline.forall(_.length <= config.users.get[Int]("max-tagline-len")), "tagline too long", "")

  override type M = User
  override type T = UserTable

  /**
    * The User's [[PermissionPredicate]]. All permission checks go through
    * here.
    */
  val can: PermissionPredicate = PermissionPredicate(this)

  def avatarUrl(implicit config: OreConfig): String = config.security.get[String]("api.avatarUrl").format(this.name)

  /**
    * Decodes this user's raw PGP public key and returns information about the
    * key.
    *
    * @return Public key information
    */
  def pgpPubKeyInfo: Option[PGPPublicKeyInfo] = this.pgpPubKey.flatMap(PGPPublicKeyInfo.decode)

  /**
    * Returns true if this user's PGP Public Key is ready for use.
    *
    * @return True if key is ready for use
    */
  def isPgpPubKeyReady(implicit config: OreConfig, service: ModelService): Boolean = this.pgpPubKey.isDefined && this.lastPgpPubKeyUpdate.forall { lastUpdate =>
    val cooldown = config.security.get[Long]("keyChangeCooldown")
    val minTime = new Timestamp(lastUpdate.getTime + cooldown)
    minTime.before(service.theTime)
  }

  /**
    * Returns this user's current language, or the default language if none
    * was configured.
    */
  implicit def langOrDefault: Lang = lang.getOrElse(Lang.defaultLang)

  /**
    * Returns the highest level [[DonorType]] this User has.
    *
    * @return Highest level donor type
    */
  def donorType: Option[DonorType] = {
    this.globalRoles
      .collect { case donor: DonorType => donor}
      .sortBy(_.rank).headOption
  }

  private def biggestRole(roles: Set[Role]): Trust = biggestRoleTpe(roles.map(_.roleType))

  private def biggestRoleTpe(roles: Set[RoleType]): Trust =
    if(roles.isEmpty) Default
    else roles.map(_.trust).max

  private def globalTrust = biggestRoleTpe(globalRoles.toSet)

  /**
    * Returns this User's highest level of Trust.
    *
    * @return Highest level of trust
    */
  def trustIn(scope: Scope = GlobalScope)(implicit ec: ExecutionContext, service: ModelService): Future[Trust] = Defined {
    scope match {
      case GlobalScope          => Future.successful(globalTrust)
      case pScope: ProjectScope =>
        val projectRoles = service.DB.db.run(Project.roleForTrustQuery(pScope.projectId, this.id.value).result)

        val projectTrust = projectRoles
          .map(biggestRoleTpe)
          .flatMap { userTrust =>
            if (userTrust != Default) {
              Future.successful(userTrust)
            } else {

              val projectMembersTable = TableQuery[ProjectMembersTable]
              val orgaTable = TableQuery[OrganizationTable]

              val memberTable = TableQuery[OrganizationMembersTable]
              val roleTable = TableQuery[OrganizationRoleTable]

              // TODO review permission logic
              val query = for {
                pm <- projectMembersTable if pm.projectId === pScope.projectId // Join members of project
                o <- orgaTable if pm.userId == o.userId // Filter out non organizations
                m <- memberTable if m.organizationId === o.id
                r <- roleTable if m.userId === r.userId && r.organizationId === o.id
              } yield r.roleType

              service.DB.db.run(query.to[Set].result).map(biggestRoleTpe)
            }
        }

        projectTrust.map(Set(_, globalTrust).max)
      case oScope: OrganizationScope =>
        Organization.getTrust(id.value, oScope.organizationId).map(Set(_, globalTrust).max)
      case _ =>
        throw new RuntimeException("unknown scope: " + scope)
    }
  }

  /**
    * Returns the Projects that this User has starred.
    *
    * @param page Page of user stars
    * @return Projects user has starred
    */
  def starred(page: Int = -1)(implicit ec: ExecutionContext, config: OreConfig, service: ModelService): Future[Seq[Project]] = Defined {
    val starsPerPage = config.users.get[Int]("stars-per-page")
    val limit = if (page < 1) -1 else starsPerPage
    val offset = (page - 1) * starsPerPage
    val filter = VisibilityTypes.isPublicFilter[Project]
    this.schema.getAssociation[ProjectStarsTable, Project](classOf[ProjectStarsTable], this)
      .sorted(ordering = _.name, filter = filter.fn, limit = limit, offset = offset)
  }

  /**
    * Returns true if this User is the currently authenticated user.
    *
    * @return True if currently authenticated user
    */
  def isCurrent(implicit request: Request[_], ec: ExecutionContext, service: ModelService, auth: SpongeAuthApi): Future[Boolean] = {
    checkNotNull(request, "null request", "")
    UserBase().current.semiflatMap { user =>
      if(user == this) Future.successful(true)
      else this.toMaybeOrganization.semiflatMap(_.owner.user).exists(_ == user)
    }.exists(identity)
  }

  /**
    * Copy this User with the information SpongeUser provides.
    *
    * @param user Sponge User
    */
  def copyFromSponge(user: SpongeUser)(implicit config: OreConfig): User = {
    copy(
      id = ObjectId(user.id),
      name = user.username,
      email = Some(user.email),
      lang = user.lang,
      globalRoles = user.addGroups.map { groups =>
        if(groups.trim.isEmpty) Nil else groups.split(",").flatMap(RoleType.withValueOpt).toList
      }.getOrElse(globalRoles)
    )
  }

  /**
    * Returns all [[Project]]s owned by this user.
    *
    * @return Projects owned by user
    */
  def projects(implicit service: ModelService): ModelAccess[Project] = this.schema.getChildren[Project](classOf[Project], this)

  /**
    * Returns the Project with the specified name that this User owns.
    *
    * @param name Name of project
    * @return Owned project, if any, None otherwise
    */
  def getProject(name: String)(implicit ec: ExecutionContext, service: ModelService): OptionT[Future, Project] = this.projects.find(equalsIgnoreCase(_.name, name))

  /**
    * Returns a [[ModelAccess]] of [[ProjectRole]]s.
    *
    * @return ProjectRoles
    */
  def projectRoles(implicit service: ModelService): ModelAccess[ProjectRole] = this.schema.getChildren[ProjectRole](classOf[ProjectRole], this)

  /**
    * Returns the [[Organization]]s that this User owns.
    *
    * @return Organizations user owns
    */
  def ownedOrganizations(implicit service: ModelService): ModelAccess[Organization] = this.schema.getChildren[Organization](classOf[Organization], this)

  /**
    * Returns the [[Organization]]s that this User belongs to.
    *
    * @return Organizations user belongs to
    */
  def organizations(implicit service: ModelService): ModelAssociationAccess[OrganizationMembersTable, Organization]
  = this.schema.getAssociation[OrganizationMembersTable, Organization](classOf[OrganizationMembersTable], this)

  /**
    * Returns a [[ModelAccess]] of [[OrganizationRole]]s.
    *
    * @return OrganizationRoles
    */
  def organizationRoles(implicit service: ModelService): ModelAccess[OrganizationRole] = this.schema.getChildren[OrganizationRole](classOf[OrganizationRole], this)

  /**
    * Converts this User to an [[Organization]].
    *
    * @return Organization
    */
  def toMaybeOrganization(implicit ec: ExecutionContext, service: ModelService): OptionT[Future, Organization] = Defined {
    OrganizationBase().get(this.id.value)
  }

  /**
    * Returns the [[Project]]s that this User is watching.
    *
    * @return Projects user is watching
    */
  def watching(implicit service: ModelService): ModelAssociationAccess[ProjectWatchersTable, Project] = this.schema.getAssociation[ProjectWatchersTable, Project](classOf[ProjectWatchersTable], this)

  /**
    * Sets the "watching" status on the specified project.
    *
    * @param project  Project to update status on
    * @param watching True if watching
    */
  def setWatching(project: Project, watching: Boolean)(implicit ec: ExecutionContext, service: ModelService): Future[Unit] = {
    checkNotNull(project, "null project", "")
    checkArgument(project.isDefined, "undefined project", "")
    val contains = this.watching.contains(project)
    contains.map {
      case true => if (!watching) this.watching.remove(project).as(())
      case false => if (watching) this.watching.add(project).as(())
    }
  }

  /**
    * Returns the [[Flag]]s submitted by this User.
    *
    * @return Flags submitted by user
    */
  def flags(implicit service: ModelService): ModelAccess[Flag] = this.schema.getChildren[Flag](classOf[Flag], this)

  /**
    * Returns true if the User has an unresolved [[Flag]] on the specified
    * [[Project]].
    *
    * @param project Project to check
    * @return True if has pending flag on Project
    */
  def hasUnresolvedFlagFor(project: Project)(implicit ec: ExecutionContext, service: ModelService): Future[Boolean] = {
    checkNotNull(project, "null project", "")
    checkArgument(project.isDefined, "undefined project", "")
    this.flags.exists(f => f.projectId === project.id.value && !f.isResolved)
  }

  /**
    * Returns this User's notifications.
    *
    * @return User notifications
    */
  def notifications(implicit service: ModelService): ModelAccess[Notification] = this.schema.getChildren[Notification](classOf[Notification], this)

  /**
    * Sends a [[Notification]] to this user.
    *
    * @param notification Notification to send
    * @return Future result
    */
  def sendNotification(notification: Notification)(implicit ec: ExecutionContext, service: ModelService, config: OreConfig): Future[Notification] = {
    checkNotNull(notification, "null notification", "")
    config.debug("Sending notification: " + notification, -1)
    service.access[Notification](classOf[Notification]).add(notification.copy(userId = this.id.value))
  }

  /**
    * Marks a [[Prompt]] as read by this User.
    *
    * @param prompt Prompt to mark as read
    */
  def markPromptAsRead(prompt: Prompt)(implicit ec: ExecutionContext, service: ModelService): Future[User] = {
    checkNotNull(prompt, "null prompt", "")
    service.update(
      copy(
        readPrompts = readPrompts :+ prompt
      )
    )
  }

  override val scope: GlobalScope.type = GlobalScope
  override def userId: ObjectReference = this.id.value
  override def copyWith(id: ObjectId, theTime: ObjectTimestamp): User = this.copy(createdAt = theTime)
}

object User {

  /**
    * Create a new [[User]] from the specified [[SpongeUser]].
    *
    * @param toConvert User to convert
    * @return Ore user
    */
  def fromSponge(toConvert: SpongeUser)(implicit config: OreConfig): User =
    User().copyFromSponge(toConvert)
}
