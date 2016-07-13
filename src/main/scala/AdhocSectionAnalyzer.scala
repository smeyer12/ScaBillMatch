/*AdhocSectionAnalyzer: an app. that performs document or section similarity searches starting off CartesianPairs

Following parameters need to be filled in the resources/adhocAnalyzer.conf file:
    nPartitions: Number of partitions in bills_meta RDD
    numTextFeatures: Number of text features to keep in hashingTF
    measureName: Similarity measure used
    inputBillsFile: Bill input file, one JSON per line
    inputPairsFile: CartesianPairs object input file
    outputMainFile: key-key pairs and corresponding similarities, as Tuple2[Tuple2[String,String],Double]
    outputFilteredFile: CartesianPairs passing similarity threshold
*/

import com.typesafe.config._

import org.apache.spark.{SparkConf, SparkContext, SparkFiles}
import org.apache.spark.SparkContext._
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.functions._

import org.apache.spark.ml.feature.{HashingTF, IDF}
import org.apache.spark.ml.feature.{RegexTokenizer, Tokenizer}
import org.apache.spark.ml.feature.NGram
import org.apache.spark.ml.feature.StopWordsRemover

//import org.apache.spark.ml.tuning.{ParamGridBuilder, CrossValidator}

import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions.explode

import scala.collection.mutable.WrappedArray

import org.apache.spark.mllib.linalg.{DenseVector, SparseVector, Vector, Vectors}


object AdhocSectionAnalyzer {

  /*
    Experimental
  */
  def converted(row: scala.collection.Seq[Any]) : Tuple2[String,SparseVector] = { 
    val ret = row.asInstanceOf[WrappedArray[Any]]
    val first = ret(0).asInstanceOf[String]
    val second = ret(1).asInstanceOf[Vector]
    Tuple2(first,second.toSparse)
  }

  //get type of var utility 
  def manOf[T: Manifest](t: T): Manifest[T] = manifest[T]

  def main(args: Array[String]) {

    println(s"\nExample submit command: spark-submit  --class AdhocSectionAnalyzer --master yarn-client --num-executors 30 --executor-cores 3 --executor-memory 10g target/scala-2.10/BillAnalysis-assembly-1.0.jar\n")

    val t0 = System.nanoTime()

    val params = ConfigFactory.load("adhocAnalyzer")
    run(params)

    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0)/1000000000 + "s")
  }

  def run(params: Config) {

    val conf = new SparkConf().setAppName("AdhocSectionAnalyzer")
      .set("spark.dynamicAllocation.enabled","true")
      .set("spark.shuffle.service.enabled","true")

    val spark = new SparkContext(conf)
    val sqlContext = new org.apache.spark.sql.SQLContext(spark)
    import sqlContext.implicits._
    
    val input = sqlContext.read.json(params.getString("adhocAnalyzer.inputBillsFile"))
    val npartitions = (200*(input.count()/100000)).toInt

    val bills = input.repartition(npartitions,col("primary_key"),col("content"))
    bills.explain

    //cannot do cleaning because I tokenize sections on section - digit pattern
    //def cleaner_udf = udf((s: String) => s.replaceAll("(\\d|,|:|;|\\?|!)", ""))
    //val cleaned_df = bills.withColumn("cleaned",cleaner_udf(col("content"))).drop("content")
    //cleaned_df.show(15)

    //tokenizer = Tokenizer(inputCol="text", outputCol="words")
    var tokenizer1 = new RegexTokenizer().setInputCol("content").setOutputCol("sections").setPattern("(SECTION \\d|section \\d)")
    val tokenized1_df = tokenizer1.transform(bills) //cleaned_df)

    //flatten the column
    val flattened_df = tokenized1_df.withColumn("sections", explode($"sections")).drop("cleaned")

    var tokenizer2 = new RegexTokenizer().setInputCol("sections").setOutputCol("words").setPattern("\\W")
    val tokenized2_df = tokenizer2.transform(flattened_df)
 
    //remove stopwords 
    var remover = new StopWordsRemover().setInputCol("words").setOutputCol("filtered")
    val filtered_df = remover.transform(tokenized2_df).drop("words")

    //ngram = NGram(n=2, inputCol="filtered", outputCol="ngram")
    //ngram_df = ngram.transform(tokenized1_df)

    //hashing
    var hashingTF = new HashingTF().setInputCol("filtered").setOutputCol("rawFeatures").setNumFeatures(params.getInt("adhocAnalyzer.numTextFeatures"))
    val featurized_df = hashingTF.transform(filtered_df).drop("filtered")
    featurized_df.show(5)

    var idf = new IDF().setInputCol("rawFeatures").setOutputCol("pre_features")
    //val Array(train, cv) = featurized_df.randomSplit(Array(0.7, 0.3))
    var idfModel = idf.fit(featurized_df)
    val rescaled_df = idfModel.transform(featurized_df).drop("rawFeatures")

    val hashed_bills = featurized_df.select("primary_key","rawFeatures").rdd.map(row => converted(row.toSeq))

    //First, run the hashing step here
    val cartesian_pairs = spark.objectFile[CartesianPair](params.getString("adhocAnalyzer.inputPairsFile")).map(pp => (pp.pk1,pp.pk2))

    var similarityMeasure: SimilarityMeasure = null
    var threshold: Double = 0.0

    params.getString("adhocAnalyzer.measureName") match {
      case "cosine" => {
        similarityMeasure = CosineSimilarity
        //threshold = ???
      }
      case "hamming" => {
        similarityMeasure = HammingSimilarity
        //threshold = ???
      }
      case "manhattan" => {
        similarityMeasure = ManhattanSimilarity
        //threshold = ???
      }
      case "jaccard" => {
        similarityMeasure = JaccardSimilarity
        //threshold = ???
      }
      case other: Any =>
        throw new IllegalArgumentException(
          s"Only hamming, cosine, euclidean, manhattan, and jaccard similarities are supported but got $other."
        )
    }

    val firstjoin = cartesian_pairs.map({case (k1,k2) => (k1, (k1,k2))})
        .join(hashed_bills)
        .map({case (_, ((k1, k2), v1)) => ((k1, k2), v1)}).filter({case ((k1, k2), v1) => (k1 != k2)})

    val matches = firstjoin.map({case ((k1,k2),v1) => (k2, ((k1,k2),v1))})
        .join(hashed_bills)
        .map({case(_, (((k1,k2), v1), v2))=>((k1, k2),(v1, v2))}).mapValues({case (v1,v2) => similarityMeasure.compute(v1.toSparse,v2.toSparse)})
    
    //val matches_df = matches.map({case ((k1,k2), v1)=>(k1, k2, v1)}).filter({case (k1, k2, v1) => ((k1 contains "CO_2006_HB1175") || (k2 contains "CO_2006_HB1175"))}).toDF("pk1","pk2","similarity").groupBy("pk1","pk2").max("similarity")
    val matches_df = matches.map({case ((k1,k2), v1)=>(k1, k2, v1)}).toDF("pk1","pk2","similarity").groupBy("pk1","pk2").max("similarity").select(col("pk1"),col("pk2"),col("max(similarity)").alias("max_similarity"))
    //matches_df.show(1000,false)
    matches_df.write.parquet(params.getString("adhocAnalyzer.outputMainFile"))

    //matches.saveAsObjectFile(params.getString("adhocAnalyzer.outputMainFile"))

    spark.stop()
   }
}