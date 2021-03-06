package org.princeton.billmatch

/*
Application: ExtractCandidates, produce all the pairs of primary keys of the documents satisfying a predicate.
Perform document bucketing using k-means clustering.

Following are the key parameters that need to be filled in the resources/workflow1_makeCartesian.conf file:
	docVersion: document version: consider document pairs having a specific version. E.g. Introduced, Enacted...
        useLSA: whether to use truncated SVD
        numConcepts: number of concepts to use for LSA
        kval: number of clusters for k-means
        onlyInOut: a switch between in-out of state and using both in-out and in-in state pairs
	inputFile: input file, one JSON per line
	outputFile: output file
        outputParquetFile: output parquet sink

Example to explore output in spark-shell:
$ spark-shell --jars target/scala-2.10/BillAnalysis-assembly-1.0.jar 
scala> val mydata = sc.objectFile[CartesianPair]("/user/path/to/files")
mydata: org.apache.spark.rdd.RDD[CartesianPair] = MapPartitionsRDD[3] at objectFile at <console>:27

scala> mydata.take(5)
res1: Array[CartesianPair] = Array()
*/
import com.typesafe.config._

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.spark.sql.DataFrame

import org.apache.spark.ml.linalg.SQLDataTypes.VectorType

import org.apache.spark.mllib.linalg.{Matrix, Matrices}
import org.apache.spark.mllib.linalg.distributed.RowMatrix

import org.apache.spark.mllib.linalg.{
  Vector => OldVector, 
  Vectors => OldVectors, 
  SparseVector => OldSparseVector,
  DenseVector => OldDenseVector,
  VectorUDT => OldVectorUDT}

import org.apache.spark.ml.linalg.{
   Vector => NewVector,
   Vectors => NewVectors,
   DenseVector => NewDenseVector,
   SparseVector => NewSparseVector
}

import java.io._

import org.apache.spark.rdd.RDD

//import org.scalatest.Assertions._

import org.princeton.billmatch.feature._


object ExtractCandidates {

  def main(args: Array[String]) {

    println(s"\nExample submit command: spark-submit --class ExtractCandidates --master yarn --queue production --num-executors 30 --executor-cores 3 --executor-memory 10g target/scala-2.11/BillAnalysis-assembly-2.0.jar\n")

    val t0 = System.nanoTime()

    val params = ConfigFactory.load("workflow1_makeCartesian")

    run(params)

    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0)/1000000000 + "s")
  }

  def run(params: Config) {

    val spark = SparkSession.builder().appName("ExtractCandidates")
      //.config("spark.dynamicAllocation.enabled","true")
      .config("spark.shuffle.service.enabled","true")
      .config("spark.shuffle.memoryFraction","0.7")
      .config("spark.sql.codegen.wholeStage", "true")
      .config("spark.driver.maxResultSize", "10g")
      .getOrCreate()

    import spark.implicits._

    lazy val vv: String = params.getString("workflow1_makeCartesian.docVersion") //like "Enacted"
    lazy val nGramGranularity = params.getInt("workflow1_makeCartesian.nGramGranularity")
    lazy val addNGramFeatures = params.getBoolean("workflow1_makeCartesian.addNGramFeatures")
    lazy val numTextFeatures = params.getInt("workflow1_makeCartesian.numTextFeatures")
    lazy val useLSA = params.getBoolean("workflow1_makeCartesian.useLSA")
    lazy val kval = params.getInt("workflow1_makeCartesian.kval")
    lazy val optimizationLevel = params.getInt("workflow1_makeCartesian.optimizationLevel")
    lazy val predicate_path = params.getString("workflow1_makeCartesian.cat")

    val input = spark.read.json(params.getString("workflow1_makeCartesian.inputFile")).filter($"docversion" === vv).filter(Utils.compactSelector_udf(col("content"))).filter(Utils.lengthSelector_udf(col("content")))
    input.printSchema()
    input.show()

    val npartitions = (4*input.count()/1000).toInt
    val bills = input.repartition(Math.max(npartitions,200),col("primary_key"),col("content"))
    bills.explain

    var features_df = Utils.extractFeatures(bills,numTextFeatures,addNGramFeatures,nGramGranularity).cache()

    val clusters_schema = StructType(Seq(StructField("primary_key",StringType,false),StructField("docversion",StringType, false),StructField("docid",StringType,false),StructField("state",LongType,false),StructField("year",LongType,false),StructField("length",LongType,false),StructField("features", VectorType, false),StructField("prediction",LongType,false)))
    var clusters_df: DataFrame = spark.createDataFrame(spark.sparkContext.emptyRDD[Row], clusters_schema)

    if (useLSA) {
        //Apply low-rank matrix factorization via SVD approach, truncate to reduce the dimensionality

        val dataPart2 = features_df.select("primary_key","docversion","docid","state","year","length").as[(String,String,String,Long,Long,Long)].rdd.zipWithIndex().map(x => (x._1._1,x._1._2,x._1._3,x._1._4,x._1._5,x._1._6,x._2)).toDF("primary_key","docversion","docid","state","year","length","id")

        val dataRDD = features_df.select("features").rdd.map {
              case Row(v: NewVector) => OldVectors.fromML(v)}.cache()
        //.as[org.apache.spark.ml.linalg.Vector].rdd.map(x => Utils.toOld(x)).cache()

        // Compute the top 5 singular values and corresponding singular vectors.
        //320 concepts worked perfectly for 10 states
        val numConcepts = params.getInt("workflow1_makeCartesian.numConcepts")
        features_df = Utils.LSA(spark,dataRDD,numConcepts,numConcepts*2)
        features_df.show()
        features_df.printSchema

        clusters_df = Utils.KMeansSuite(features_df,kval)

        //Setup for splitting by cluster on the step2
        val dataPart1 = clusters_df.select("prediction","features").rdd.map(row => Utils.converter(row.toSeq)).zipWithIndex().map(x => (x._1._1,x._1._2,x._2)).toDF("prediction","features","id")
        //val dataPart1 = clusters_df.select("prediction").withColumn("id",monotonicallyIncreasingId)

        clusters_df = dataPart1.join(dataPart2, dataPart2("id") === dataPart1("id"))

        clusters_df.printSchema()
        clusters_df.show()
        dataPart1.unpersist()
        dataPart2.unpersist()
    } else if (optimizationLevel > 0) {
        clusters_df = Utils.KMeansSuite(features_df,kval)
    } else {
        clusters_df = features_df.withColumn("prediction",lit(-1))
    }
 
    clusters_df.select("primary_key","docversion","docid","state","year","prediction","length","features").write.parquet(params.getString("workflow1_makeCartesian.outputParquetFile"))

    //this does not get filtered
    var bills_meta = clusters_df.select("primary_key","docversion","docid","state","year","prediction","length").as[MetaLabeledDocument].cache()

    var bills_meta_for_bcast = Array[MetaLabeledDocument]()

    if (predicate_path.length() > 0) { 
      val custom_predicate : String = Utils.makeCustomPredicate(predicate_path)        
      bills_meta_for_bcast = clusters_df.filter(custom_predicate).select("primary_key","docversion","docid","state","year","prediction","length").as[MetaLabeledDocument].collect()

      if (params.getBoolean("workflow1_makeCartesian.bothIn")) {
      	bills_meta = clusters_df.filter(custom_predicate).select("primary_key","docversion","docid","state","year","prediction","length").as[MetaLabeledDocument].cache()
      } else {
      	val custom_predicate_ex : String = Utils.makeCustomPredicateExclude(predicate_path)
      	bills_meta = clusters_df.filter(custom_predicate_ex).select("primary_key","docversion","docid","state","year","prediction","length").as[MetaLabeledDocument].cache()
      }
    } else {
      bills_meta_for_bcast = clusters_df.select("primary_key","docversion","docid","state","year","prediction","length").as[MetaLabeledDocument].collect()
    }

    var bills_meta_bcast = spark.sparkContext.broadcast(bills_meta_for_bcast)

    var cartesian_pairs = bills_meta.rdd.coalesce(params.getInt("workflow1_makeCartesian.nPartitions"))
                          .map(x => Utils.pairup(x,bills_meta_bcast, params.getBoolean("workflow1_makeCartesian.onlyInOut"),optimizationLevel)).filter({case (dd,ll) => (ll.length > 0)}).map({case(k,v) => v}).flatMap(x => x) //.groupByKey()    

    cartesian_pairs.saveAsObjectFile(params.getString("workflow1_makeCartesian.outputFile"))
    spark.stop()
   }
}

case class MetaLabeledDocument(primary_key: String, prediction: Option[Long], state: Long, docid: String, docversion: String, year: Long, length: Long)
case class CartesianPair(pk1: String, pk2: String)
