/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.dw.jstorm.example.newindow;

import backtype.storm.Config;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import com.alibaba.jstorm.cluster.Common;
import com.alibaba.jstorm.utils.JStormUtils;
import com.alibaba.jstorm.window.BaseWindowedBolt;
import com.alibaba.jstorm.window.Time;
import com.alibaba.jstorm.window.TimeWindow;
import com.alibaba.jstorm.window.TimestampExtractor;
import com.alibaba.jstorm.window.Watermark;
import com.alibaba.jstorm.window.WatermarkGenerator;
import com.alibaba.jstorm.window.WindowStateMerger;
import com.alibaba.starter.utils.JStormHelper;
import com.google.common.collect.Lists;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WordCount but teh spout does not stop, and the bolts are implemented in java.
 * This can show how fast the word count can run.
 */
public class FastWordCountSessionEventTimeWindowTopology {
    private static Logger LOG = LoggerFactory.getLogger(FastWordCountSessionEventTimeWindowTopology.class);
    public final static String TOPOLOGY_SPOUT_PARALLELISM_HINT = "spout.parallel";
    public final static String TOPOLOGY_COUNT_PARALLELISM_HINT = "count.parallel";

    public static class FastRandomSentenceSpout implements IRichSpout {

        SpoutOutputCollector collector;
        int index = 0;
        static long BASE_TIME = System.currentTimeMillis();

        private static final List<String[]> WORDS = Lists.newArrayList(
                new String[]{"aa", "bb", "cc"},
                new String[]{"dd", "ee"},
                new String[]{"aa", "bb", "cc"});
        private static final List<long[]> TIMES = Lists.newArrayList(
                new long[]{BASE_TIME + 1, BASE_TIME + 2, BASE_TIME + 3},
                new long[]{BASE_TIME + 5, BASE_TIME + 8},
                new long[]{BASE_TIME + 10, BASE_TIME + 1, BASE_TIME + 5});

        @Override
        public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
            this.collector = collector;
        }

        @Override
        public void close() {
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void nextTuple() {
            if (index >= WORDS.size()) {
                JStormUtils.sleepMs(10);
                collector.emit(Common.WATERMARK_STREAM_ID, new Values(new Watermark(BASE_TIME + 15L)));
                return;
            }

            String[] words = WORDS.get(index);
            long[] times = TIMES.get(index);
            for (int i = 0; i < words.length; i++) {
                collector.emit(new Values(words[i], times[i]));
            }

            index++;
            JStormUtils.sleepMs(500);
        }

        @Override
        public void ack(Object msgId) {
        }

        @Override
        public void fail(Object msgId) {
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("word", "ts"));
        }

        @Override
        public Map<String, Object> getComponentConfiguration() {
            return null;
        }
    }

    public static class WordCount extends BaseWindowedBolt<Tuple> implements TimestampExtractor, WindowStateMerger {
        OutputCollector collector;

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
        }

        @Override
        public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
            this.collector = collector;
        }

        @Override
        public void cleanup() {
        }

        @Override
        public Object initWindowState(TimeWindow window) {
            return new HashMap<>();
        }

        @Override
        public void execute(Tuple tuple, Object state, TimeWindow window) {
            Map<String, Integer> counts = (Map<String, Integer>) state;
            String word = tuple.getString(0);
            Integer count = counts.get(word);
            if (count == null)
                count = 0;
            counts.put(word, ++count);
        }

        @Override
        public void purgeWindow(Object state, TimeWindow window) {
            Map<String, Integer> counts = (Map<String, Integer>) state;
            System.out.println("purging window: " + window);
            System.out.println("=============================");
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                System.out.println("word: " + entry.getKey() + ", \t\tcount: " + entry.getValue());
            }
            System.out.println("=============================");
            System.out.println();
        }

        @Override
        public Object reduceState(Object state1, Object state2) {
            Map<String, Integer> counts1 = (Map<String, Integer>) state1;
            Map<String, Integer> counts2 = (Map<String, Integer>) state2;
            for (Map.Entry<String, Integer> entry : counts2.entrySet()) {
                String key = entry.getKey();
                Integer c1 = counts1.get(key);
                if (c1 != null) {
                    counts1.put(key, c1 + entry.getValue());
                } else {
                    counts1.put(key, entry.getValue());
                }
            }

            return counts1;
        }

        @Override
        public Map<String, Object> getComponentConfiguration() {
            return null;
        }

        @Override
        public long extractTimestamp(Tuple element) {
            return element.getLong(1);
        }
    }

    static Config conf = JStormHelper.getConfig(null);

    public static void test() throws Exception {
        int spout_Parallelism_hint = JStormUtils.parseInt(conf.get(TOPOLOGY_SPOUT_PARALLELISM_HINT), 1);
        int count_Parallelism_hint = JStormUtils.parseInt(conf.get(TOPOLOGY_COUNT_PARALLELISM_HINT), 1);

        TopologyBuilder builder = new TopologyBuilder();

        builder.setSpout("spout", new FastRandomSentenceSpout(), spout_Parallelism_hint);

        WordCount wordCountBolt = new WordCount();
        builder.setBolt("count", wordCountBolt.sessionEventTimeWindow(Time.milliseconds(3L))
                .withTimestampExtractor(wordCountBolt)
                .withWindowStateMerger(wordCountBolt), count_Parallelism_hint)
                .fieldsGrouping("spout", new Fields("word", "ts"));

        String[] className = Thread.currentThread().getStackTrace()[1].getClassName().split("\\.");
        String topologyName = className[className.length - 1];

        JStormHelper.runTopology(builder.createTopology(), topologyName, conf, 60,
                new JStormHelper.CheckAckedFail(conf), true);
    }

    public static void main(String[] args) throws Exception {
        conf = JStormHelper.getConfig(args);
        test();
    }
}
