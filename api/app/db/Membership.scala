package db

import com.gilt.apidoc.models.{Organization, User}
import com.gilt.apidoc.models.json._
import lib.Role
import anorm._
import play.api.db._
import play.api.Play.current
import play.api.libs.json._
import java.util.UUID

object Membership {

  private val InsertQuery = """
    insert into memberships
    (guid, organization_guid, user_guid, role, created_by_guid)
    values
    ({guid}::uuid, {organization_guid}::uuid, {user_guid}::uuid, {role}, {created_by_guid}::uuid)
  """

  private val BaseQuery = """
    select memberships.guid,
           role,
           organizations.guid as organization_guid,
           organizations.name as organization_name,
           organizations.key as organization_key,
           users.guid as user_guid,
           users.email as user_email,
           users.name as user_name
      from memberships
      join organizations on organizations.guid = memberships.organization_guid
      join users on users.guid = memberships.user_guid
     where memberships.deleted_at is null
  """

  def upsert(createdBy: User, organization: Organization, user: User, role: Role): com.gilt.apidoc.models.Membership = {
    val membership = findByOrganizationAndUserAndRole(organization, user, role) match {
      case Some(r) => r
      case None => create(createdBy, organization, user, role)
    }

    // If we made this user an admin, and s/he already exists as a
    // member, remove the member role - this is akin to an upgrade
    // in membership from member to admin.
    if (role == Role.Admin) {
      findByOrganizationAndUserAndRole(organization, user, Role.Member).foreach { membership =>
        softDelete(user, membership)
      }
    }

    membership
  }

  private[db] def create(createdBy: User, organization: Organization, user: User, role: Role): com.gilt.apidoc.models.Membership = {
    DB.withConnection { implicit c =>
      create(c, createdBy, organization, user, role)
    }
  }

  private[db] def create(implicit c: java.sql.Connection, createdBy: User, organization: Organization, user: User, role: Role): com.gilt.apidoc.models.Membership = {
    val membership = com.gilt.apidoc.models.Membership(
      guid = UUID.randomUUID,
      organization = organization,
      user = user,
      role = role.key
    )

    SQL(InsertQuery).on(
      'guid -> membership.guid,
      'organization_guid -> membership.organization.guid,
      'user_guid -> membership.user.guid,
      'role -> membership.role,
      'created_by_guid -> createdBy.guid
    ).execute()

    membership
  }

  /**
    * Deletes a membership record. Also removes the user from any
    * publication subscriptions that require the administrative role
    * for this org.
    */
  def softDelete(user: User, membership: com.gilt.apidoc.models.Membership) {
    SubscriptionDao.deleteSubscriptionsRequiringAdmin(user, membership.organization, membership.user)
    SoftDelete.delete("memberships", user, membership.guid)
  }

  def isUserAdmin(user: User, organization: Organization): Boolean = {
    findByOrganizationAndUserAndRole(organization, user, Role.Admin) match {
      case None => false
      case Some(_) => true
    }
  }

  def isUserMember(user: User, organization: Organization): Boolean = {
    findAll(organizationGuid = Some(organization.guid), userGuid = Some(user.guid), limit = 1).headOption match {
      case None => false
      case Some(_) => true
    }
  }

  def findByOrganizationAndUserAndRole(organization: Organization, user: User, role: Role): Option[com.gilt.apidoc.models.Membership] = {
    findAll(organizationGuid = Some(organization.guid), userGuid = Some(user.guid), role = Some(role.key)).headOption
  }

  def findAll(guid: Option[String] = None,
              organizationGuid: Option[UUID] = None,
              organizationKey: Option[String] = None,
              userGuid: Option[UUID] = None,
              role: Option[String] = None,
              limit: Int = 50,
              offset: Int = 0): Seq[com.gilt.apidoc.models.Membership] = {
    val sql = Seq(
      Some(BaseQuery.trim),
      guid.map { v => "and memberships.guid = {guid}::uuid" },
      organizationGuid.map { v => "and memberships.organization_guid = {organization_guid}::uuid" },
      organizationKey.map { v => "and memberships.organization_guid = (select guid from organizations where deleted_at is null and key = {organization_key})" },
      userGuid.map { v => "and memberships.user_guid = {user_guid}::uuid" },
      role.map { v => "and role = {role}" },
      Some(s"order by lower(users.name), lower(users.email) limit ${limit} offset ${offset}")
    ).flatten.mkString("\n   ")

    val bind = Seq[Option[NamedParameter]](
      guid.map('guid -> _ ),
      organizationGuid.map('organization_guid -> _.toString ),
      organizationKey.map('organization_key -> _ ),
      userGuid.map('user_guid -> _.toString),
      role.map('role -> _)
    ).flatten

    DB.withConnection { implicit c =>
      SQL(sql).on(bind: _*)().toList.map { row =>
        com.gilt.apidoc.models.Membership(
          guid = row[UUID]("guid"),
          organization = OrganizationDao.summaryFromRow(row, Some("organization")),
          user = UserDao.fromRow(row, Some("user")),
          role = row[String]("role")
        )
      }.toSeq
    }
  }

}
