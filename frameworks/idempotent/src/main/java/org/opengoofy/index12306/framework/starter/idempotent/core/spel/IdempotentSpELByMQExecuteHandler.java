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

package org.opengoofy.index12306.framework.starter.idempotent.core.spel;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.idempotent.annotation.Idempotent;
import org.opengoofy.index12306.framework.starter.idempotent.core.AbstractIdempotentExecuteHandler;
import org.opengoofy.index12306.framework.starter.idempotent.core.IdempotentAspect;
import org.opengoofy.index12306.framework.starter.idempotent.core.IdempotentContext;
import org.opengoofy.index12306.framework.starter.idempotent.core.IdempotentParamWrapper;
import org.opengoofy.index12306.framework.starter.idempotent.core.RepeatConsumptionException;
import org.opengoofy.index12306.framework.starter.idempotent.enums.IdempotentMQConsumeStatusEnum;
import org.opengoofy.index12306.framework.starter.idempotent.toolkit.LogUtil;
import org.opengoofy.index12306.framework.starter.idempotent.toolkit.SpELUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 基于 SpEL 方法验证请求幂等性，适用于 MQ 场景
 *
 *
 */
@RequiredArgsConstructor
public final class IdempotentSpELByMQExecuteHandler extends AbstractIdempotentExecuteHandler implements IdempotentSpELService {

    private final DistributedCache distributedCache;

    private final static int TIMEOUT = 600;
    private final static String WRAPPER = "wrapper:spEL:MQ";

    @SneakyThrows
    @Override
    protected IdempotentParamWrapper buildWrapper(ProceedingJoinPoint joinPoint) {
        //从切面中获取目标方法的@Idempotent注解
        Idempotent idempotent = IdempotentAspect.getIdempotent(joinPoint);
        //使用SpELUtil.parseKey方法解析幂等性key，将其中的SpEL表达式替换为实际的参数值
        String key = (String) SpELUtil.parseKey(idempotent.key(), ((MethodSignature) joinPoint.getSignature()).getMethod(), joinPoint.getArgs());
        // 构建并返回幂等性参数包装器对象
        return IdempotentParamWrapper.builder().lockKey(key).joinPoint(joinPoint).build();
    }

    @Override
    public void handler(IdempotentParamWrapper wrapper) {
        //拼接唯一key
        String uniqueKey = wrapper.getIdempotent().uniqueKeyPrefix() + wrapper.getLockKey();
        //尝试将key存储到redis中并设置过期时间
        Boolean setIfAbsent = ((StringRedisTemplate) distributedCache.getInstance())
                .opsForValue()
                .setIfAbsent(uniqueKey, IdempotentMQConsumeStatusEnum.CONSUMING.getCode(), TIMEOUT, TimeUnit.SECONDS);
        //如果key已经存在，判断消费状态
        if (setIfAbsent != null && !setIfAbsent) {
            //获取消费状态
            String consumeStatus = distributedCache.get(uniqueKey, String.class);

            boolean error = IdempotentMQConsumeStatusEnum.isError(consumeStatus);
            //输出日志并抛出异常
            LogUtil.getLog(wrapper.getJoinPoint()).warn("[{}] MQ repeated consumption, {}.", uniqueKey, error ? "Wait for the client to delay consumption" : "Status is completed");
            throw new RepeatConsumptionException(error);
        }
        //将wrapper对象放入上下文。
        IdempotentContext.put(WRAPPER, wrapper);
    }

    //出现异常时清理幂等性相关的缓存数据。
    @Override
    public void exceptionProcessing() {
        //从幂等上下文中获取幂等参数包装对象
        IdempotentParamWrapper wrapper = (IdempotentParamWrapper) IdempotentContext.getKey(WRAPPER);
        if (wrapper != null) {
            //从幂等包装对象中获取幂等注解类
            Idempotent idempotent = wrapper.getIdempotent();
            //构造唯一key，用于删除缓存
            String uniqueKey = idempotent.uniqueKeyPrefix() + wrapper.getLockKey();
            try {
                //删除分布式缓存中的数据。
                distributedCache.delete(uniqueKey);
            } catch (Throwable ex) {
                //记录日常日志
                LogUtil.getLog(wrapper.getJoinPoint()).error("[{}] Failed to del MQ anti-heavy token.", uniqueKey);
            }
        }
    }


    //当业务逻辑执行成功后，需要进行的一些后续处理
    @Override
    public void postProcessing() {
        //从幂等上下文中获取幂等参数包装对象
        IdempotentParamWrapper wrapper = (IdempotentParamWrapper) IdempotentContext.getKey(WRAPPER);
        if (wrapper != null) {
            //从幂等包装对象中获取幂等注解类
            Idempotent idempotent = wrapper.getIdempotent();
            //构造唯一key，用于添加到分布式缓存中
            String uniqueKey = idempotent.uniqueKeyPrefix() + wrapper.getLockKey();
            try {
                //存储到分布式缓存中，并设置一个过期时间。
                distributedCache.put(uniqueKey, IdempotentMQConsumeStatusEnum.CONSUMED.getCode(), idempotent.keyTimeout(), TimeUnit.SECONDS);
            } catch (Throwable ex) {
                //记录日常日志
                LogUtil.getLog(wrapper.getJoinPoint()).error("[{}] Failed to set MQ anti-heavy token.", uniqueKey);
            }
        }
    }
}
