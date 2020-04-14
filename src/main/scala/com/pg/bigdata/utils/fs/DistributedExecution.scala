package com.pg.bigdata.utils.fs

import com.pg.bigdata.utils.fs
import com.pg.bigdata.utils.helpers.ConfigSerDeser
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ForkJoinPool
import scala.concurrent.{Await, ExecutionContext, Future}


object DistributedExecution extends Serializable {


  def copyFolder(sourceFolderUri: String, targetLocationUri: String, partitionCount: Int = 192)(implicit spark: SparkSession, confEx: Configuration): Array[FSOperationResult] = {
    val srcFs = getFileSystem(confEx, sourceFolderUri)
    val exec = new ForkJoinPool(partitionCount)
    val pool = ExecutionContext.fromExecutor(exec)
    val sourceFileList = listLevel(srcFs, Array(new Path(sourceFolderUri)))(pool).filter(!_.isDirectory).map(_.path) //filter is to avoid copying folders (folders will get created where copying files). Caveat: empty folders will not be copied
    val targetFileList = sourceFileList.map(_.replaceAll(sourceFolderUri, targetLocationUri)) //uri to work on differnt fikle systems
    println(targetFileList.head)
    val paths = sourceFileList.zip(targetFileList).map(x => Paths(x._1, x._2))

    println(paths.head)
    copyFiles(sourceFolderUri, targetLocationUri, paths, partitionCount)

  }

  def copyFiles(sourceFolderUri: String, targetLocationUri: String, paths: Seq[Paths],
                partitionCount: Int, attempt: Int = 0)
               (implicit spark: SparkSession, confEx: Configuration): Array[FSOperationResult] = {
    val confsd = new ConfigSerDeser(confEx)
    val requestProcessed = spark.sparkContext.longAccumulator("CopyFilesProcessedCount")
    val res = spark.sparkContext.parallelize(paths, partitionCount).mapPartitions(x => {
      val conf = confsd.get()
      val srcFs = getFileSystem(conf, sourceFolderUri)
      val trgFs = getFileSystem(conf, targetLocationUri)
      x.map(paths => {
        requestProcessed.add(1)
        FSOperationResult(paths.sourcePath, fs.copySingleFile(conf, paths.sourcePath, paths.targetPath, srcFs, trgFs))
      })
    }).collect()
    val failed = res.filter(!_.success)
    println("Number of files copied properly: " + res.count(_.success))
    println("Files with errors: " + failed.length)
    if (failed.isEmpty) res
    else if (failed.length == paths.length || attempt > 4)
      throw new Exception("Copy of files did not succeed - please check why and here are some of them: \n" + failed.map(_.path).slice(0, 10).mkString("\n"))
    else {
      val failedPaths = paths.map(_.sourcePath).filter(x => failed.map(_.path).contains(x))
      val pathsForReprocessing = paths.filter(x => failedPaths.contains(x.sourcePath))
      println("Reprocessing " + failedPaths.length + " of failed paths...")
      res.filter(_.success) ++ copyFiles(sourceFolderUri, targetLocationUri, pathsForReprocessing, partitionCount, attempt + 1)
    }
  }
/*
  private def moveFiles(relativePaths: Seq[Paths], sourceFolderUri: String, partitionCount: Int = 32, attempt: Int = 0)
                       (implicit spark: SparkSession, confEx: Configuration): Array[FSOperationResult] = {
    println("Starting moveFiles. Paths to be moved: " + relativePaths.size)

    val requestProcessed = spark.sparkContext.longAccumulator("MoveFilesProcessedCount")
    val sdConf = new ConfigSerDeser(confEx)
    val res = spark.sparkContext.parallelize(relativePaths, partitionCount).mapPartitions(x => {
      val conf = sdConf.get()
      val srcFs = getFileSystem(conf, sourceFolderUri) //move can be done only within single fs, which makes sense :)
      x.map(paths => {
        requestProcessed.add(1)
        println("Executor paths: " + paths)
        Future(paths, srcFs.rename(new Path(paths.sourcePath), new Path(paths.targetPath))) //todo this fails if folder structure for the file does not exist
      })
    }).map(x => Await.result(x, 120.seconds)).map(x => FSOperationResult(x._1.sourcePath, x._2)).collect()
    println("Number of files moved properly: " + res.count(_.success))
    println("Files with errors: " + res.count(!_.success))
    val failed = res.filter(!_.success)

    if (failed.isEmpty) res
    else if (failed.length == relativePaths.length || attempt > 4)
      throw new Exception("Move of files did not succeed - please check why and here are some of them: \n" + failed.map(_.path).slice(0, 10).mkString("\n"))
    else {
      val failedPaths = relativePaths.map(_.sourcePath).filter(x => failed.map(_.path).contains(x))
      val pathsForReprocessing = relativePaths.filter(x => failedPaths.contains(x.sourcePath))
      println("Reprocessing " + failedPaths.length + " of failed paths...")
      res.filter(_.success) ++ moveFiles(pathsForReprocessing, sourceFolderUri, partitionCount, attempt + 1)
    }
  }

 */


}
