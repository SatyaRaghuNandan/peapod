package peapod

import org.apache.commons.codec.binary.Base64
import org.apache.hadoop.io.MD5Hash

import scala.reflect.ClassTag

abstract class Task [+T: ClassTag] {
  val p: Peapod

  /**
    * Base name of this Task, this can be extended if name is being defined explicitly in inherited classes
    */
  lazy val baseName: String = this.getClass.getName

  /**
    * The name of this Task as used for defining uniqueness, all Tasks with the same name should be globally identical
    */
  lazy val name: String = baseName

  /**
    * Names of this Task as used for versioning
    */
  lazy val versionName: String = name

  /**
    * Short description of what this task does
    */
  val description: String = ""

  /**
    * If this Task is being stored to persistent storage (disk, DB, etc.) or not
    */
  val storable: Boolean

  /**
    * Determined how this Task will be cached in memory
    */
  val persist: Persist = Auto

  /**
    * The version of this Task, any changes to a Task's logic should have a corresponding version change
    */
  val version: String = "1"

  /**
    * The Tasks which this Task depends on
    */
  var children: List[Task[_]] = Nil

  /**
    * The directory where this Task's main directory would be stored to disk if it is to be stored to disk. This is the
    * directory without the version or other subdirectories attached to it but merely the task itself. This is located
    * here rather than in StorableTask to allow traits to better override it
    */
  lazy val baseDir = p.path + "/" + baseName

  /**
    * The directory where this Task would be stored to disk if it is to be stored to disk. This is located here rather
    * than in StorableTask to allow traits to better override it
    */
  lazy val dir = p.path + "/" + baseName + "/" + storageRecursiveVersion()

  /**
    * The recursive version that is used for storage to disk, it is the recursiveVersionShort unless recursive
    * versioning is disabled in which case it is "latest"
    */
  protected def storageRecursiveVersion() = {
    if(p.recursiveVersioning) {
      recursiveVersionShort
    } else {
      "latest"
    }
  }

  /**
    * Generates the output of this Task, potentially saving it to persistent storage in the process
    */
  def build(): T

  protected def pea[D: ClassTag](t: Task[D]): WrappedTask[D] = {
    val child = t
    children = children :+ child
    new WrappedTask[D](p, child)
  }

  /**
    * Does the output for this Task already exist in persistent storage
    */
  def exists(): Boolean

  /**
    * Remove the output of this Task from persistent storage, if there is no output then this should be a no-op rather
    * than throwing an error
    */
  def delete()

  /**
    * Remove the output of this Task from persistent storage if the recursive version differs from the current version,
    * if there is no output then this should be a no-op rather than throwing an error
    */
  def deleteOtherVersions()

  /**
    * Loads the output of this task from persistent storage if possible, may throw an exception if the output does
    * not exist
    */
  def load() : T

  /**
    * The recursively generated version for this Task, combining it's name+version and the name+version of it's
    * children recursively
    */
  lazy val recursiveVersion: List[String] = {
    //Sorting first so that changed in ordering of peas doesn't cause new version
    versionName + ":" + version :: children.sortBy(_.versionName).flatMap(_.recursiveVersion.map("-" + _))
  }

  /**
    * The recursively generated version for this Task in short form generated by hashing the long form recursiveVersion
    */
  def recursiveVersionShort: String = {
    val bytes = MD5Hash.digest(recursiveVersion.mkString("\n")).getDigest
    val encodedBytes = Base64.encodeBase64URLSafeString(bytes)
    new String(encodedBytes)
  }

  /**
    * Generates a string representation of the metadata for this task including name, version, description and these
    * for all Tasks that this task is dependent on
    *
    * @return String representation of the metadata of this task
    */
  def metadata(): String = {
    val allChildren = children.distinct
    val out =
      description match {
        case "" => name + ":" + version :: Nil
        case _ => name + ":" + version ::
          description :: Nil
      }

    val childMetadata =   allChildren.flatMap{t => t.description match {
      case "" =>
        "-" + t.name + ":" + t.version :: Nil
      case _ => "-" + t.name + ":" + t.version ::
        "--" + t.description ::
        Nil
    }}
    (out ::: childMetadata).mkString("\n")
  }

  /**
    * String version of a Task is it's name
    */
  override def toString: String = {
    name
  }

  /**
    * Hashcode for a Task is the hash code of it's name
    */
  override def hashCode: Int = {
    toString.hashCode
  }

  /**
    * Equality for a Task is based on it's name
    */
  override def equals(o: Any) = {
    o match {
      case t: Task[_] => t.toString == this.toString
      case _ => false
    }
  }

  /**
    * Helper method that gives the children of this Task as an array
    */
  def childrenArray() = {
    children.toArray
  }
}
