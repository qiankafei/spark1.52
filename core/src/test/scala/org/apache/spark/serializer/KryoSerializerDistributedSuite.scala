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

package org.apache.spark.serializer

import org.apache.spark.util.Utils

import com.esotericsoftware.kryo.Kryo

import org.apache.spark._
import org.apache.spark.serializer.KryoDistributedTest._

class KryoSerializerDistributedSuite extends SparkFunSuite {
  //kryo对象序列在不同的进程中
  test("kryo objects are serialised consistently in different processes") {
    val conf = new SparkConf(false)
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", classOf[AppJarRegistrator].getName)
      //Task的最大重试次数
      .set("spark.task.maxFailures", "1")

    val jar = TestUtils.createJarWithClasses(List(AppJarRegistrator.customClassName))
    conf.setJars(List(jar.getPath))

   // val sc = new SparkContext("local-cluster[2,1,1024]", "test", conf)

    val sc = new SparkContext("local[*]", "test", conf)
    //Thread.currentThread().getContextClassLoader,可以获取当前线程的引用,getContextClassLoader用来获取线程的上下文类加载器
    val original = Thread.currentThread.getContextClassLoader
    val loader = new java.net.URLClassLoader(Array(jar), Utils.getContextOrSparkClassLoader)
    SparkEnv.get.serializer.setDefaultClassLoader(loader)

    val cachedRDD = sc.parallelize((0 until 10).map((_, new MyCustomClass)), 3).cache()

    // Randomly mix the keys so that the join below will require a shuffle with each partition
    // sending data to multiple other partitions.
    //随机混合键,以便下面的连接将需要与每个分区进行shuffle将数据发送到多个其他分区。
    val shuffledRDD = cachedRDD.map { case (i, o) => (i * i * i - 10 * i * i, o)}

    // Join the two RDDs, and force evaluation
    //加入两RDDS,影响力分析
    assert(shuffledRDD.join(cachedRDD).collect().size == 1)

    LocalSparkContext.stop(sc)
  }
}

object KryoDistributedTest {
  class MyCustomClass

  class AppJarRegistrator extends KryoRegistrator {
    override def registerClasses(k: Kryo) {
      //Thread.currentThread().getContextClassLoader,可以获取当前线程的引用,getContextClassLoader用来获取线程的上下文类加载器
      val classLoader = Thread.currentThread.getContextClassLoader
      // scalastyle:off classforname
      k.register(Class.forName(AppJarRegistrator.customClassName, true, classLoader))
      // scalastyle:on classforname
    }
  }

  object AppJarRegistrator {
    val customClassName = "KryoSerializerDistributedSuiteCustomClass"
  }
}
