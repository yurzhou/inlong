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

package org.apache.inlong.dataproxy.source2.v0msg;

import org.apache.inlong.common.enums.DataProxyErrCode;
import org.apache.inlong.common.msg.AttributeConstants;
import org.apache.inlong.common.msg.InLongMsg;
import org.apache.inlong.common.msg.MsgType;
import org.apache.inlong.dataproxy.base.SinkRspEvent;
import org.apache.inlong.dataproxy.config.CommonConfigHolder;
import org.apache.inlong.dataproxy.config.ConfigManager;
import org.apache.inlong.dataproxy.consts.StatConstants;
import org.apache.inlong.dataproxy.source2.BaseSource;
import org.apache.inlong.dataproxy.utils.MessageUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_ATTRLEN_SIZE;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_BODYLEN_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_BODY_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_CNT_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_DT_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_EXTEND_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_FORMAT_SIZE;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_GROUPIDNUM_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_MAGIC;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_MAGIC_SIZE;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_MSGTYPE_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_STREAMIDNUM_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_TOTALLEN_OFFSET;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_TOTALLEN_SIZE;
import static org.apache.inlong.dataproxy.source2.v0msg.MsgFieldConsts.BIN_MSG_UNIQ_OFFSET;

public class CodecBinMsg extends AbsV0MsgCodec {

    private int groupIdNum;
    private int streamIdNum;
    private int extendField;
    private long dataTimeSec;
    private boolean num2name = false;
    private boolean transNum2Name = false;
    private boolean isOrderOrProxy = false;
    private boolean indexMsg = false;
    private boolean fileCheckMsg = false;
    private boolean needTraceMsg = false;

    public CodecBinMsg(int totalDataLen, int msgTypeValue,
            long msgRcvTime, String strRemoteIP) {
        super(totalDataLen, msgTypeValue, msgRcvTime, strRemoteIP);
    }

    public boolean descMsg(BaseSource source, ByteBuf cb) throws Exception {
        int msgHeadPos = cb.readerIndex() - 5;
        // read fixed field value
        this.groupIdNum = cb.getUnsignedShort(BIN_MSG_GROUPIDNUM_OFFSET);
        this.streamIdNum = cb.getUnsignedShort(BIN_MSG_STREAMIDNUM_OFFSET);
        this.extendField = cb.getUnsignedShort(BIN_MSG_EXTEND_OFFSET);
        this.dataTimeSec = cb.getUnsignedInt(BIN_MSG_DT_OFFSET);
        this.dataTimeMs = this.dataTimeSec * 1000L;
        this.msgCount = cb.getUnsignedShort(BIN_MSG_CNT_OFFSET);
        this.msgCount = (this.msgCount != 0) ? this.msgCount : 1;
        this.uniq = cb.getUnsignedInt(BIN_MSG_UNIQ_OFFSET);
        // get body and attribute field length
        int bodyLen = cb.getInt(msgHeadPos + BIN_MSG_BODYLEN_OFFSET);
        int attrLen = cb.getShort(msgHeadPos + BIN_MSG_BODY_OFFSET + bodyLen);
        int msgMagic = cb.getUnsignedShort(msgHeadPos + BIN_MSG_BODY_OFFSET
                + bodyLen + BIN_MSG_ATTRLEN_SIZE + attrLen);
        if (bodyLen <= 0) {
            if (bodyLen == 0) {
                source.fileMetricEventInc(StatConstants.EVENT_NOBODY);
                this.errCode = DataProxyErrCode.BODY_LENGTH_ZERO;
            } else {
                source.fileMetricEventInc(StatConstants.EVENT_NEGBODY);
                this.errCode = DataProxyErrCode.BODY_LENGTH_LESS_ZERO;
            }
            return false;
        }
        // get attribute length
        if (attrLen < 0) {
            source.fileMetricEventInc(StatConstants.EVENT_NEGATTR);
            this.errCode = DataProxyErrCode.ATTR_LENGTH_LESS_ZERO;
            return false;
        }
        // get msg magic
        if ((msgMagic != BIN_MSG_MAGIC)
                || (totalDataLen + BIN_MSG_TOTALLEN_SIZE < (bodyLen + attrLen + BIN_MSG_FORMAT_SIZE))) {
            source.fileMetricEventInc(StatConstants.EVENT_MALFORMED);
            this.errCode = DataProxyErrCode.FIELD_VALUE_NOT_EQUAL;
            this.errMsg = String.format(
                    "fixedLen(%d) + bodyLen(%d) + attrLen(%d) > totalDataLen(%d) + 4 or msgMagic(%d) != %d",
                    BIN_MSG_FORMAT_SIZE, bodyLen, attrLen, totalDataLen, msgMagic, BIN_MSG_MAGIC);
            return false;
        }
        // extract attr bytes
        if (!decAttrInfo(source, cb, attrLen,
                msgHeadPos + BIN_MSG_BODY_OFFSET + bodyLen + BIN_MSG_ATTRLEN_SIZE)) {
            return false;
        }
        this.bodyData = new byte[bodyLen];
        cb.getBytes(msgHeadPos + BIN_MSG_BODY_OFFSET, this.bodyData, 0, bodyLen);
        // process extend field value
        if (((this.extendField & 0x8) == 0x8) || ((this.extendField & 0x10) == 0x10)) {
            this.indexMsg = true;
            this.fileCheckMsg = (this.extendField & 0x8) == 0x8;
        }
        if (((extendField & 0x2) >> 1) == 0x1) {
            this.needTraceMsg = true;
        }
        if (((extendField & 0x4) >> 2) == 0x0) {
            this.num2name = true;
        }
        // parse required fields
        Pair<Boolean, String> evenProcType =
                MessageUtils.getEventProcType(attrMap.get(AttributeConstants.MESSAGE_SYNC_SEND),
                        attrMap.get(AttributeConstants.MESSAGE_PROXY_SEND));
        this.isOrderOrProxy = evenProcType.getLeft();
        this.msgProcType = evenProcType.getRight();
        return true;
    }

    public boolean validAndFillFields(BaseSource source, StringBuilder strBuff) {
        // reject unsupported index messages
        if (indexMsg) {
            source.fileMetricEventInc(StatConstants.EVENT_UNSUPMSG);
            this.errCode = DataProxyErrCode.UNSUPPORTED_EXTEND_FIELD_VALUE;
            return false;
        }
        // valid and fill topicName
        if (!validAndFillTopic(source)) {
            return false;
        }
        // build message seqId
        this.msgSeqId = strBuff.append(this.topicName)
                .append(AttributeConstants.SEPARATOR).append(this.streamId)
                .append(AttributeConstants.SEPARATOR).append(strRemoteIP)
                .append("#").append(dataTimeMs).append("#").append(uniq).toString();
        strBuff.delete(0, strBuff.length());
        // check required rtms attrs
        if (StringUtils.isBlank(attrMap.get(AttributeConstants.MSG_RPT_TIME))) {
            strBuff.append(AttributeConstants.MSG_RPT_TIME)
                    .append(AttributeConstants.KEY_VALUE_SEPARATOR).append(msgRcvTime);
            attrMap.put(AttributeConstants.MSG_RPT_TIME, String.valueOf(msgRcvTime));
        }
        // get trace requirement
        if (this.needTraceMsg) {
            if (strBuff.length() > 0) {
                strBuff.append(AttributeConstants.SEPARATOR);
            }
            strBuff.append(AttributeConstants.DATAPROXY_NODE_IP)
                    .append(AttributeConstants.KEY_VALUE_SEPARATOR).append(source.getStrPort())
                    .append(AttributeConstants.SEPARATOR)
                    .append(AttributeConstants.DATAPROXY_RCVTIME)
                    .append(AttributeConstants.KEY_VALUE_SEPARATOR).append(msgRcvTime);
            attrMap.put(AttributeConstants.DATAPROXY_NODE_IP, source.getSrcHost());
            attrMap.put(AttributeConstants.DATAPROXY_RCVTIME, String.valueOf(msgRcvTime));
        }
        // trans groupId and StreamId Num 2 Name
        if (this.transNum2Name) {
            if (strBuff.length() > 0) {
                strBuff.append(AttributeConstants.SEPARATOR);
            }
            strBuff.append(AttributeConstants.GROUP_ID)
                    .append(AttributeConstants.KEY_VALUE_SEPARATOR).append(groupId)
                    .append(AttributeConstants.SEPARATOR)
                    .append(AttributeConstants.STREAM_ID)
                    .append(AttributeConstants.KEY_VALUE_SEPARATOR).append(streamId);
            for (Map.Entry<String, String> entry : attrMap.entrySet()) {
                if (AttributeConstants.GROUP_ID.equalsIgnoreCase(entry.getKey())
                        || AttributeConstants.STREAM_ID.equalsIgnoreCase(entry.getKey())) {
                    continue;
                }
                strBuff.append(AttributeConstants.SEPARATOR)
                        .append(entry.getKey())
                        .append(AttributeConstants.KEY_VALUE_SEPARATOR).append(entry.getValue());
            }
            this.groupIdNum = 0;
            this.streamIdNum = 0;
            this.num2name = false;
            this.extendField = this.extendField | 0x4;
            attrMap.put(AttributeConstants.GROUP_ID, groupId);
            attrMap.put(AttributeConstants.STREAM_ID, streamId);
        }
        if (strBuff.length() > 0) {
            if (StringUtils.isNotBlank(origAttr)) {
                strBuff.append(AttributeConstants.SEPARATOR).append(origAttr);
            }
            totalDataLen += strBuff.length() - origAttr.length();
            origAttr = strBuff.toString();
            strBuff.delete(0, strBuff.length());
        }
        return true;
    }

    public Event encEventPackage(BaseSource source, Channel channel) {
        // fill bin msg package
        int totalPkgLength = totalDataLen + BIN_MSG_TOTALLEN_SIZE;
        ByteBuffer dataBuf = ByteBuffer.allocate(totalPkgLength);
        dataBuf.putInt(BIN_MSG_TOTALLEN_OFFSET, totalDataLen);
        dataBuf.put(BIN_MSG_MSGTYPE_OFFSET, msgType);
        dataBuf.putShort(BIN_MSG_GROUPIDNUM_OFFSET, (short) groupIdNum);
        dataBuf.putShort(BIN_MSG_STREAMIDNUM_OFFSET, (short) streamIdNum);
        dataBuf.putShort(BIN_MSG_EXTEND_OFFSET, (short) extendField);
        dataBuf.putInt(BIN_MSG_DT_OFFSET, (int) dataTimeSec);
        dataBuf.putShort(BIN_MSG_CNT_OFFSET, (short) msgCount);
        dataBuf.putInt(BIN_MSG_UNIQ_OFFSET, (int) uniq);
        dataBuf.putInt(BIN_MSG_BODYLEN_OFFSET, bodyData.length);
        if (bodyData.length > 0) {
            System.arraycopy(bodyData, 0, dataBuf.array(), BIN_MSG_BODY_OFFSET, bodyData.length);
        }
        dataBuf.putShort(totalPkgLength
                - BIN_MSG_ATTRLEN_SIZE - BIN_MSG_MAGIC_SIZE - origAttr.length(), (short) origAttr.length());
        if (origAttr.length() > 0) {
            System.arraycopy(origAttr.getBytes(StandardCharsets.UTF_8), 0, dataBuf.array(),
                    totalPkgLength - BIN_MSG_MAGIC_SIZE - origAttr.length(), bodyData.length);
        }
        dataBuf.putShort(totalPkgLength - BIN_MSG_MAGIC_SIZE, (short) BIN_MSG_MAGIC);
        // build InLong message
        InLongMsg inLongMsg = InLongMsg.newInLongMsg(source.isCompressed(), 4);
        inLongMsg.addMsg(dataBuf.array());
        long pkgTime = inLongMsg.getCreatetime();
        Event event = EventBuilder.withBody(inLongMsg.buildArray(), buildEventHeaders(pkgTime));
        if (isOrderOrProxy) {
            event = new SinkRspEvent(event, MsgType.MSG_BIN_MULTI_BODY, channel);
        }
        inLongMsg.reset();
        return event;
    }

    private boolean validAndFillTopic(BaseSource source) {
        // valid groupId, streamId
        ConfigManager configManager = ConfigManager.getInstance();
        this.groupId = this.attrMap.get(AttributeConstants.GROUP_ID);
        this.streamId = this.attrMap.get(AttributeConstants.STREAM_ID);
        if (num2name) {
            if (this.groupIdNum == 0) {
                source.fileMetricEventInc(StatConstants.EVENT_WITHOUTGROUPID);
                this.errCode = DataProxyErrCode.MISS_REQUIRED_GROUPID_ARGUMENT;
                this.errMsg = "groupIdNum is 0 in message";
                return false;
            }
            String confGroupId;
            String confStreamId;
            String strGroupIdNum = String.valueOf(this.groupIdNum);
            if (configManager.getGroupIdMappingProperties() == null) {
                source.fileMetricEventInc(StatConstants.EVENT_SERVICE_UNREADY);
                this.errCode = DataProxyErrCode.CONF_SERVICE_UNREADY;
                this.errMsg = "GroupId-Mapping configuration is null";
                return false;
            }
            confGroupId = configManager.getGroupIdMappingProperties().get(strGroupIdNum);
            if (StringUtils.isBlank(confGroupId)) {
                source.fileMetricEventInc(StatConstants.EVENT_WITHOUTGROUPID);
                this.errCode = DataProxyErrCode.GROUPID_OR_STREAMID_NOT_CONFIGURE;
                this.errMsg = String.format("Non-existing groupIdNum(%s) configuration", strGroupIdNum);
                return false;
            }
            if (StringUtils.isNotBlank(this.groupId) && !this.groupId.equalsIgnoreCase(confGroupId)) {
                source.fileMetricEventInc(StatConstants.EVENT_INCONSGROUPORSTREAMID);
                this.errCode = DataProxyErrCode.GROUPID_OR_STREAMID_INCONSTANT;
                this.errMsg = String.format(
                        "Inconstant GroupId not equal, (%s) in attr but (%s) in configure by groupIdNum(%s)",
                        this.groupId, confGroupId, strGroupIdNum);
                return false;
            }
            this.groupId = confGroupId;
            // check streamId
            if (this.streamIdNum == 0) {
                if (StringUtils.isNotBlank(this.streamId)) {
                    source.fileMetricEventInc(StatConstants.EVENT_INCONSGROUPORSTREAMID);
                    this.errCode = DataProxyErrCode.GROUPID_OR_STREAMID_INCONSTANT;
                    this.errMsg = String.format("Inconstant streamId(%s) in attr but streamIdNum=0", this.streamId);
                    return false;
                }
            } else {
                if (configManager.getStreamIdMappingProperties() == null) {
                    source.fileMetricEventInc(StatConstants.EVENT_SERVICE_UNREADY);
                    this.errCode = DataProxyErrCode.CONF_SERVICE_UNREADY;
                    this.errMsg = "StreamId-Mapping configuration is null";
                    return false;
                }
                Map<String, String> confStreamIdMap =
                        configManager.getStreamIdMappingProperties().get(strGroupIdNum);
                if (confStreamIdMap == null) {
                    source.fileMetricEventInc(StatConstants.EVENT_SERVICE_UNREADY);
                    this.errCode = DataProxyErrCode.CONF_SERVICE_UNREADY;
                    this.errMsg = "GroupId in StreamId-Mapping configuration is null";
                    return false;
                }
                String strStreamIdNum = String.valueOf(this.streamIdNum);
                confStreamId = confStreamIdMap.get(strStreamIdNum);
                if (StringUtils.isBlank(confStreamId)) {
                    source.fileMetricEventInc(StatConstants.EVENT_WITHOUTGROUPID);
                    this.errCode = DataProxyErrCode.GROUPID_OR_STREAMID_NOT_CONFIGURE;
                    this.errMsg = String.format("Non-existing GroupId(%s)-StreamId(%s) configuration",
                            strGroupIdNum, strStreamIdNum);
                    return false;
                }
                if (StringUtils.isNotBlank(this.streamId) && !this.streamId.equalsIgnoreCase(confStreamId)) {
                    source.fileMetricEventInc(StatConstants.EVENT_INCONSGROUPORSTREAMID);
                    this.errCode = DataProxyErrCode.GROUPID_OR_STREAMID_INCONSTANT;
                    this.errMsg = String.format(
                            "Inconstant StreamId, (%s) in attr but (%s) in configure by groupIdNum(%s), streamIdNum(%s)",
                            this.streamId, confStreamId, strGroupIdNum, strStreamIdNum);
                    return false;
                }
                this.streamId = confStreamId;
            }
            // check whether enable num 2 name translate
            String enableTrans = (configManager.getGroupIdEnableMappingProperties() == null)
                    ? null
                    : configManager.getGroupIdEnableMappingProperties().get(strGroupIdNum);
            if ("true".equalsIgnoreCase(enableTrans) && this.num2name) {
                this.transNum2Name = true;
            }
        } else {
            if (StringUtils.isBlank(groupId)) {
                source.fileMetricEventInc(StatConstants.EVENT_WITHOUTGROUPID);
                this.errCode = DataProxyErrCode.MISS_REQUIRED_GROUPID_ARGUMENT;
                return false;
            }
        }
        // get and check topic configure
        this.topicName = configManager.getTopicName(this.groupId, this.streamId);
        if (StringUtils.isBlank(this.topicName)) {
            if (CommonConfigHolder.getInstance().isNoTopicAccept()) {
                this.topicName = source.getDefTopic();
            } else {
                source.fileMetricEventInc(StatConstants.EVENT_NOTOPIC);
                this.errCode = DataProxyErrCode.TOPIC_IS_BLANK;
                this.errMsg = String.format("Topic is null for inlongGroupId=(%s), inlongStreamId=(%s)",
                        this.groupId, this.streamId);
                return false;
            }
        }
        if (StringUtils.isBlank(this.streamId)) {
            this.streamId = "";
        }
        return true;
    }

}
