package controllers

import controllers.sugar.Bakery
import controllers.sugar.Requests.AuthRequest
import db.{ModelService, ObjectReference}
import db.impl.OrePostgresDriver.api._
import db.impl.access.UserBase.UserOrdering
import db.impl.{ProjectTableMain, VersionTable}
import discourse.OreDiscourseApi
import form.OreForms
import javax.inject.Inject

import mail.{EmailFactory, Mailer}
import models.user.{LoggedAction, SignOn, User, UserActionLogger}
import models.viewhelper.{OrganizationData, ScopedOrganizationData}
import ore.permission.ReviewProjects
import ore.rest.OreWrites
import ore.user.notification.InviteFilters.InviteFilter
import ore.user.notification.NotificationFilters.NotificationFilter
import ore.user.notification.{InviteFilters, NotificationFilters}
import ore.user.{FakeUser, Prompts}
import ore.{OreConfig, OreEnv}
import play.Logger
import play.api.cache.AsyncCacheApi
import play.api.i18n.MessagesApi
import play.api.mvc._
import security.spauth.{SingleSignOnConsumer, SpongeAuthApi}
import views.{html => views}
import cats.instances.future._
import cats.syntax.all._
import scala.concurrent.{ExecutionContext, Future}

import cats.data.EitherT

/**
  * Controller for general user actions.
  */
class Users @Inject()(fakeUser: FakeUser,
                      forms: OreForms,
                      forums: OreDiscourseApi,
                      writes: OreWrites,
                      mailer: Mailer,
                      emails: EmailFactory)(
    implicit val ec: ExecutionContext,
    bakery: Bakery,
    auth: SpongeAuthApi,
    sso: SingleSignOnConsumer,
    messagesApi: MessagesApi,
    env: OreEnv,
    config: OreConfig,
    cache: AsyncCacheApi,
    service: ModelService
) extends OreBaseController {

  private val baseUrl = this.config.app.get[String]("baseUrl")

  /**
    * Redirect to auth page for SSO authentication.
    *
    * @return Logged in page
    */
  def signUp() = Action { implicit request =>
    val nonce = SingleSignOnConsumer.nonce
    this.signOns.add(SignOn(nonce = nonce))
    redirectToSso(this.sso.getSignupUrl(this.baseUrl + "/login", nonce))
  }

  /**
    * Redirect to auth page for SSO authentication and then back here again.
    *
    * @param sso  Incoming payload from auth
    * @param sig  Incoming signature from auth
    * @return     Logged in home
    */
  def logIn(sso: Option[String], sig: Option[String], returnPath: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    if (this.fakeUser.isEnabled) {
      // Log in as fake user (debug only)
      this.config.checkDebug()
      users.getOrCreate(this.fakeUser)
      this.redirectBack(returnPath.getOrElse(request.path), this.fakeUser)
    } else if (sso.isEmpty || sig.isEmpty) {
      val nonce = SingleSignOnConsumer.nonce
      this.signOns.add(SignOn(nonce = nonce))
      Future.successful(redirectToSso(this.sso.getLoginUrl(this.baseUrl + "/login", nonce)))
    } else {
      // Redirected from SpongeSSO, decode SSO payload and convert to Ore user
      this.sso.authenticate(sso.get, sig.get)(isNonceValid).map(User.fromSponge).semiflatMap { fromSponge =>
        // Complete authentication
        for {
          user <- users.getOrCreate(fromSponge)
          result <- this.redirectBack(request.flash.get("url").getOrElse("/"), user)
        } yield result
      }.getOrElse(Redirect(ShowHome).withError("error.loginFailed"))
    }
  }

  /**
    * Redirects the user to the auth verification page to re-enter their
    * password and then perform some action.
    *
    * @param returnPath Verified action to perform
    * @return           Redirect to verification
    */
  def verify(returnPath: Option[String]) = Authenticated { implicit request =>
    val nonce = SingleSignOnConsumer.nonce
    this.signOns.add(SignOn(nonce = nonce))
    redirectToSso(this.sso.getVerifyUrl(this.baseUrl + returnPath.getOrElse("/"), nonce))
  }

  private def redirectToSso(url: String): Result = {
    if (this.sso.isAvailable)
      Redirect(url)
    else
      Redirect(ShowHome).withError("error.noLogin")
  }

  private def redirectBack(url: String, user: User)
  = Redirect(this.baseUrl + url).authenticatedAs(user, this.config.play.get[Int]("http.session.maxAge"))

  /**
    * Clears the current session.
    *
    * @return Home page
    */
  def logOut() = Action { implicit request =>
    Redirect(config.security.get[String]("api.url") + "/accounts/logout/").clearingSession().flashing("noRedirect" -> "true")
  }

  /**
    * Shows the User's [[models.project.Project]]s page for the user with the
    * specified username.
    *
    * @param username   Username to lookup
    * @return           View of user projects page
    */
  def showProjects(username: String, page: Option[Int]): Action[AnyContent] = OreAction async { implicit request =>
    val pageSize = this.config.users.get[Int]("project-page-size")
    val p = page.getOrElse(1)
    val offset = (p - 1) * pageSize
    users.withName(username).semiflatMap { user =>
      for {
        // TODO include orga projects?
        projectSeq <- service.DB.db.run(queryUserProjects(user).drop(offset).take(pageSize).result)
        starred <- user.starred()
        orga <- getOrga(request, username).value
        (tags, userData, starredRv, orgaData, scopedOrgaData) <- (
          Future.sequence(projectSeq.map(_._2.tags)),
          getUserData(request, username).value,
          Future.sequence(starred.map(_.recommendedVersion)),
          OrganizationData.of(orga).value,
          ScopedOrganizationData.of(request.currentUser, orga).value
        ).tupled
      } yield {
        val data = projectSeq zip tags map { case ((p, v), tags) =>
          (p, user, v, tags)
        }
        val starredData = starred zip starredRv
        Ok(views.users.projects(userData.get, orgaData.flatMap(a => scopedOrgaData.map(b => (a, b))), data, starredData.take(5), p))
      }
    }.getOrElse(notFound)
  }

  private def queryUserProjects(user: User) = {
    queryProjectRV filter { case (p, v) =>
      p.userId === user.id.value
    } sortBy { case (p, v) =>
      (p.stars.desc, p.name.asc)
    }
  }

  private def queryProjectRV = {
    val tableProject = TableQuery[ProjectTableMain]
    val tableVersion = TableQuery[VersionTable]

    for {
      p <- tableProject
      v <- tableVersion if p.recommendedVersionId === v.id
    } yield {
      (p, v)
    }
  }

  /**
    * Submits a change to the specified user's tagline.
    *
    * @param username   User to update
    * @return           View of user page
    */
  def saveTagline(username: String): Action[AnyContent] = UserAction(username).async { implicit request =>
    val maxLen = this.config.users.get[Int]("max-tagline-len")

    val res = for {
      user <- users.withName(username).toRight(NotFound)
      tagline <- bindFormEitherT[Future](this.forms.UserTagline)(_ => BadRequest)
      res <- {
        if (tagline.length > maxLen)
          EitherT.rightT[Future, Result](Redirect(ShowUser(user)).withError(request.messages.apply("error.tagline.tooLong", maxLen)))
        else {
          val log = UserActionLogger.log(request, LoggedAction.UserTaglineChanged, user.id.value, tagline, user.tagline.getOrElse("null"))
          val insert = service.update(user.copy(tagline = Some(tagline)))
          EitherT.right[Result](log *> insert).map(_ => Redirect(ShowUser(user)))
        }
      }
    } yield res

    res.merge
  }

  /**
    * Attempts to save a submitted PGP Public Key to the specified User
    * profile.
    *
    * @param username User to save key to
    * @return JSON response
    */
  def savePgpPublicKey(username: String): Action[AnyContent] = UserAction(username).async { implicit request =>
    this.forms.UserPgpPubKey.bindFromRequest.fold(
      hasErrors =>
        Future.successful(Redirect(ShowUser(username)).withFormErrors(hasErrors.errors)),
      keySubmission => {
        val keyInfo = keySubmission.info
        val user = request.user

        // Send email notification
        this.mailer.push(this.emails.create(user, this.emails.PgpUpdated))
        UserActionLogger.log(request, LoggedAction.UserPgpKeySaved, user.id.value, "", "")

        service.update(
          user.copy(
            pgpPubKey = Some(keyInfo.raw),
            lastPgpPubKeyUpdate =
              if(user.lastPgpPubKeyUpdate.isDefined) Some(service.theTime)
              else user.lastPgpPubKeyUpdate
          )
        ).as(Redirect(ShowUser(username)).flashing("pgp-updated" -> "true"))
      }
    )
  }

  /**
    * Deletes the specified [[User]]'s PGP public key if it exists.
    *
    * @param username Username to delete key for
    * @return Ok if deleted, bad request if didn't exist
    */
  def deletePgpPublicKey(username: String, sso: Option[String], sig: Option[String]): Action[AnyContent] = {
    VerifiedAction(username, sso, sig).async { implicit request =>
      Logger.debug("Deleting public key for " + username)
      val user = request.user
      if (user.pgpPubKey.isEmpty)
        Future.successful(BadRequest)
      else {
        val log = UserActionLogger.log(request, LoggedAction.UserPgpKeyRemoved, user.id.value, "", "")
        val insert = service.update(
          user.copy(
            pgpPubKey = None,
            lastPgpPubKeyUpdate = Some(service.theTime)
          )
        )

        (log *> insert).as(Redirect(ShowUser(username)).flashing("pgp-updated" -> "true"))
      }
    }
  }

  /**
    * Sets the "locked" status of a User.
    *
    * @param username User to set status of
    * @param locked   True if user is locked
    * @return         Redirection to user page
    */
  def setLocked(username: String, locked: Boolean, sso: Option[String], sig: Option[String]): Action[AnyContent] = {
    VerifiedAction(username, sso, sig).async { implicit request =>
      val user = request.user
      if (!locked)
        this.mailer.push(this.emails.create(user, this.emails.AccountUnlocked))
      service.update(
        user.copy(isLocked = locked)
      ).as(Redirect(ShowUser(username)))
    }
  }

  /**
    * Shows a list of [[models.user.User]]s that have created a
    * [[models.project.Project]].
    */
  def showAuthors(sort: Option[String], page: Option[Int]): Action[AnyContent] = OreAction async { implicit request =>
    val ordering = sort.getOrElse(UserOrdering.Projects)
    val p = page.getOrElse(1)
    users.getAuthors(ordering, p).map { u =>
      Ok(views.users.authors(u, ordering, p))
    }
  }


  /**
    * Shows a list of [[models.user.User]]s that have Ore staff roles.
    */
  def showStaff(sort: Option[String], page: Option[Int]): Action[AnyContent] = (Authenticated andThen PermissionAction[AuthRequest](ReviewProjects)).async { implicit request =>
    val ordering = sort.getOrElse(UserOrdering.Role)
    val p = page.getOrElse(1)
    users.getStaff(ordering, p).map { u =>
      Ok(views.users.staff(u, ordering, p))
    }
  }

  /**
    * Displays the current user's unread notifications.
    *
    * @return Unread notifications
    */
  def showNotifications(notificationFilter: Option[String], inviteFilter: Option[String]): Action[AnyContent] = {
    Authenticated.async { implicit request =>
      val user = request.user

      // Get visible notifications
      val nFilter: NotificationFilter = notificationFilter
        .flatMap(str => NotificationFilters.values.find(_.name.equalsIgnoreCase(str)))
        .getOrElse(NotificationFilters.Unread)

      val iFilter: InviteFilter = inviteFilter
        .flatMap(str => InviteFilters.values.find(_.name.equalsIgnoreCase(str)))
        .getOrElse(InviteFilters.All)

      val notificationsFut = nFilter(user.notifications).flatMap(l => Future.sequence(l.map(notif => notif.origin.map((notif, _)))))
      val invitesFut = iFilter(user).flatMap(invites => Future.sequence(invites.map {invite => invite.subject.map((invite, _))}))

      (notificationsFut, invitesFut).mapN { (notifications, invites) =>
        Ok(views.users.notifications(
          notifications,
          invites,
          nFilter, iFilter))
      }
    }
  }

  /**
    * Marks a [[models.user.User]]'s notification as read.
    *
    * @param id Notification ID
    * @return   Ok if marked as read, NotFound if notification does not exist
    */
  def markNotificationRead(id: ObjectReference): Action[AnyContent] = Authenticated.async { implicit request =>
    request.user.notifications.get(id).semiflatMap { notification =>
      service.update(notification.copy(isRead = true)).as(Ok)
    }.getOrElse(notFound)
  }

  /**
    * Marks a [[ore.user.Prompts.Prompt]] as read for the authenticated
    * [[models.user.User]].
    *
    * @param id Prompt ID
    * @return   Ok if successful
    */
  def markPromptRead(id: ObjectReference): Action[AnyContent] = Authenticated.async { implicit request =>
    Prompts.values.find(_.id == id) match {
      case None         => Future.successful(BadRequest)
      case Some(prompt) => request.user.markPromptAsRead(prompt).as(Ok)
    }
  }

}
