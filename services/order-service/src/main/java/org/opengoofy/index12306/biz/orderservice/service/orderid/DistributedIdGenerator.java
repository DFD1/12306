/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.orderservice.service.orderid;

/**
 * 全局唯一订单号生成器
 *
 *
 */
public class DistributedIdGenerator {

    private static final long EPOCH = 1609459200000L;
    private static final int NODE_BITS = 5;
    private static final int SEQUENCE_BITS = 7;

    private final long nodeID;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public DistributedIdGenerator(long nodeID) {
        this.nodeID = nodeID;
    }

    public synchronized long generateId() {
        long timestamp = System.currentTimeMillis() - EPOCH;
        //系统时钟不稳定发生了回退，拒绝生成ID
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards. Refusing to generate ID.");
        }
        //如果上一次生成的ID和当前ID是在同一毫秒内
        if (timestamp == lastTimestamp) {
            //sequence自增
            sequence = (sequence + 1) & ((1 << SEQUENCE_BITS) - 1);
            //如果序列号到达最大值，等待下一毫秒再生成ID。
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            //上一次生成的ID和当前ID不在同一毫秒内，就把序列号置为0
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        //最终通过按位左移和按位或运算，将时间戳、节点ID和序列号组合为一个64位的唯一ID，并返回生成的ID。
        return (timestamp << (NODE_BITS + SEQUENCE_BITS)) | (nodeID << SEQUENCE_BITS) | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis() - EPOCH;
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis() - EPOCH;
        }
        return timestamp;
    }
}
