/*AdhocAnalyzer: an app. that performs document or section similarity searches starting off CartesianPairs

Following parameters need to be filled in the resources/adhocAnalyzer.conf file:
    nPartitions: Number of partitions in bills_meta RDD
    numTextFeatures: Number of text features to keep in hashingTF
    measureName: Distance measure used
    inputBillsFile: Bill input file, one JSON per line
    inputPairsFile: CartesianPairs object input file
    outputMainFile: key-key pairs and corresponding similarities, as Tuple2[Tuple2[Long,Long],Double]
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

import scala.collection.mutable.WrappedArray

import org.apache.spark.mllib.linalg.{DenseVector, SparseVector, Vector, Vectors}

import org.dianahep.histogrammar._
import org.dianahep.histogrammar.histogram._

import java.io._

import org.graphframes._

object AdhocAnalyzer {

  /*
    Experimental
  */
  def converted(row: scala.collection.Seq[Any]) : Tuple2[Long,SparseVector] = { 
    val ret = row.asInstanceOf[WrappedArray[Any]]
    val first = ret(0).asInstanceOf[Long]
    val second = ret(1).asInstanceOf[Vector]
    Tuple2(first,second.toSparse)
  }

  //get type of var utility 
  def manOf[T: Manifest](t: T): Manifest[T] = manifest[T]

  def main(args: Array[String]) {

     println(s"\nExample submit command: spark-submit  --class AdhocAnalyzer --master yarn-client --num-executors 30 --executor-cores 3 --executor-memory 10g target/scala-2.10/BillAnalysis-assembly-1.0.jar\n")

    val t0 = System.nanoTime()

    val params = ConfigFactory.load("adhocAnalyzer")
    run(params)

    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0)/1000000000 + "s")
  }

  def run(params: Config) {

    val conf = new SparkConf().setAppName("AdhocAnalyzer")
      .set("spark.dynamicAllocation.enabled","true")
      .set("spark.shuffle.service.enabled","true")

    val spark = new SparkContext(conf)
    val sqlContext = new org.apache.spark.sql.SQLContext(spark)
    import sqlContext.implicits._

    val bills = sqlContext.read.json(params.getString("adhocAnalyzer.inputBillsFile"))
    bills.repartition(col("primary_key"))
    bills.explain
    //bills.printSchema()
    //bills.show() 

    //tokenizer = Tokenizer(inputCol="text", outputCol="words")
    var tokenizer = new RegexTokenizer().setInputCol("content").setOutputCol("words").setPattern("\\W")
    val tokenized_df = tokenizer.transform(bills)
    //tokenized_df.show(5,false)

    //remove stopwords 
    var remover = new StopWordsRemover().setInputCol("words").setOutputCol("filtered")
    val filtered_df = remover.transform(tokenized_df).drop("words")
    //filtered_df.printSchema()
    //filtered_df.show()

    //ngram = NGram(n=2, inputCol="filtered", outputCol="ngram")
    //ngram_df = ngram.transform(tokenized_df)

    //hashing
    var hashingTF = new HashingTF().setInputCol("filtered").setOutputCol("rawFeatures").setNumFeatures(params.getInt("adhocAnalyzer.numTextFeatures"))
    val featurized_df = hashingTF.transform(filtered_df).drop("filtered")
    //featurized_df.show(15,false)

    var idf = new IDF().setInputCol("rawFeatures").setOutputCol("pre_features")
    //val Array(train, cv) = featurized_df.randomSplit(Array(0.7, 0.3))
    var idfModel = idf.fit(featurized_df)
    val rescaled_df = idfModel.transform(featurized_df).drop("rawFeatures")
    rescaled_df.show(5) //,false)

    val hashed_bills = featurized_df.select("primary_key","rawFeatures").rdd.map(row => converted(row.toSeq))
    //println(hashed_bills.collect())

    //Experimental
    //First, run the hashing step here
    println(params.getString("adhocAnalyzer.inputPairsFile"))
    val cartesian_pairs = spark.objectFile[CartesianPair](params.getString("adhocAnalyzer.inputPairsFile")).map(pp => (pp.pk1,pp.pk2))

    var distanceMeasure: DistanceMeasure = null
    var threshold: Double = 0.0

    params.getString("adhocAnalyzer.measureName") match {
      case "cosine" => {
        distanceMeasure = CosineDistance
        //threshold = ???
      }
      case "hamming" => {
        distanceMeasure = HammingDistance
        //threshold = ???
      }
      case "euclidean" => {
        distanceMeasure = EuclideanDistance
        //threshold = ???
      }
      case "manhattan" => {
        distanceMeasure = ManhattanDistance
        //threshold = ???
      }
      case "jaccard" => {
        distanceMeasure = JaccardDistance
        //threshold = ???
      }
      case other: Any =>
        throw new IllegalArgumentException(
          s"Only hamming, cosine, euclidean, manhattan, and jaccard distances are supported but got $other."
        )
    }

    val firstjoin = cartesian_pairs.map({case (k1,k2) => (k1, (k1,k2))})
        .join(hashed_bills)
        .map({case (_, ((k1, k2), v1)) => ((k1, k2), v1)})
    //println(firstjoin.count())
    val matches = firstjoin.map({case ((k1,k2),v1) => (k2, ((k1,k2),v1))})
        .join(hashed_bills)
        .map({case(_, (((k1,k2), v1), v2))=>((k1, k2),(v1, v2))}).mapValues({case (v1,v2) => distanceMeasure.compute(v1.toSparse,v2.toSparse)})
    //matches.collect().foreach(println)
    matches.saveAsObjectFile(params.getString("adhocAnalyzer.outputMainFile"))
    //matches.filter(kv => (kv._2 > threshold)).saveAsObjectFile(params.getString("adhocAnalyzer.outputMainFile"))

    val edges_df = matches.map({case ((k1,k2), v1)=>(k1, k2, v1)}).toDF("src","dst","similarity")
    edges_df.printSchema
    edges_df.show()

    val vertices_df = rescaled_df.select(col("primary_key").alias("id"), col("content"))
    vertices_df.printSchema
    vertices_df.show()
    val gr = GraphFrame(vertices_df,edges_df)

    //gr.vertices.filter("age > 35")
    //gr.edges.filter("similarity > 20")
    //gr.inDegrees.filter("inDegree >= 2")
    val results = gr.pageRank.resetProbability(0.01).run()
    results.vertices.select("primary_key", "pagerank").show()


    //add histogramming Experimental
    val sim_histogram = Histogram(200, 0, 100, {matches: Tuple2[Tuple2[Long,Long],Double] => matches._2})
    val all_histograms = Label("Similarity" -> sim_histogram)
    val final_histogram = matches.aggregate(all_histograms)(new Increment, new Combine)
    val json_string = final_histogram("Similarity").toJson.stringify
    val file = new File("/user/alexeys/test_histos")
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(json_string)
    bw.close()    

    spark.stop()
   }
}
