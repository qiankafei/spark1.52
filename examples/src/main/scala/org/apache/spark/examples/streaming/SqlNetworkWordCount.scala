/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// scalastyle:off println
package org.apache.spark.examples.streaming

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.{Time, Seconds, StreamingContext}
import org.apache.spark.util.IntParam
import org.apache.spark.sql.SQLContext
import org.apache.spark.storage.StorageLevel

/**
 * Use DataFrames and SQL to count words in UTF8 encoded, '\n' delimited text received from the
 * network every second.
 *
 * Usage: SqlNetworkWordCount <hostname> <port>
 * <hostname> and <port> describe the TCP server that Spark Streaming would connect to receive data.
 *
 * To run this on your local machine, you need to first run a Netcat server
 *    `$ nc -lk 9999`
 * and then run the example
 *    `$ bin/run-example org.apache.spark.examples.streaming.SqlNetworkWordCount localhost 9999`
 */

object SqlNetworkWordCount {
  def main(args: Array[String]) {
    if (args.length < 2) {
      System.err.println("Usage: NetworkWordCount <hostname> <port>")
      System.exit(1)
    }

    StreamingExamples.setStreamingLogLevels()

    // Create the context with a 2 second batch size
    //创建上下文一个2秒批量大小
    val sparkConf = new SparkConf().setAppName("SqlNetworkWordCount")
    val ssc = new StreamingContext(sparkConf, Seconds(2))

    // Create a socket stream on target ip:port and count the
    // words in input stream of \n delimited text (eg. generated by 'nc')
    //在目标IP上创建一个套接字流:端口,并在“n”分隔的文本的输入流中计数单词
    // Note that no duplication in storage level only for running locally.
    //请注意,只有在本地运行的存储级别没有重复。
    // Replication necessary in distributed scenario for fault tolerance.
    //分布式容错中必要的复制
    val lines = ssc.socketTextStream(args(0), args(1).toInt, StorageLevel.MEMORY_AND_DISK_SER)
    val words = lines.flatMap(_.split(" "))

    // Convert RDDs of the words DStream to DataFrame and run SQL query
    //转换 RDD单词离散流dstream到DataFrame运行SQL查询RDDS
    words.foreachRDD((rdd: RDD[String], time: Time) => {
      // Get the singleton instance of SQLContext
      //获得SQLContext单例 
      val sqlContext = SQLContextSingleton.getInstance(rdd.sparkContext)
      import sqlContext.implicits._

      // Convert RDD[String] to RDD[case class] to DataFrame
      //将RDD[字符串]转换  RDD [实列类]到DataFrame
      val wordsDataFrame = rdd.map(w => Record(w)).toDF()

      // Register as table 注册表
      wordsDataFrame.registerTempTable("words")

      // Do word count on table using SQL and print it
      //做单词计数表使用SQL和打印
      val wordCountsDataFrame =
        sqlContext.sql("select word, count(*) as total from words group by word")
      println(s"========= $time =========")
      wordCountsDataFrame.show()
    })

    ssc.start()
    ssc.awaitTermination()
  }
}


/** 
 *  Case class for converting RDD to DataFrame
 *  RDD实例类转换到DataFrame
 *  */
case class Record(word: String)


/** 
 *  Lazily instantiated singleton instance of SQLContext 
 *  延迟初始化单例SQL上下文
 *  */
object SQLContextSingleton {

  @transient  private var instance: SQLContext = _

  def getInstance(sparkContext: SparkContext): SQLContext = {
    if (instance == null) {
      instance = new SQLContext(sparkContext)
    }
    instance
  }
}
// scalastyle:on println
