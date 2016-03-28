package models.project

import java.sql.Timestamp
import java.util.Date

import db.Storage
import models.author.Dev
import models.project.ChannelColors.ChannelColor
import models.project.Version.PendingVersion
import org.apache.commons.io.FileUtils
import org.spongepowered.plugin.meta.PluginMetadata
import play.api.Play.current
import play.api.cache.Cache
import plugin.{Pages, PluginFile, ProjectManager}
import util.{Cacheable, PendingAction}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * Represents an Ore package.
  *
  * <p>Note: As a general rule, do not handle actions / results in model classes</p>
  *
  * <p>Note: Instance variables should be private unless they are database
  * properties</p>
  *
  * @param id                     Unique identifier
  * @param createdAt              Instant of creation
  * @param pluginId               Plugin ID
  * @param name                   Name of plugin
  * @param owner                  The owner Author for this project
  * @param authors                Authors who work on this project
  * @param homepage               The external project URL
  * @param recommendedVersionId   The ID of this project's recommended version
  * @param categoryId             The ID of this project's category
  * @param views                  How many times this project has been views
  * @param downloads              How many times this project has been downloaded in total
  * @param starred                How many times this project has been starred
  */
case class Project(id: Option[Int], private var createdAt: Option[Timestamp], pluginId: String,
                   private var name: String, owner: String, authors: List[String],
                   homepage: Option[String], private var recommendedVersionId: Option[Int],
                   var categoryId: Int = -1, views: Int, downloads: Int, starred: Int) {

  def this(pluginId: String, name: String, owner: String, authors: List[String], homepage: String) = {
    this(None, None, pluginId, name, owner, authors, Option(homepage), None, 0, 0, 0, 0)
  }

  def getOwner: Dev = Dev(owner) // TODO: Teams

  def getAuthors: List[Dev] = for (author <- authors) yield Dev(author) // TODO: Teams

  /**
    * Returns the Timestamp instant that this Project was created or None if it
    * has not yet been created.
    *
    * @return Instant of creation or None if has not been created
    */
  def getCreatedAt: Option[Timestamp] = this.createdAt

  /**
    * Method called when this Project is created in the database.
    */
  def onCreate() = this.createdAt = Some(new Timestamp(new Date().getTime))

  /**
    * Returns the name of this project.
    *
    * @return Name of project
    */
  def getName: String = this.name

  /**
    * Sets the name of this project and performs all the necessary renames.
    *
    * @param name   New name
    * @return       Future result
    */
  def setName(name: String): Future[Int] = {
    val f = Storage.updateProjectString(this, table => table.name, name)
    f.onSuccess {
      case i =>
        ProjectManager.renameProject(this.owner, this.name, name)
        this.name = name
    }
    f
  }

  /**
    * Returns all Channels belonging to this Project.
    *
    * @return All channels in project
    */
  def getChannels: Future[Seq[Channel]] = Storage.getChannels(this.id.get)

  /**
    * Returns the Channel in this project with the specified name.
    *
    * @param name   Name of channel
    * @return       Channel with name, if present, None otherwise
    */
  def getChannel(name: String): Future[Option[Channel]] = Storage.optChannel(this.id.get, name)

  def getChannel(color: ChannelColor): Future[Option[Channel]] = Storage.optChannel(this.id.get, color.id)

  /**
    * Creates a new Channel for this project with the specified name.
    *
    * @param name   Name of channel
    * @return       New channel
    */
  def newChannel(name: String, color: ChannelColor): Try[Channel] = Try {
    Storage.now(getChannels) match {
      case Failure(thrown) => throw thrown
      case Success(channels) => if (channels.size >= Channel.MAX_AMOUNT) {
        throw new IllegalArgumentException("Project has reached maximum channel capacity.")
      }
    }
    Storage.now(Storage.createChannel(new Channel(name, color, this.id.get))) match {
      case Failure(thrown) => throw thrown
      case Success(channel) => channel
    }
  }

  /**
    * Returns this Project's recommended version.
    *
    * @return Recommended version
    */
  def getRecommendedVersion: Future[Version] = Storage.getVersion(this.recommendedVersionId.get)

  /**
    * Updates this project's recommended version.
    *
    * @param version  Version to set
    * @return         Result
    */
  def setRecommendedVersion(version: Version) = {
    Storage.updateProjectInt(this, table => table.recommendedVersionId, version.id.get).onSuccess {
      case i => this.recommendedVersionId = version.id
    }
  }

  /**
    * Returns all Versions belonging to this Project.
    *
    * @return All versions in project
    */
  def getVersions: Seq[Version] = Storage.now(Storage.getAllVersions(this.id.get)) match {
    case Failure(thrown) =>  throw thrown
    case Success(versions) => versions
  }

  /**
    * Returns true if this Project already exists.
    *
    * @return True if project exists, false otherwise
    */
  def exists: Boolean = {
    Storage.now(Storage.isDefined(Storage.getProject(this.owner, this.name))).isSuccess
  }

  /**
    * Immediately deletes this projects and any associated files.
    *
    * @return Result
    */
  def delete: Try[Unit] = Try {
    Storage.now(Storage.deleteProject(this)) match {
      case Failure(thrown) => throw thrown
      case Success(i) =>
        FileUtils.deleteDirectory(ProjectManager.getProjectDir(this.owner, this.name).toFile)
        FileUtils.deleteDirectory(Pages.getDocsDir(this.owner, this.name).toFile)
    }
  }

  override def hashCode: Int = this.id.get.hashCode

  override def equals(o: Any): Boolean = {
    o.isInstanceOf[Project] && o.asInstanceOf[Project].id.get == this.id.get
  }

}

object Project {

  /**
    * Represents a Project with an uploaded plugin that has not yet been
    * created.
    *
    * @param project        Pending project
    * @param firstVersion   Uploaded plugin
    */
  case class PendingProject(project: Project, firstVersion: PluginFile) extends PendingAction[Project] with Cacheable {

    private var pendingVersion: Option[PendingVersion] = None

    /**
      * Creates a new PendingVersion for this PendingProject
      *
      * @return New PendingVersion
      */
    def initFirstVersion: PendingVersion = {
      val meta = this.firstVersion.getMeta.get
      val version = Version.fromMeta(this.project, meta)
      val pending = Version.setPending(project.owner, project.name,
        Channel.getSuggestedNameForVersion(version.versionString), version, this.firstVersion)
      this.pendingVersion = Some(pending)
      pending
    }

    /**
      * Returns this PendingProject's PendingVersion
      *
      * @return PendingVersion
      */
    def getPendingVersion: Option[PendingVersion] = this.pendingVersion

    override def complete: Try[Project] = Try {
      free()
      ProjectManager.createProject(this) match {
        case Failure(thrown) => throw thrown
        case Success(newProject) => ProjectManager.createVersion(this.pendingVersion.get) match {
          case Failure(thrown) => throw thrown
          case Success(newVersion) =>
            newProject.setRecommendedVersion(newVersion)
            newProject
        }
      }
    }

    override def cancel() = {
      free()
      this.firstVersion.delete()
      if (project.exists) {
        project.delete
      }
    }

    override def getKey: String = this.project.owner + '/' + this.project.name

  }

  /**
    * Marks the specified Project as pending for later use.
    *
    * @param project        Project that is pending
    * @param firstVersion   Uploaded plugin
    */
  def setPending(project: Project, firstVersion: PluginFile) =  {
    PendingProject(project, firstVersion).cache()
  }

  /**
    * Returns the PendingProject of the specified owner and name, if any.
    *
    * @param owner  Project owner
    * @param name   Project name
    * @return       PendingProject if present, None otherwise
    */
  def getPending(owner: String, name: String): Option[PendingProject] = {
    Cache.getAs[PendingProject](owner + '/' + name)
  }

  /**
    * Creates a new Project from the specified PluginMetadata.
    *
    * @param owner  Owner of project
    * @param meta   PluginMetadata object
    * @return       New project
    */
  def fromMeta(owner: String, meta: PluginMetadata): Project = {
    new Project(meta.getId, meta.getName, owner, meta.getAuthors.toList, meta.getUrl)
  }

}
