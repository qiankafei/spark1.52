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

import scala.collection.mutable.LinkedList
import scala.reflect.ClassTag
import scala.util.Random

import akka.actor.{Actor, ActorRef, Props, actorRef2Scala}

import org.apache.spark.{SparkConf, SecurityManager}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.StreamingContext.toPairDStreamFunctions
import org.apache.spark.util.AkkaUtils
import org.apache.spark.streaming.receiver.ActorHelper

case class SubscribeReceiver(receiverActor: ActorRef)
case class UnsubscribeReceiver(receiverActor: ActorRef)

/**
 * Sends the random content to every receiver subscribed with 1/2
 *  second delay.
 * 将随机内容发送给每一个有1 / 2秒延迟的接收机
 */
class FeederActor extends Actor {

  val rand = new Random()
  var receivers: LinkedList[ActorRef] = new LinkedList[ActorRef]()

  val strings: Array[String] = Array("words ", "may ", "count ")

  def makeMessage(): String = {
    val x = rand.nextInt(3)
    strings(x) + strings(2 - x)
  }

  /*
   * A thread to generate random messages
   * 生成随机消息的一个线程
   */
  new Thread() {
    override def run() {
      while (true) {
        Thread.sleep(500)
        receivers.foreach(_ ! makeMessage)
      }
    }
  }.start()

  def receive: Receive = {

    case SubscribeReceiver(receiverActor: ActorRef) =>
      println("received subscribe from %s".format(receiverActor.toString))
    receivers = LinkedList(receiverActor) ++ receivers

    case UnsubscribeReceiver(receiverActor: ActorRef) =>
      println("received unsubscribe from %s".format(receiverActor.toString))
    receivers = receivers.dropWhile(x => x eq receiverActor)

  }
}

/**
 * A sample actor as receiver, is also simplest. This receiver actor
 * 一个简单的接收器,也是最简单的,这个接收器actor去和订阅一个典型的发布/反溃演员和接收数据
 * goes and subscribe to a typical publisher/feeder actor and receives
 * data.
 *
 * @see [[org.apache.spark.examples.streaming.FeederActor]]
 */
class SampleActorReceiver[T: ClassTag](urlOfPublisher: String)
extends Actor with ActorHelper {

  lazy private val remotePublisher = context.actorSelection(urlOfPublisher)

  override def preStart(): Unit = remotePublisher ! SubscribeReceiver(context.self)

  def receive: PartialFunction[Any, Unit] = {
    case msg => store(msg.asInstanceOf[T])
  }

  override def postStop(): Unit = remotePublisher ! UnsubscribeReceiver(context.self)

}

/**
 * A sample feeder actor
 *一个简单的feeder actor
 * Usage: FeederActor <hostname> <port>
 *   <hostname> and <port> describe the AkkaSystem that Spark Sample feeder would start on.
 */
object FeederActor {

  def main(args: Array[String]) {
    if (args.length < 2){
      System.err.println("Usage: FeederActor <hostname> <port>\n")
      System.exit(1)
    }
    //
    val Seq(host, port) = args.toSeq

    val conf = new SparkConf
    val actorSystem = AkkaUtils.createActorSystem("test", host, port.toInt, conf = conf,
      securityManager = new SecurityManager(conf))._1
    val feeder = actorSystem.actorOf(Props[FeederActor], "FeederActor")

    println("Feeder started as:" + feeder)

    actorSystem.awaitTermination()
  }
}

/**
 * A sample word count program demonstrating the use of plugging in
 * Actor as Receiver
 * 一个示例字计数程序,演示了插入的使用Actor作为接收机
 * Usage: ActorWordCount <hostname> <port>
 *   <hostname> and <port> describe the AkkaSystem that Spark Sample feeder is running on.
 *
 * To run this example locally, you may run Feeder Actor as
 *    `$ bin/run-example org.apache.spark.examples.streaming.FeederActor 127.0.1.1 9999`
 * and then run the example
 *    `$ bin/run-example org.apache.spark.examples.streaming.ActorWordCount 127.0.1.1 9999`
 */
object ActorWordCount {
  def main(args: Array[String]) {
    if (args.length < 2) {
      System.err.println(
        "Usage: ActorWordCount <hostname> <port>")
      System.exit(1)
    }

    StreamingExamples.setStreamingLogLevels()

    val Seq(host, port) = args.toSeq
    val sparkConf = new SparkConf().setAppName("ActorWordCount")
    // Create the context and set the batch size
    //创建上下文并设置批量大小,Seconds(2)批次间隔
    val ssc = new StreamingContext(sparkConf, Seconds(2))

    /*
     * Following is the use of actorStream to plug in custom actor as receiver
     * 以下是actorstream插入自定义的演员作为接收机的使用
     * An important point to note:
     * Since Actor may exist outside the spark framework, It is thus user's responsibility
     * to ensure the type safety, i.e type of data received and InputDstream
     * should be same.
     *
     * For example: Both actorStream and SampleActorReceiver are parameterized
     * to same type to ensure type safety.
     */

    val lines = ssc.actorStream[String](
      Props(new SampleActorReceiver[String]("akka.tcp://test@%s:%s/user/FeederActor".format(
        host, port.toInt))), "SampleReceiver")

    // compute wordcount 计算执行
    lines.flatMap(_.split("\\s+")).map(x => (x, 1)).reduceByKey(_ + _).print()

    ssc.start()
    ssc.awaitTermination()
  }
}
// scalastyle:on println
