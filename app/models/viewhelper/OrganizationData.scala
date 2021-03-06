package models.viewhelper

import db.{ModelService, ObjectReference}
import db.impl.OrePostgresDriver.api._
import db.impl._
import models.project.Project
import models.user.role.{OrganizationRole, ProjectRole}
import models.user.{Organization, User}
import ore.organization.OrganizationMember
import ore.permission._
import play.api.cache.AsyncCacheApi
import slick.jdbc.JdbcBackend
import scala.concurrent.{ExecutionContext, Future}

import slick.lifted.TableQuery
import cats.data.OptionT
import cats.instances.future._

case class OrganizationData(joinable: Organization,
                            ownerRole: OrganizationRole,
                            members: Seq[(OrganizationRole, User)], // TODO sorted/reverse
                            projectRoles: Seq[(ProjectRole, Project)]
                            )
  extends JoinableData[OrganizationRole, OrganizationMember, Organization] {

  def orga: Organization = joinable

}

object OrganizationData {
  val noPerms: Map[Permission, Boolean] = Map(EditSettings -> false)

  def cacheKey(orga: Organization): String = "organization" + orga.id.value

  def of[A](orga: Organization)(implicit cache: AsyncCacheApi, db: JdbcBackend#DatabaseDef, ec: ExecutionContext,
                                service: ModelService): Future[OrganizationData] = {
    for {
      role <- orga.owner.headRole
      members <- orga.memberships.members
      memberRoles <- Future.sequence(members.map(_.headRole))
      memberUser <- Future.sequence(memberRoles.map(_.user))
      projectRoles <- db.run(queryProjectRoles(orga.id.value).result)
    } yield {
      val members = memberRoles zip memberUser
      OrganizationData(orga, role, members.toSeq, projectRoles)
    }
  }

  private def queryProjectRoles(userId: ObjectReference) = {
    val tableProjectRole = TableQuery[ProjectRoleTable]
    val tableProject = TableQuery[ProjectTableMain]

    for {
      (role, project) <- tableProjectRole.join(tableProject).on(_.projectId === _.id) if role.userId === userId
    } yield {
      (role, project)
    }
  }


  def of[A](orga: Option[Organization])(implicit cache: AsyncCacheApi, db: JdbcBackend#DatabaseDef, ec: ExecutionContext,
                                        service: ModelService): OptionT[Future, OrganizationData] = {
    OptionT.fromOption[Future](orga).semiflatMap(of)
  }
}
