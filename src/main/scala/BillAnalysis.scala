import org.apache.spark.{SparkConf, SparkContext, SparkFiles}
import org.apache.spark.SparkContext._
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.functions._

import scala.collection.mutable.ListBuffer
//import org.apache.spark.mllib.linalg.Vector

import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.WrappedArray


object BillAnalysis {

  def preprocess (line: String) : ArrayBuffer[Long] = {
    val distinct_tokens = Stemmer.tokenize(line).distinct //.mkString
    //println(distinct_tokens)

    var wGrps = ArrayBuffer.empty[Long]
    val grpSize = 5
    for (n <- 0 to distinct_tokens.length-grpSize) {
      val cgrouplst = distinct_tokens.drop(n).take(grpSize)
      var cgrp = ""
      //var cgrp = " ".join(cgrouplst)
      for (tok <- cgrouplst) {
         cgrp += tok
      }
      wGrps += cgrp.hashCode()
    }
    wGrps.distinct
  }


  def extractSimilarities = (s_wGrps: WrappedArray[Long], m_wGrps: WrappedArray[Long]) => {
    val model_size = (m_wGrps.length+s_wGrps.length)/2.
    val matchCnt = m_wGrps.intersect(s_wGrps).length
    matchCnt/model_size * 100.
  }

  def main(args: Array[String]) {
    val t0 = System.nanoTime()
    val conf = new SparkConf().setAppName("TextProcessing")
    val spark = new SparkContext(conf)
    val sqlContext = new SQLContext(spark)

    //println(sys.env("PWD"))
    //register UDFs
    sqlContext.udf.register("df_preprocess",preprocess _)
    val df_preprocess_udf = udf(preprocess _)
    sqlContext.udf.register("df_extractSimilarities",extractSimilarities)
    val df_extractSimilarities_udf = udf(extractSimilarities)


    var ipath_str = sys.env("PWD")
    ipath_str = "file://".concat(ipath_str).concat("/data/bills.json")
    var bills = sqlContext.read.json(ipath_str)
    bills.printSchema()
  
    //Repartition: key1 to solving current memory issues: (java heap OutOfMemory)
    var bills_rept = bills.repartition(col("content")).cache()
    bills_rept.explain

    //apply necessary UDFs
    bills_rept = bills_rept.withColumn("hashed_content", df_preprocess_udf(col("content"))).drop("content")
    bills_rept.registerTempTable("bills_df")

    //JOIN on !=
    val cartesian = sqlContext.sql("SELECT a.primary_key as pk1, b.primary_key as pk2, a.hashed_content as hc1, b.hashed_content as hc2 FROM bills_df a JOIN bills_df b ON a.primary_key != b.primary_key WHERE a.year < b.year AND a.state != b.state")
    cartesian.printSchema()
    //val matches = cartesian.withColumn("similarities", df_extractSimilarities_udf(col("hc1"),col("hc2"))).select("similarities","pk1","pk2")
    val matches = cartesian.withColumn("similarities", df_extractSimilarities_udf(col("hc1"),col("hc2"))).select("similarities")
    //matches.printSchema() 

    //for deugging
    //for (word <- matches.collect()) {
    //  println(word)
    //}

    //matches.write.parquet("/user/alexeys/test_output")
    var opath_str = sys.env("PWD")
    opath_str = "file://".concat(opath_str).concat("/test_output")
    matches.write.parquet(opath_str)

    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0)/1000000000 + "s")

    spark.stop()
   }
}
