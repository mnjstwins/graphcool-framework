package cool.graph.deploy.database.tables

import cool.graph.shared.models.MigrationStatus
import cool.graph.shared.models.MigrationStatus.MigrationStatus
import play.api.libs.json.JsValue
import slick.dbio.Effect.{Read, Write}
import slick.jdbc.MySQLProfile.api._
import slick.sql.{FixedSqlAction, FixedSqlStreamingAction, SqlAction}

case class Migration(
    projectId: String,
    revision: Int,
    schema: JsValue,
    status: MigrationStatus,
    applied: Int,
    rolledBack: Int,
    steps: JsValue,
    errors: JsValue
)

class MigrationTable(tag: Tag) extends Table[Migration](tag, "Migration") {
  implicit val statusMapper = MigrationTable.statusMapper
  implicit val jsonMapper   = MigrationTable.jsonMapper

  def projectId  = column[String]("projectId")
  def revision   = column[Int]("revision")
  def schema     = column[JsValue]("schema")
  def status     = column[MigrationStatus]("status")
  def applied    = column[Int]("applied")
  def rolledBack = column[Int]("rolledBack")
  def steps      = column[JsValue]("steps")
  def errors     = column[JsValue]("errors")

  def migration = foreignKey("migrations_projectid_foreign", projectId, Tables.Projects)(_.id)
  def *         = (projectId, revision, schema, status, applied, rolledBack, steps, errors) <> (Migration.tupled, Migration.unapply)
}

object MigrationTable {
  implicit val jsonMapper = MappedColumns.jsonMapper
  implicit val statusMapper = MappedColumnType.base[MigrationStatus, String](
    _.toString,
    MigrationStatus.withName
  )

  // todo: Take a hard look at the code and determine if this is necessary
  // Retrieves the last migration for the project, regardless of its status
  def lastRevision(projectId: String): SqlAction[Option[Int], NoStream, Read] = {
    val baseQuery = for {
      migration <- Tables.Migrations
      if migration.projectId === projectId
    } yield migration.revision

    baseQuery.max.result
  }

  def lastSuccessfulMigration(projectId: String): SqlAction[Option[Migration], NoStream, Read] = {
    val baseQuery = for {
      migration <- Tables.Migrations
      if migration.projectId === projectId && migration.status === MigrationStatus.Success
    } yield migration

    val query = baseQuery.sortBy(_.revision.desc).take(1)
    query.result.headOption
  }

  def nextOpenMigration(projectId: String): SqlAction[Option[Migration], NoStream, Read] = {
    val baseQuery = for {
      migration <- Tables.Migrations
      if migration.projectId === projectId
      if migration.status inSet MigrationStatus.openStates
    } yield migration

    val query = baseQuery.sortBy(_.revision.asc).take(1)
    query.result.headOption
  }

  private def updateBaseQuery(projectId: String, revision: Int) = {
    for {
      migration <- Tables.Migrations
      if migration.projectId === projectId
      if migration.revision === revision
    } yield migration
  }

  def updateMigrationStatus(projectId: String, revision: Int, status: MigrationStatus): FixedSqlAction[Int, NoStream, Write] = {
    updateBaseQuery(projectId, revision).map(_.status).update(status)
  }

  def updateMigrationErrors(projectId: String, revision: Int, errors: JsValue) = {
    updateBaseQuery(projectId, revision).map(_.errors).update(errors)
  }

  def updateMigrationApplied(projectId: String, revision: Int, applied: Int): FixedSqlAction[Int, NoStream, Write] = {
    updateBaseQuery(projectId, revision).map(_.applied).update(applied)
  }

  def updateMigrationRolledBack(projectId: String, revision: Int, rolledBack: Int): FixedSqlAction[Int, NoStream, Write] = {
    updateBaseQuery(projectId, revision).map(_.rolledBack).update(rolledBack)
  }

  def loadByRevision(projectId: String, revision: Int): SqlAction[Option[Migration], NoStream, Read] = {
    val baseQuery = for {
      migration <- Tables.Migrations
      if migration.projectId === projectId && migration.revision === revision
    } yield migration

    baseQuery.take(1).result.headOption
  }

  def distinctUnmigratedProjectIds(): FixedSqlStreamingAction[Seq[String], String, Read] = {
    val baseQuery = for {
      migration <- Tables.Migrations
      if migration.status inSet MigrationStatus.openStates
    } yield migration.projectId

    baseQuery.distinct.result
  }
}