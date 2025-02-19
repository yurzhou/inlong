/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.dataproxy.sink.mq.tube;

import org.apache.inlong.common.monitor.LogCounter;
import org.apache.inlong.dataproxy.config.pojo.CacheClusterConfig;
import org.apache.inlong.dataproxy.config.pojo.IdTopicConfig;
import org.apache.inlong.dataproxy.consts.ConfigConstants;
import org.apache.inlong.dataproxy.consts.StatConstants;
import org.apache.inlong.dataproxy.sink.common.EventHandler;
import org.apache.inlong.dataproxy.sink.common.TubeUtils;
import org.apache.inlong.dataproxy.sink.mq.BatchPackProfile;
import org.apache.inlong.dataproxy.sink.mq.MessageQueueHandler;
import org.apache.inlong.dataproxy.sink.mq.MessageQueueZoneSinkContext;
import org.apache.inlong.dataproxy.sink.mq.OrderBatchPackProfileV0;
import org.apache.inlong.dataproxy.sink.mq.SimpleBatchPackProfileV0;
import org.apache.inlong.tubemq.client.config.TubeClientConfig;
import org.apache.inlong.tubemq.client.exception.TubeClientException;
import org.apache.inlong.tubemq.client.factory.TubeMultiSessionFactory;
import org.apache.inlong.tubemq.client.producer.MessageProducer;
import org.apache.inlong.tubemq.client.producer.MessageSentCallback;
import org.apache.inlong.tubemq.client.producer.MessageSentResult;
import org.apache.inlong.tubemq.corebase.Message;

import org.apache.commons.lang3.StringUtils;
import org.apache.flume.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * TubeHandler
 */
public class TubeHandler implements MessageQueueHandler {

    public static final Logger LOG = LoggerFactory.getLogger(TubeHandler.class);
    // log print count
    private static final LogCounter logCounter = new LogCounter(10, 100000, 30 * 1000);

    private static String MASTER_HOST_PORT_LIST = "master-host-port-list";
    public static final String KEY_NAMESPACE = "namespace";

    private CacheClusterConfig config;
    private String clusterName;
    private MessageQueueZoneSinkContext sinkContext;

    // parameter
    private String masterHostAndPortList;
    private long linkMaxAllowedDelayedMsgCount;
    private long sessionWarnDelayedMsgCount;
    private long sessionMaxAllowedDelayedMsgCount;
    private long nettyWriteBufferHighWaterMark;
    // tube producer
    private TubeMultiSessionFactory sessionFactory;
    private MessageProducer producer;
    private Set<String> topicSet = new HashSet<>();
    private ThreadLocal<EventHandler> handlerLocal = new ThreadLocal<>();

    /**
     * init
     * @param config
     * @param sinkContext
     */
    @Override
    public void init(CacheClusterConfig config, MessageQueueZoneSinkContext sinkContext) {
        this.config = config;
        this.clusterName = config.getClusterName();
        this.sinkContext = sinkContext;
    }

    /**
     * start
     */
    @Override
    public void start() {
        // create tube producer
        try {
            // prepare configuration
            TubeClientConfig conf = initTubeConfig();
            LOG.info("try to create producer:{}", conf.toJsonString());
            this.sessionFactory = new TubeMultiSessionFactory(conf);
            this.producer = sessionFactory.createProducer();
            LOG.info("create new producer success:{}", producer);
        } catch (Throwable e) {
            LOG.error(e.getMessage(), e);
        }
    }

    /**
     * initTubeConfig
     * @return
     *
     * @throws Exception
     */
    private TubeClientConfig initTubeConfig() throws Exception {
        // get parameter
        Context context = sinkContext.getProducerContext();
        Context configContext = new Context(context.getParameters());
        configContext.putAll(this.config.getParams());
        masterHostAndPortList = configContext.getString(MASTER_HOST_PORT_LIST);
        linkMaxAllowedDelayedMsgCount = configContext.getLong(ConfigConstants.LINK_MAX_ALLOWED_DELAYED_MSG_COUNT,
                80000L);
        sessionWarnDelayedMsgCount = configContext.getLong(ConfigConstants.SESSION_WARN_DELAYED_MSG_COUNT,
                2000000L);
        sessionMaxAllowedDelayedMsgCount = configContext.getLong(
                ConfigConstants.SESSION_MAX_ALLOWED_DELAYED_MSG_COUNT,
                4000000L);
        nettyWriteBufferHighWaterMark = configContext.getLong(ConfigConstants.NETTY_WRITE_BUFFER_HIGH_WATER_MARK,
                15 * 1024 * 1024L);
        // config
        final TubeClientConfig tubeClientConfig = new TubeClientConfig(this.masterHostAndPortList);
        tubeClientConfig.setLinkMaxAllowedDelayedMsgCount(linkMaxAllowedDelayedMsgCount);
        tubeClientConfig.setSessionWarnDelayedMsgCount(sessionWarnDelayedMsgCount);
        tubeClientConfig.setSessionMaxAllowedDelayedMsgCount(sessionMaxAllowedDelayedMsgCount);
        tubeClientConfig.setNettyWriteBufferHighWaterMark(nettyWriteBufferHighWaterMark);
        tubeClientConfig.setHeartbeatPeriodMs(15000L);
        tubeClientConfig.setRpcTimeoutMs(20000L);

        return tubeClientConfig;
    }

    /**
     * stop
     */
    @Override
    public void stop() {
        // producer
        if (this.producer != null) {
            try {
                this.producer.shutdown();
            } catch (Throwable e) {
                LOG.error(e.getMessage(), e);
            }
        }
        if (this.sessionFactory != null) {
            try {
                this.sessionFactory.shutdown();
            } catch (TubeClientException e) {
                LOG.error(e.getMessage(), e);
            }
        }
        LOG.info("tube handler stopped");
    }

    private String getTubeTopic(IdTopicConfig idConfig) {
        // consider first using group mq resource as tube topic, for example, group id
        String topic = idConfig.getParams().get(KEY_NAMESPACE);
        if (StringUtils.isBlank(topic)) {
            // use whatever user specifies in stream mq resource
            topic = idConfig.getTopicName();
        }
        return topic;
    }

    /**
     * send
     */
    public boolean send(BatchPackProfile event) {
        try {
            // idConfig
            IdTopicConfig idConfig = sinkContext.getIdTopicHolder().getIdConfig(event.getUid());
            if (idConfig == null) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_NOUID);
                sinkContext.addSendResultMetric(event, clusterName, event.getUid(), false, 0);
                sinkContext.getDispatchQueue().release(event.getSize());
                event.fail();
                return false;
            }
            String topic = getTubeTopic(idConfig);
            if (topic == null) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_NOTOPIC);
                sinkContext.addSendResultMetric(event, clusterName, event.getUid(), false, 0);
                sinkContext.getDispatchQueue().release(event.getSize());
                event.fail();
                return false;
            }
            // create producer failed
            if (producer == null) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_NOPRODUCER);
                sinkContext.processSendFail(event, clusterName, topic, 0);
                LOG.error("producer is null");
                return false;
            }
            // publish
            if (!this.topicSet.contains(topic)) {
                this.producer.publish(topic);
                this.topicSet.add(topic);
            }
            // send
            if (event instanceof SimpleBatchPackProfileV0) {
                this.sendSimpleProfileV0((SimpleBatchPackProfileV0) event, idConfig, topic);
            } else if (event instanceof OrderBatchPackProfileV0) {
                this.sendOrderProfileV0((OrderBatchPackProfileV0) event, idConfig, topic);
            } else {
                this.sendProfileV1(event, idConfig, topic);
            }
            return true;
        } catch (Exception e) {
            sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_SENDEXCEPT);
            sinkContext.processSendFail(event, clusterName, event.getUid(), 0);
            LOG.error(e.getMessage(), e);
            return false;
        }
    }

    /**
     * sendProfileV1
     */
    private void sendProfileV1(BatchPackProfile event, IdTopicConfig idConfig,
            String topic) throws Exception {
        EventHandler handler = handlerLocal.get();
        if (handler == null) {
            handler = this.sinkContext.createEventHandler();
            handlerLocal.set(handler);
        }
        // headers
        Map<String, String> headers = handler.parseHeader(idConfig, event, sinkContext.getNodeId(),
                sinkContext.getCompressType());
        // compress
        byte[] bodyBytes = handler.parseBody(idConfig, event, sinkContext.getCompressType());
        // metric
        sinkContext.addSendMetric(event, clusterName, topic, bodyBytes.length);
        // sendAsync
        Message message = new Message(topic, bodyBytes);
        // add headers
        headers.forEach((key, value) -> {
            message.setAttrKeyVal(key, value);
        });
        // callback
        long sendTime = System.currentTimeMillis();
        MessageSentCallback callback = new MessageSentCallback() {

            @Override
            public void onMessageSent(MessageSentResult result) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_SUCCESS);
                sinkContext.addSendResultMetric(event, clusterName, topic, true, sendTime);
                sinkContext.getDispatchQueue().release(event.getSize());
                event.ack();
            }

            @Override
            public void onException(Throwable ex) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_RECEIVEEXCEPT);
                sinkContext.processSendFail(event, clusterName, topic, sendTime);
                if (logCounter.shouldPrint()) {
                    LOG.error("Send ProfileV1 to tube failure", ex);
                }
            }
        };
        producer.sendMessage(message, callback);
    }

    /**
     * sendSimpleProfileV0
     */
    private void sendSimpleProfileV0(SimpleBatchPackProfileV0 event, IdTopicConfig idConfig,
            String topic) throws Exception {
        // build message
        Message message = TubeUtils.buildMessage(topic, event.getSimpleProfile());
        // metric
        sinkContext.addSendMetric(event, clusterName, topic, event.getSimpleProfile().getBody().length);
        // callback
        long sendTime = System.currentTimeMillis();
        MessageSentCallback callback = new MessageSentCallback() {

            @Override
            public void onMessageSent(MessageSentResult result) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_SUCCESS);
                sinkContext.addSendResultMetric(event, clusterName, topic, true, sendTime);
                sinkContext.getDispatchQueue().release(event.getSize());
                event.ack();
            }

            @Override
            public void onException(Throwable ex) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_RECEIVEEXCEPT);
                sinkContext.processSendFail(event, clusterName, topic, sendTime);
                if (logCounter.shouldPrint()) {
                    LOG.error("Send SimpleProfileV0 to tube failure", ex);
                }
            }
        };
        producer.sendMessage(message, callback);
    }

    /**
     * sendOrderProfileV0
     */
    private void sendOrderProfileV0(OrderBatchPackProfileV0 event, IdTopicConfig idConfig, String topic)
            throws Exception {
        // headers
        Map<String, String> headers = event.getOrderProfile().getHeaders();
        // compress
        byte[] bodyBytes = event.getOrderProfile().getBody();
        // metric
        sinkContext.addSendMetric(event, clusterName, topic, bodyBytes.length);
        // sendAsync
        Message message = new Message(topic, bodyBytes);
        // add headers
        headers.forEach(message::setAttrKeyVal);
        // callback
        long sendTime = System.currentTimeMillis();
        MessageSentCallback callback = new MessageSentCallback() {

            @Override
            public void onMessageSent(MessageSentResult result) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_SUCCESS);
                sinkContext.addSendResultMetric(event, clusterName, topic, true, sendTime);
                sinkContext.getDispatchQueue().release(event.getSize());
                event.ack();
                event.ackOrder();
            }

            @Override
            public void onException(Throwable ex) {
                sinkContext.fileMetricEventInc(StatConstants.EVENT_SINK_RECEIVEEXCEPT);
                sinkContext.processSendFail(event, clusterName, topic, sendTime);
                if (logCounter.shouldPrint()) {
                    LOG.error("Send OrderProfileV0 to tube failure", ex);
                }
            }
        };
        producer.sendMessage(message, callback);
    }
}
