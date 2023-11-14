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

package org.opengoofy.index12306.biz.ticketservice.canal;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.common.enums.CanalExecuteStrategyMarkEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SeatStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.mq.event.CanalBinlogEvent;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractExecuteStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 列车余票缓存更新组件
 *
 *
 */
@Component
@RequiredArgsConstructor
public class TicketAvailabilityCacheUpdateHandler implements AbstractExecuteStrategy<CanalBinlogEvent, Void> {

    private final DistributedCache distributedCache;

    @Override
    public void execute(CanalBinlogEvent message) {
        //用来存储符合条件的新数据
        List<Map<String, Object>> messageDataList = new ArrayList<>();
        //用来存储对应的旧数据
        List<Map<String, Object>> actualOldDataList = new ArrayList<>();
        for (int i = 0; i < message.getOld().size(); i++) {
            //遍历message对象中的旧数据列表
            Map<String, Object> oldDataMap = message.getOld().get(i);
            //首先判断oldDataMap是否包含键为seat_status的值
            if (oldDataMap.get("seat_status") != null && StrUtil.isNotBlank(oldDataMap.get("seat_status").toString())) {
                //如果oldDataMap含有键为seat_status的值，就从message中获取对应位置的新数据currentDataMap
                Map<String, Object> currentDataMap = message.getData().get(i);
                //只关注seat_status字段的改变,例如由0（可售状态）转变为1（锁定状态），或者从1变为0，只有这种数据变化时，才需要跟新余票
                if (StrUtil.equalsAny(currentDataMap.get("seat_status").toString(), String.valueOf(SeatStatusEnum.AVAILABLE.getCode()), String.valueOf(SeatStatusEnum.LOCKED.getCode()))) {
                    //将oldDataMap存储到旧数据列表中
                    actualOldDataList.add(oldDataMap);
                    //将currentDataMap存储到新数据列表中
                    messageDataList.add(currentDataMap);
                }
            }
        }
        if (CollUtil.isEmpty(messageDataList) || CollUtil.isEmpty(actualOldDataList)) {
            return;
        }
        //存储缓存的变化，以缓存键为key，value是以座位类型和座位数量信息的Map。
        Map<String, Map<Integer, Integer>> cacheChangeKeyMap = new HashMap<>();
        for (int i = 0; i < messageDataList.size(); i++) {
            //从messgeDataList取出新数据
            Map<String, Object> each = messageDataList.get(i);
            //从actualOldDataList取出对应的旧数据
            Map<String, Object> actualOldData = actualOldDataList.get(i);
            //获取旧数据的座位状态seatStatus
            String seatStatus = actualOldData.get("seat_status").toString();
            //如果旧数据的座位状态为0（可售状态），则表示新数据的座位状态变为了1，需要自减该座位类型的座位数量
            int increment = Objects.equals(seatStatus, "0") ? -1 : 1;
            //获取新数据的train_id
            String trainId = each.get("train_id").toString();
            //构建缓存键hashCacheKey
            String hashCacheKey = TRAIN_STATION_REMAINING_TICKET + trainId + "_" + each.get("start_station") + "_" + each.get("end_station");
            //从cacheChangeKeyMap中获取与hashCacheKey对应的座位类型和数量信息seatTypeMap，如果没有就创建一个新的HashMap
            Map<Integer, Integer> seatTypeMap = cacheChangeKeyMap.get(hashCacheKey);
            if (CollUtil.isEmpty(seatTypeMap)) {
                seatTypeMap = new HashMap<>();
            }
            //获取新数据元素的seatType
            Integer seatType = Integer.parseInt(each.get("seat_type").toString());
            //从seatTypeMap中获取与seatType对应的数量num
            Integer num = seatTypeMap.get(seatType);
            //将座位类型和数量信息更新到seatTypeMap中
            // 如果数量num为空，即还没有对应的座位类型记录，则将增量increment赋值给num；否则，将num加上increment的值。
            seatTypeMap.put(seatType, num == null ? increment : num + increment);
            //将更新后的座位类型和数量信息seatTypeMap存入cacheChangeKeyMap中，以hashCacheKey为键
            cacheChangeKeyMap.put(hashCacheKey, seatTypeMap);
        }
        //获取StringRedisTemplate实例
        StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
        //遍历cacheChangeKeyMap中的每个缓存键值对，然后再遍历每个座位类型和数量信息
        //对于每个座位类型和数量信息，通过instance.opsForHash().increment(cacheKey, String.valueOf(seatType), num)来对缓存的相应字段进行自增操作。
        cacheChangeKeyMap.forEach((cacheKey, cacheVal) -> cacheVal.forEach((seatType, num) -> instance.opsForHash().increment(cacheKey, String.valueOf(seatType), num)));
    }

    @Override
    public String mark() {
        return CanalExecuteStrategyMarkEnum.T_SEAT.getActualTable();
    }
}
