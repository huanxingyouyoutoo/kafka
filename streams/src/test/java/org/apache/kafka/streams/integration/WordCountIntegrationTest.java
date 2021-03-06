/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.kafka.streams.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.apache.kafka.test.TestUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.apache.kafka.streams.integration.utils.EmbeddedSingleNodeKafkaCluster;
import org.apache.kafka.streams.integration.utils.IntegrationTestUtils;


/**
 * End-to-end integration test based on a simple word count example, using an embedded Kafka
 * cluster.
 */
public class WordCountIntegrationTest {
    @ClassRule
    public static final EmbeddedSingleNodeKafkaCluster CLUSTER = new EmbeddedSingleNodeKafkaCluster();
    private static final String DEFAULT_INPUT_TOPIC = "inputTopic";
    private static final String DEFAULT_OUTPUT_TOPIC = "outputTopic";

    @BeforeClass
    public static void startKafkaCluster() throws Exception {
        CLUSTER.createTopic(DEFAULT_INPUT_TOPIC, 2, 1);
        CLUSTER.createTopic(DEFAULT_OUTPUT_TOPIC);
    }

    @Test
    public void shouldCountWords() throws Exception {
        List<String> inputValues = Arrays.asList("hello", "world", "world", "hello world");
        List<KeyValue<String, Long>> expectedWordCounts = Arrays.asList(
            new KeyValue<>("hello", 1L),
            new KeyValue<>("hello", 2L),
            new KeyValue<>("world", 1L),
            new KeyValue<>("world", 2L),
            new KeyValue<>("world", 3L)
        );

        //
        // Step 1: Configure and start the processor topology.
        //
        final Serde<String> stringSerde = Serdes.String();
        final Serde<Long> longSerde = Serdes.Long();

        Properties streamsConfiguration = new Properties();
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-integration-test");
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        streamsConfiguration.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, CLUSTER.zKConnectString());
        streamsConfiguration.put(StreamsConfig.KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getPath());

        KStreamBuilder builder = new KStreamBuilder();

        KStream<String, String> textLines = builder.stream(DEFAULT_INPUT_TOPIC);

        KStream<String, Long> wordCounts = textLines
            .flatMapValues(new ValueMapper<String, Iterable<String>>() {
                @Override
                public Iterable<String> apply(String value) {
                    return Arrays.asList(value.toLowerCase(Locale.getDefault()).split("\\W+"));
                }
            }).groupBy(new KeyValueMapper<String, String, String>() {
                @Override
                public String apply(final String key, final String value) {
                    return value;
                }
            }).count("Counts")
            .toStream();

        wordCounts.to(stringSerde, longSerde, DEFAULT_OUTPUT_TOPIC);

        // Remove any state from previous test runs
        IntegrationTestUtils.purgeLocalStreamsState(streamsConfiguration);

        KafkaStreams streams = new KafkaStreams(builder, streamsConfiguration);
        streams.start();
        
        //
        // Step 2: Produce some input data to the input topic.
        //
        Properties producerConfig = new Properties();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        IntegrationTestUtils.produceValuesSynchronously(DEFAULT_INPUT_TOPIC, inputValues, producerConfig);

        //
        // Step 3: Verify the application's output data.
        //
        Properties consumerConfig = new Properties();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "wordcount-integration-test-standard-consumer");
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
        List<KeyValue<String, Long>> actualWordCounts = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfig,
            DEFAULT_OUTPUT_TOPIC, expectedWordCounts.size());
        Collections.sort(actualWordCounts, new Comparator<KeyValue<String, Long>>() {
            @Override
            public int compare(final KeyValue<String, Long> o1, final KeyValue<String, Long> o2) {
                int keyComparison = o1.key.compareTo(o2.key);
                if (keyComparison == 0) {
                    return o1.value.compareTo(o2.value);
                }
                return keyComparison;
            }
        });
        streams.close();
        assertThat(actualWordCounts, equalTo(expectedWordCounts));
    }

}
