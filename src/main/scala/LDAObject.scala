/**
*  Copyright 2015 Aalto Yliopisto, Ahmed Hussnain
*
*  Licensed under the Apache License, Version 2.0 (the "License");
*  you may not use this file except in compliance with the License.
*  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software
*  distributed under the License is distributed on an "AS IS" BASIS,
*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*  limitations under the License.
*/
package main.scala

import java.sql.Timestamp
import java.util.Properties

import org.apache.spark.mllib.clustering.{ OnlineLDAOptimizer, LDA }
import org.apache.spark.mllib.linalg.{ Vector, Vectors }
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.types._

import scala.collection.mutable.HashMap

object LDAObject {

  /*
   * Convert tweets to bag-of-words model
   */
  def preprocess(data: RDD[String], stopwords: Array[String]) = {

    // Filter out words that are; less than 3 char long or contain any non letter chars. If the word is at the end of sentence drop last char. 
    val tokenized: RDD[Array[String]] = data.map(d => d.toLowerCase().split("\\s")).map {
    // d = document, a = array, w = word, c = character
      a => 
        a.filter(word => word.length > 2).map {
          w =>
            w.last match {
              case ',' | '!' | '?' | '.' => w.dropRight(1)
              case _ => w
            }
        }.filter(w => w.forall(c => java.lang.Character.isLetter(c)))
    }

    // termcounts for every document
    val termCounts = tokenized.flatMap(d => d.map(w => (w, 1L))).reduceByKey(_ + _).collect().sortBy(-_._2)

    val numFreqwords = 5
    val vocabArray = // Remove stopwords. Remove few of the most freq words and words that only appear once
      termCounts.takeRight(termCounts.size - numFreqwords).filter(_._2 > 1).filterNot(w => stopwords.contains(w._1)).map(_._1)

    val vocabulary = vocabArray.zipWithIndex.toMap

    // Make every document to a bag of words  
    val documents: RDD[(Long, Vector)] = tokenized.zipWithIndex.map { 
      case (tokens, id) =>
        val counts = new HashMap[Int, Double]()
        tokens.foreach { term =>
          if (vocabulary.contains(term)) {
            val idx = vocabulary(term)
            counts(idx) = counts.getOrElse(idx, 0.0) + 1.0
          }
        }
        // (documentID, Vector(vocab.size,Seq(wordId, freq))
        (id, Vectors.sparse(vocabulary.size, counts.toSeq)) 
    }

    (documents, vocabArray)
  }

  def main(sqlContext: SQLContext, timestamp: Timestamp, k: Int = 5, i: Int = 20) = {

    // Set up jdbc properties
    val url = "jdbc:mysql://localhost/default"
    val table = "Tweets"
    val properties = new Properties
    properties.setProperty("user", "myuser")
    properties.setProperty("password", "mypassword")

    // Load up stopwords
    val stopwords = sqlContext.sparkContext.textFile("file:///path/to/spark-twitter-lda/stopwords.txt").collect()

    val dataFrame = sqlContext.read.jdbc(url, table, properties)

    dataFrame.registerTempTable("temp")

    // Get tweets from the last 30min (10 minutes from both side of the peak)
    val start = new Timestamp(timestamp.getTime() - (60000 * 20))
    val peak_at = new Timestamp(timestamp.getTime() - (60000 * 10))
    val end = timestamp
    val tweets = // Query tweets from start to end 
      sqlContext.sql("SELECT text, hashtags FROM temp WHERE '" +
        end + "'>CAST(created_at AS STRING) AND '" +
        start + "'<CAST(created_at AS STRING) AND lang='en'")

    val tweetsRDD = tweets.map(row => row.getString(0))
    val hashtagsRDD = tweets.map(row => row.getString(1))

    // Take ten most frequent hashtags
    val hashtags10 = hashtagsRDD.flatMap(l => l.toLowerCase().split(" "))
      .map(w => (w, 1L)).reduceByKey(_ + _).sortBy(-_._2).map(_._1).take(11).mkString(" ")

    // Process the RDD
    val (documents, vocabArray) = preprocess(tweetsRDD, stopwords)
    val docCount = documents.count()

    // Setup LDA
    val lda = new LDA()
    val optimizer = new OnlineLDAOptimizer().setMiniBatchFraction(0.05)
    lda.setOptimizer(optimizer).setK(k).setMaxIterations(i)

    // Run LDA
    val ldaModel = lda.run(documents)
    val topicIndices = ldaModel.describeTopics(10)

    // Create a string from LDA result
    val topics = topicIndices.map {
      case (wordIds, termWeights) =>
        wordIds.zip(termWeights).map { case (wordId, weight) => (vocabArray(wordId.toInt), weight) }
    }
    val results = topics.zipWithIndex.map {
      case (topic, i) =>
        "<b>Topic " + (i + 1) + "</b>: " + topic.map(_._1).mkString(" ") + "<br>"
    }.mkString("\n") + "\n<b>Top 10 hashtags</b>:<br> " + hashtags10

    // Schema for LDA results table 
    val schema =
      StructType(
        StructField("peak_at", TimestampType) ::
          StructField("LDA", StringType) ::
          StructField("hashtags", StringType) ::
          Nil)

    val resultRow = sqlContext.sparkContext.parallelize(Array(Row(peak_at, results, hashtags10)))
    val dfToSave = sqlContext.createDataFrame(resultRow, schema)

    // Write to database 
    dfToSave.write.mode("append").jdbc(url, "LDAResults", properties)
  
  }
}