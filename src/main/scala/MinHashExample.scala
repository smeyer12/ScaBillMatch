package org.apache.spark.ml.feature

import com.typesafe.config._

import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.sql.Dataset

import org.apache.spark.{SparkConf, SparkContext, SparkFiles}
import org.apache.spark.SparkContext._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector, Vectors}

import org.apache.spark.sql.Row
import org.apache.spark.sql.types._


object MinHashLSHExample {

  /**
   * Compute the precision and recall of approximate similarity join
   * @param lsh The lsh instance
   * @param datasetA One of the datasets to join
   * @param datasetB Another dataset to join
   * @param threshold The threshold for the distance of record pairs
   * @tparam T The class type of lsh
   * @return A tuple of two doubles, representing precision and recall rate
   */
  def calculateApproxSimilarityJoin[T <: LSHModel[T]](
      lsh: LSH[T],
      datasetA: Dataset[_],
      datasetB: Dataset[_],
      threshold: Double): (Double, Double) = {
    val model = lsh.fit(datasetA)
    val inputCol = model.getInputCol

    // Compute expected
    val distUDF = udf((x: Vector, y: Vector) => model.keyDistance(x, y), DataTypes.DoubleType)
    val expected = datasetA.as("a").crossJoin(datasetB.as("b"))
      .filter(distUDF(col(s"a.$inputCol"), col(s"b.$inputCol")) < threshold)

    // Compute actual
    val actual = model.approxSimilarityJoin(datasetA, datasetB, threshold)

    //SchemaUtils.checkColumnType(actual.schema, "distCol", DataTypes.DoubleType)
    //assert(actual.schema.apply("datasetA").dataType
    //  .sameType(model.transformSchema(datasetA.schema)))
    //assert(actual.schema.apply("datasetB").dataType
    //  .sameType(model.transformSchema(datasetB.schema)))

    // Compute precision and recall
    val correctCount = actual.filter(col("distCol") < threshold).count().toDouble
    (correctCount / actual.count(), correctCount / expected.count())
  }


  def calculateApproxSimilarityJoin2[T <: LSHModel[T]](
      lsh: LSH[T],
      datasetA: Dataset[_],
      datasetB: Dataset[_],
      threshold: Double): Unit = {
    val model = lsh.fit(datasetA)
    val inputCol = model.getInputCol

    // Compute actual
    val actual = model.approxSimilarityJoin(datasetA, datasetB, threshold)
    actual.show()
  }


  def main(args: Array[String]) {

    //Test with actual text data
    val spark = SparkSession.builder().appName("MinHashExample")
      //.config("spark.dynamicAllocation.enabled","true")
      .config("spark.shuffle.service.enabled","true")
      .config("spark.shuffle.memoryFraction","0.6")
      .config("spark.sql.codegen.wholeStage", "true")
      .config("spark.driver.maxResultSize", "10g")
      .getOrCreate()

    import spark.implicits._

    val params = ConfigFactory.load("billAnalyzer")

    val data1 = {
      for (i <- 0 until 20) yield Vectors.sparse(100, (5 * i until 5 * i + 5).map((_, 1.0)))
    }
    val df1 = spark.createDataFrame(data1.map(Tuple1.apply)).toDF("keys")

    val data2 = {
      for (i <- 0 until 30) yield Vectors.sparse(100, (3 * i until 3 * i + 3).map((_, 1.0)))
    }
    val df2 = spark.createDataFrame(data2.map(Tuple1.apply)).toDF("keys")

    //Test with actual text data

    def compactSelector_udf = udf((s: String) => {

       val probe = s.toLowerCase()

       val compactPattern = "compact".r
       val isCompact = compactPattern.findFirstIn(probe).getOrElse("")

       val uniformPattern = "uniform".r
       val isUniform = uniformPattern.findFirstIn(probe).getOrElse("")

       (isCompact.isEmpty() && isUniform.isEmpty())
    })

    val vv: String = params.getString("makeCartesian.docVersion") //like "Enacted"
    val input = spark.read.json(params.getString("makeCartesian.inputFile")).filter($"docversion" === vv).filter(compactSelector_udf(col("content")))

    val npartitions = (4*input.count()/1000).toInt
    val bills = input.repartition(Math.max(npartitions,200),col("primary_key"),col("content"))
    bills.explain

    val nGramGranularity = params.getInt("makeCartesian.nGramGranularity")
    val addNGramFeatures = params.getBoolean("makeCartesian.addNGramFeatures")
    val numTextFeatures = params.getInt("makeCartesian.numTextFeatures")

    def cleaner_udf = udf((s: String) => s.replaceAll("(\\d|,|:|;|\\?|!)", ""))
    val cleaned_df = bills.withColumn("cleaned",cleaner_udf(col("content"))) //.drop("content")

    //tokenizer = Tokenizer(inputCol="text", outputCol="words")
    var tokenizer = new RegexTokenizer().setInputCol("cleaned").setOutputCol("words").setPattern("\\W")
    val tokenized_df = tokenizer.transform(cleaned_df)

    //remove stopwords 
    var remover = new StopWordsRemover().setInputCol("words").setOutputCol("filtered")
    var prefeaturized_df = remover.transform(tokenized_df).drop("words")

    val ngram = new NGram().setN(nGramGranularity).setInputCol("filtered").setOutputCol("ngram")
    val ngram_df = ngram.transform(prefeaturized_df)

    prefeaturized_df = ngram_df.select(col("primary_key"),col("content"),col("docversion"),col("docid"),col("state"),col("year"),col("ngram").alias("combined"))

    //hashing
    var hashingTF = new HashingTF().setInputCol("combined").setOutputCol("keys").setNumFeatures(numTextFeatures)
    val featurized_df = hashingTF.transform(prefeaturized_df).drop("combined")

    ///end 
    val mh = new MinHashLSH()
      .setNumHashTables(20)
      .setInputCol("keys")
      .setOutputCol("values")
      .setSeed(12345)

    //val (precision, recall) = calculateApproxSimilarityJoin(mh, df1, df2, 0.5)
    //assert(precision == 1.0)
    //assert(recall >= 0.7)
    val part1 = featurized_df.filter(col("state") === 29).cache()
    val part2 = featurized_df.filter(col("state") === 5).cache()
    calculateApproxSimilarityJoin2(mh,part1,part2,0.1)
 
    spark.stop()
  }
}
