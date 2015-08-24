lazy val root = (project in file(".")).
  settings(
    name := "spark-twitter-lda",
    version := "1.0",
    scalaVersion := "2.10.4",
    mainClass in Compile := Some("scala.Launcher")
)

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "1.4.0" % "provided",
  "org.apache.spark" %% "spark-streaming" % "1.4.0" % "provided",
  "org.apache.spark" %% "spark-sql" % "1.4.0" % "provided",
  "org.apache.spark" %% "spark-mllib" % "1.4.0" % "provided",
  "org.apache.spark" % "spark-streaming-kafka_2.10" % "1.4.0",
  "org.apache.kafka" % "kafka_2.10" % "0.8.1.1",
  "mysql" % "mysql-connector-java" % "5.1.36"
)

// META-INF discarding
mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
   {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case x => MergeStrategy.first
   }
}

assemblyJarName in assembly := "sparktwitterlda.jar"
