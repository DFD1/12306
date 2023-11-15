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

package org.opengoofy.index12306.biz.userservice.config;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 布隆过滤器配置
 *
 *
 */
@Configuration
//将 UserRegisterBloomFilterProperties 类注入到 Spring 容器中，以便在其他组件中使用。
@EnableConfigurationProperties(UserRegisterBloomFilterProperties.class)
public class RBloomFilterConfiguration {

    /**
     * 防止用户注册缓存穿透的布隆过滤器
     */
    @Bean
    public RBloomFilter<String> userRegisterCachePenetrationBloomFilter(RedissonClient redissonClient, UserRegisterBloomFilterProperties userRegisterBloomFilterProperties) {
        //从 Redisson 客户端中获取名为 user_register_cache_penetration_bloom_filter 的 RBloomFilter 实例，然后将其赋值给了名为 cachePenetrationBloomFilter 的
        //创建一个新的名为 user_register_cache_penetration_bloom_filter 的 RBloomFilter 实例
        RBloomFilter<String> cachePenetrationBloomFilter = redissonClient.getBloomFilter(userRegisterBloomFilterProperties.getName());
        //配置布隆过滤器期望插入的数量和误差概率
        cachePenetrationBloomFilter.tryInit(userRegisterBloomFilterProperties.getExpectedInsertions(), userRegisterBloomFilterProperties.getFalseProbability());
        return cachePenetrationBloomFilter;
    }
}
