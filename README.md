Apache Spark Streaming LDA
==========================
This application is for analyzing and storing Twitter data in real time.

Setup Kafka
-----------
Download and install Kafka 2.10-0.8.1.1  

Start Zookeeper  
``cd /path/to/kafka``  
``bin/zookeeper-server-start.sh config/zookeeper.properties``

Start Kafka  
``bin/kafka-server-start.sh config/server.properties``

Create Kafka topic  
``bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic tweets``

Twitter Kafka pipeline
----------------------------
Create a Twitter user and create a Twitter app at ``apps.twitter.com`` to get your Consumer key and Access Token.

Create Twitter Kafka pipeline to collect tweets with:  
``https://github.com/NFLabs/kafka-twitter`` 

Run Kafka Twitter Producer with:  
`cd kafka-twitter-master`  
``./gradlew run -Pargs="conf/producer.conf"``

MySQL
-----
Install MySQL, log in and then create an user that you want the app to use, and grant it permissions.  
Then do the following command:
<pre>
mysql&gt; USE default;
</pre>  
Spark creates the tables if it doesn't find them in the given database.  But if you want you can make them with these commands
<pre>
mysql&gt; CREATE TABLE LDAResults(
-&gt; peak_at TIMESTAMP, 
-&gt; LDA TEXT,  
-&gt; hashtags TEXT );
mysql&gt; CREATE TABLE Tweets(
-&gt; username TEXT,
-&gt; created_at TIMESTAMP, 
-&gt; text TEXT,
-&gt; hashtags TEXT, 
-&gt; lang TEXT,
-&gt; partition TIMESTAMP ); 
</pre> 

To keep your database from blowing up I suggest you make some kind of cleaning event that removes every day x days old data.

Setup Apache Spark and the App
--------------------------------------------
Download and build Apache Spark. Version used in development of this app was  1.4.0.
Download and add ``mysql-connector-java.jar``to Spark Classpath. Version used 5.1.36.  

Change your MySQL database information in ``Launcher.scala`` and ``LDAObject.scala``.

<pre>
cd spark-twitter-lda/
sbt assembly
</pre>

Launch Apache Spark standalone server.
<pre>
cd /path/to/spark
sbin/start-all.sh
</pre>

You need to start this application at even 10minutes for the peak detection to work correctly.
Launch App with command:
<pre>
./bin/spark-submit --class main.scala.Launcher -master spark://localhost.localdomain:7077 /path/to/sparktwitterlda.jar
</pre>

Visualize your data
-------------------
For data visualization you can use anything that supports mysql connection. 
I created a simple graph in d3.js and php. 