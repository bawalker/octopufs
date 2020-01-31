import com.pg.bigdata.utils.Promotor
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.lit

object TestPartitionCopy extends App{
  implicit val spark: SparkSession = SparkSession.builder().
    appName("NAS_").
    master("local").
    getOrCreate()
  implicit val c = spark.sparkContext.hadoopConfiguration
  TestUtils.setupTestEnv()

  //modify partition content to make sure later, that partition was actually exchanged
  val modifiedDLT = spark.table("STORE_SALES_DLT").filter("mm_time_perd_end_date = '2019-10-31'").
    withColumn("prod_id",lit("exchange"))
  modifiedDLT.localCheckpoint()
  modifiedDLT.write.partitionBy("mm_time_perd_end_date").
    option("path","data/testfield/STORE_SALES_DLT").insertInto("STORE_SALES_DLT")

  //#test1
  assert(spark.table("STORE_SALES_DLT").filter("mm_time_perd_end_date = '2019-12-31'").count() != 0, "check if partition to be promoted exists in source")
  println("Partitions in DLT")
  spark.table("STORE_SALES_DLT").select("mm_time_perd_end_date").distinct.show()

  assert(spark.table("STORE_SALES_SFCT").filter("mm_time_perd_end_date = '2019-12-31'").count() == 0, "check if partition to be promoted does not exists in target")
  println("Partitions in SFCT")
  spark.table("STORE_SALES_SFCT").select("mm_time_perd_end_date").distinct.show()

  Promotor.copyTablePartitions("STORE_SALES_DLT","STORE_SALES_SFCT",Seq("2019-12-31"), 1)
  println("SFCT partitions after promotion:")
  spark.table("STORE_SALES_SFCT").select("mm_time_perd_end_date").distinct.show()
  assert(spark.table("STORE_SALES_SFCT").filter("mm_time_perd_end_date = '2019-12-31'").count()!=0, "check if partition was correctly copied")
  assert(spark.table("STORE_SALES_SFCT").select("mm_time_perd_end_date").distinct.count==5, "check if other partitions remained")


}
