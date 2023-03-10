/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.coomia.datalake.benchmark;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.utils.MultipleParameterTool;
import org.apache.flink.calcite.shaded.com.google.common.collect.ImmutableMap;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink;
import org.apache.flink.streaming.api.functions.sink.filesystem.bucketassigners.DateTimeBucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;
import org.apache.flink.streaming.connectors.elasticsearch7.ElasticsearchSink;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer.Semantic;
import org.apache.http.HttpHost;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableList;

public class BDPBenchmarkJob {

  public static void main(String[] args) throws Exception {
    // get kafka envs from args.
    System.setProperty("HADOOP_USER_NAME", "hdfs");
    MultipleParameterTool params = MultipleParameterTool.fromArgs(args);
    String topic = "arkevent";
    String esHost = "elasticsearch";
    String path = "hdfs://namenode:9000/hdfs";
    String zkHost = "zookeeper";
    String zkPort = "2181";
    String hbaseMaster = "hbase-master:16000";
    String cf = "cf";

    int esPort = 9200;
    int partitionNum = 3;
    short replicas = 1;
    String servers = "kafka:9092";

    if (params.has("topic"))
      topic = params.get("topic");
    if (params.has("partition"))
      partitionNum = params.getInt("partition");
    if (params.has("bootstrap-server"))
      servers = params.get("bootstrap-server");
    if (params.has("esHost"))
      esHost = params.get("esHost");
    if (params.has("esPort"))
      esPort = params.getInt("esPort");
    if (params.has("path"))
      path = params.get("path");

    if (params.has("zkHost"))
      zkHost = params.get("zkHost");

    if (params.has("zkPort"))
      zkPort = params.get("zkPort");

    if (params.has("hbaseMaster"))
      hbaseMaster = params.get("hbaseMaster");
    if (params.has("cf"))
      cf = params.get("cf");

    // create kafka topic if not exists.
    AdminClient kafkAdminClient = AdminClient.create(KafkaUtils.producerProps(servers));
    NewTopic newTopic = new NewTopic(topic, partitionNum, replicas);
    newTopic.configs(new ImmutableMap.Builder<String, String>().put("cleanup.policy","delete").
        put("retention.ms",Long.toString(86400000L)).
        put("retention.bytes","-1").
        build()); 
    if (!new ArrayList<String>(kafkAdminClient.listTopics().names().get()).contains(topic))
      kafkAdminClient.createTopics(new ImmutableList.Builder<NewTopic>().add(newTopic).build());
    kafkAdminClient.close();

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    // produce data to kafka with a lot of data.
    DataStream<String> producer =
        env.addSource(new EventSourceGenerator(Long.MAX_VALUE, Integer.MAX_VALUE));
    producer
        .addSink(new FlinkKafkaProducer<String>(topic, new ProducerStringSerializationSchema(topic),
            KafkaUtils.producerProps(servers), Semantic.EXACTLY_ONCE));

    // consume data to ES
    List<HttpHost> httpHosts = new ArrayList<>();
    httpHosts.add(new HttpHost(esHost, esPort, "http"));
    ElasticsearchSink.Builder<String> esSinkBuilder = new ElasticsearchSink.Builder<>(httpHosts,
        new ElasticsearchSinkFunctionWithConf(topic, topic));
    esSinkBuilder.setBulkFlushMaxActions(1);

    FlinkKafkaConsumer<String> consumer = new FlinkKafkaConsumer<String>(topic,
        new SimpleStringSchema(), KafkaUtils.consumeProps(servers, "flink-consumer"));
    consumer.setStartFromEarliest();
    DataStreamSource<String> dataStream = env.addSource(consumer);
    // uid is used for job restart or something when using savepoint.
    dataStream.uid("flink-consumer");
    dataStream.addSink(esSinkBuilder.build());


    /**
     * sink data to hdfs.
     */
    DefaultRollingPolicy rollingPolicy =
        DefaultRollingPolicy.builder().withMaxPartSize(1024 * 1024 * 50) // ?????????????????????????????????
                                                                         // ,?????????128M??????????????????50M
            .withRolloverInterval(TimeUnit.MINUTES.toMillis(1)) // ???????????????????????????????????????60s??????????????????1??????
            .withInactivityInterval(TimeUnit.SECONDS.toMillis(30)) // 30s????????????????????????????????????
            .build();
    BucketAssigner ba = new DateTimeBucketAssigner("yyyyMMdd", ZoneId.of("Asia/Shanghai"));

    StreamingFileSink<String> sink =
        StreamingFileSink.forRowFormat(new Path(path), new SimpleStringEncoder<String>("UTF-8"))
            .withBucketAssigner(ba).withRollingPolicy(rollingPolicy).withBucketCheckInterval(1000L) // ?????????????????????????????????1s
            .build();
    dataStream.addSink(sink);

    /**
     * sink data to HBase
     */


    dataStream.map(new MapFunction<String, Map<String, Object>>() {

      @Override
      public Map<String, Object> map(String value) throws Exception {
        return JSONObject.parseObject(value).getInnerMap();
      }

    }).addSink(new FlinkHbaseSink(zkHost, zkPort, hbaseMaster, topic, cf));

    env.execute("flink-consumer-demo");
  }

}
