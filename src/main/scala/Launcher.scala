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

import kafka.serializer.StringDecoder

import java.util.Properties
import java.util.Locale
import java.text.SimpleDateFormat
import java.sql.Timestamp

import org.apache.log4j.{ Level, Logger }

import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka._
import org.apache.spark.{ SparkConf, SparkContext }
import org.apache.spark.sql.json.JsonRDD
import org.apache.spark.sql._
import org.apache.spark.sql.types._

import scala.collection.mutable.ArrayBuffer

object Launcher {

  /*
   * Parse created_at and make it a Timestamp
   */
  def timeParser(created_at: String): (Timestamp, Timestamp) = {
    val twitterFormat = "EEE MMM dd HH:mm:ss Z yyyy"
    val sdf = new SimpleDateFormat(twitterFormat, Locale.UK)
    val tweetTimestamp = new Timestamp(sdf.parse(created_at).getTime())
    val partition = new Timestamp((System.currentTimeMillis() / (10000 * 60)) * (10000 * 60)) // Partition by 10 minutes
    (tweetTimestamp, partition)
  }

  def main(args: Array[String]) = {

    Logger.getRootLogger.setLevel(Level.WARN)
   // Set cache cleaner in cleaner.ttl interval in seconds. Needs to be 2 times longer than  the longest stream window(10 minutes * 2)  
    val conf = new SparkConf().setAppName("DirectKafkaStream").set("spark.cleaner.ttl", "1210")
    val ssc = new StreamingContext(conf, Seconds(5))
    val checkpointDir = ssc.checkpoint("checkpointDir")

    // Create direct kafka stream.
    val topics = Set("tweets") // A kafka topic which Spark will subscribe to
    val kafkaParams = Map("metadata.broker.list" -> "localhost:9092")
    val directStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topics)

    // Collects tweets and saves them to database. Interval given at StreamingContext
    directStream.foreachRDD { rdd =>
      val sqlContext = SQLContextSingleton.getInstance(rdd.sparkContext)

      // Set up jdbc properties
      val url = "jdbc:mysql://localhost/default"
      val table = "Tweets"
      val properties = new Properties
      properties.setProperty("user", "myuser")
      properties.setProperty("password", "mypassword")

      // Create DataFrame from incoming tweets and a tempTable to filter the wanted fields from tweets
      val df = sqlContext.read.json(rdd.map(_._2)).toDF
      val tempTable = df.registerTempTable("temp")

      // Select wanted fields from tweets
      val data = sqlContext.sql("SELECT user.name, created_at, text, lang FROM temp")

      // Change created_at from String to Timestamptype.
      val processedRows = data.map { r =>
        val username = r.getString(0)
        val (created_at, partition) = timeParser(r.getString(1))
        val text = r.getString(2)
        val hashtags = text.split("\\s").filter(_.startsWith("#")).mkString(" ")
        val lang = r.getString(3)
        Row(username, created_at, text, hashtags, lang, partition)
      }

      // Schema for table "tweets"
      val schema =
        StructType(
          StructField("username", StringType) ::
            StructField("created_at", TimestampType) ::
            StructField("text", StringType) ::
            StructField("hashtags", StringType) ::
            StructField("lang", StringType) ::
            StructField("partition", TimestampType) ::
            Nil)

      val processedDF = sqlContext.createDataFrame(processedRows, schema)

      // Write to database 
      processedDF.write.mode("append").jdbc(url, table, properties)
    }

    /*
     * Keeps track at a 10 minute interval for tweet counts to detect peaks in the amount of tweets.
     * Also has a flag if the point is considered a peak or not. countArr acts as a "moving window"
     */
    val countArr = ArrayBuffer[(Long, Boolean)]()
    directStream.window(Minutes(10), Minutes(10)).foreachRDD { rdd =>
      val count = rdd.count
      println("--------------------")
      println("Current value: " + count)
      if (countArr.length > 0) {

        // Computing some values for peak detection.

        // This peak detection is based on moving average and moving standard deviation. 
        val mvAvg = (countArr.map(_._1).sum + rdd.count()) / (countArr.length + 1)
        val mvStdDev = math.sqrt((countArr.map(x => math.pow(x._1 - mvAvg, 2)).sum + (math.pow(count - mvAvg, 2))) / (countArr.length + 1)).toLong
        val S = 2
        val isPeak1 = count > (mvAvg + (S  * mvStdDev))

        // This peak detection is based on maxium value, deviation and difference quotient.
        val max = (countArr.map(_._1) :+ rdd.count()).max
        val K = 0.45
        val deviation = (countArr.map(x => x._1 - mvAvg).sum + (count - mvAvg)) / countArr.length
        val exp = ((max + mvAvg) / 2) + (K * deviation)
        val dq = count - countArr(0)._1
        val isPeak2 = dq > 300 && count > exp

        // Determine if the point is a peak or not. This needs more work.
        val isPeak = isPeak1 || isPeak2
        println("Is a peak: " + isPeak + " at " + new Timestamp((System.currentTimeMillis() / ((10000 * 60))) * (10000 * 60) - 10000 * 60))

        countArr.prepend(((count, isPeak)))

        if (countArr(1)._2) {
          // If the current value is bigger than the last one then the peak is still growing > postpone LDA)
          if (countArr(0)._1 > countArr(1)._1) { 
            countArr(0) = ((countArr(0)._1, true))
          } else {
            println("Calculating LDA")
            val sqlContextLDA = SQLContextSingleton.getInstance(rdd.sparkContext)
            val time = new Timestamp((System.currentTimeMillis() / ((10000 * 60))) * (10000 * 60) - 10000 * 60)
            LDAObject.main(sqlContextLDA, time)
          }
        }

        if (countArr.length > 6) countArr.trimEnd(1)
        
      } else {
        countArr.prepend((count, false))
      }
      println("--------------------")
    }

    // Start
    ssc.start()
    ssc.awaitTermination()
  }

}
// For creating a SQLContext from existing Spark Context
object SQLContextSingleton {

  @transient private var instance: SQLContext = _

  def getInstance(sparkContext: SparkContext): SQLContext = {
    if (instance == null) {
      instance = new SQLContext(sparkContext)
    }
    instance
  }
}