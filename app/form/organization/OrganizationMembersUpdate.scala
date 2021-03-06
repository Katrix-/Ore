package form.organization

import models.user.role.OrganizationRole
import models.user.{Notification, Organization}
import ore.permission.role.RoleType
import ore.user.notification.NotificationTypes
import play.api.cache.AsyncCacheApi
import play.api.i18n.{Lang, MessagesApi}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.NonEmptyList
import db.{ModelService, ObjectReference}
import db.impl.{OrganizationMembersTable, OrganizationRoleTable}
import ore.OreConfig
import ore.organization.OrganizationMember
import ore.user.MembershipDossier

/**
  * Saves new and old [[OrganizationRole]]s.
  *
  * @param users    New users
  * @param roles    New roles
  * @param userUps  Old users
  * @param roleUps  Old roles
  */
case class OrganizationMembersUpdate(override val users: List[ObjectReference],
                                     override val roles: List[String],
                                     userUps: List[String],
                                     roleUps: List[String]) extends TOrganizationRoleSetBuilder {

  //noinspection ComparingUnrelatedTypes
  def saveTo(organization: Organization)(implicit cache: AsyncCacheApi, ex: ExecutionContext, messages: MessagesApi, service: ModelService, config: OreConfig): Unit = {
    if (!organization.isDefined)
      throw new RuntimeException("tried to update members on undefined organization")

    // Add new roles
    val dossier: MembershipDossier {
      type MembersTable = OrganizationMembersTable

      type MemberType = OrganizationMember

      type RoleTable = OrganizationRoleTable

      type ModelType = Organization

      type RoleType = OrganizationRole
    } = organization.memberships
    val orgId = organization.id.value
    for (role <- this.build()) {
      val user = role.user
      dossier.addRole(role.copy(organizationId = orgId))
      user.flatMap { user =>
        import user.langOrDefault
        user.sendNotification(Notification(
          userId = user.id.value,
          originId = orgId,
          notificationType = NotificationTypes.OrganizationInvite,
          messageArgs = NonEmptyList.of("notification.organization.invite", role.roleType.title, organization.name)
        ))
      }
    }

    // Update existing roles
    val orgRoleTypes = RoleType.values.filter(_.roleClass.equals(classOf[OrganizationRole]))
    for ((user, i) <- this.userUps.zipWithIndex) {
        organization.memberships.members.flatMap { members =>
          Future.sequence(members.map(member => member.user.map((_, member))))
        } map { users =>
          users.find(_._1.name.equalsIgnoreCase(user.trim)).foreach { user =>
            user._2.headRole.flatMap { role =>
              val roleType = orgRoleTypes.find(_.title.equals(roleUps(i))).getOrElse(throw new RuntimeException("supplied invalid role type"))
              service.update(role.copy(roleType = roleType))
            }
          }
        }
      }
    }
}
