package com.pg.bigdata.utils.acl

import java.util.concurrent.Executors

import com.pg.bigdata.utils.fs._
import com.pg.bigdata.utils.metastore._
import com.pg.bigdata.utils.fs.FSOperationResult
import com.pg.bigdata.utils.helpers.ConfigSerDeser
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.permission.{AclEntry, AclEntryScope, AclEntryType, FsAction}
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import collection.JavaConverters._
import scala.util.Try

object AclManager extends Serializable {

  def modifyTableACLs(db: String, tableName: String, newPermission: FSPermission, partitionCount: Int = 30)
                     (implicit spark: SparkSession, confEx: Configuration): Dataset[FSOperationResult] = {
    import collection.JavaConverters._

    val sdConf = new ConfigSerDeser(confEx)
    val loc = getTableLocation(db, tableName)
    val files = getListOfTableFiles(db, tableName).toList

    getFileSystem(confEx, loc).modifyAclEntries(new Path(getRelativePath(loc)), Seq(AclManager.getAclEntry(newPermission.getDefaultLevelPerm())).asJava)

    println(files(0))
    println("Files to process: " + files.length)
    modifyACL(files,loc, partitionCount,sdConf,newPermission)

  }

  def modifyACL(paths: List[String], parentFolderURI: String, partitionCount: Int, sdConf: ConfigSerDeser, newFsPermission: FSPermission)
               (implicit spark: SparkSession): Dataset[FSOperationResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val res = spark.sparkContext.parallelize(paths, partitionCount).mapPartitions(part => {
      val conf = sdConf.get()
      val y = AclManager.getAclEntry(newFsPermission)
      val fs = getFileSystem(conf, parentFolderURI)
      part.map(x => Future {
        Try({
          fs.modifyAclEntries(new Path(x), Seq(y).asJava)
          (x, true)
        }).getOrElse((x, false)) //todo make sure it executes only once
      }).map(x => Await.result(x, 1.minute))
    }
    )
    import spark.implicits._
    spark.createDataset(res).toDF("path", "success").as[FSOperationResult]
  }

  def getAclEntry(p: FSPermission): AclEntry = {
    val ptype = if (p.scope == p.USER) AclEntryType.USER
    else if (p.scope == p.GROUP) AclEntryType.GROUP
    else if (p.scope == p.OTHER) AclEntryType.OTHER
    else AclEntryType.MASK

    val perm = FsAction.getFsAction(p.permission)
    if (perm == null)
      throw new Exception("Provided permission " + p.permission + " is not valid for FsAction")
    val level = AclEntryScope.valueOf(p.level)
    val x = new AclEntry.Builder()
    val y = x.
      setType(ptype).
      setPermission(perm).
      setScope(level).
      setName(p.granteeObjectId).build()
    println(y.toString)
    y
  }

  def modifyFolderACLs(folderUri: String, newPermission: FSPermission, partitionCount: Int = 30, driverParallelism: Int = 1000)(implicit spark: SparkSession, confEx: Configuration): DataFrame = {
    implicit val pool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(driverParallelism))
    val sdConf = new ConfigSerDeser(confEx)
    val fs = getFileSystem(confEx, folderUri)
    val elements = listRecursively(fs, new Path(folderUri))
    val folders = elements.filter(_.isDirectory).map(_.path)
    val files = elements.filter(!_.isDirectory).map(_.path)

    println(files(0))
    println("Files to process: " + files.length)
    println("Folders to process: " + folders.length)

    println("Changing file and folders ACCESS ACLs: " + files.length)
    val resAccess = modifyACL(elements.map(_.path).toList, folderUri,partitionCount,sdConf,newPermission)
    println("Changing folders Default ACLs: " + folders.length)
    val resDefault = modifyACL(folders.toList,folderUri,partitionCount,sdConf,newPermission.getDefaultLevelPerm())
    resAccess.withColumn("acl_type", lit("ACCESS")).
      union(resDefault.withColumn("acl_type", lit("DEFAULT")))
  }

  case class FSPermission(scope: String, permission: String, level: String, granteeObjectId: String) {
    val USER: String = "user"
    val GROUP: String = "group"
    val OTHER: String = "other"
    val MASK: String = "mask"

    def getDefaultLevelPerm(): AclManager.FSPermission = FSPermission(scope, permission, "DEFAULT", granteeObjectId)
  }

  //def modifyACL()
}
