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
package org.apache.dubbo.cache.filter;

import java.io.Serializable;

import org.apache.dubbo.cache.Cache;
import org.apache.dubbo.cache.CacheFactory;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcResult;

/**
 * CacheFilter
 * 用于服务消费者和提供者中，提供 结果缓存 的功能
 * 结果缓存 ，用于加速热门数据的访问速度，Dubbo 提供声明式缓存，以减少用户加缓存的工作量。
 */
@Activate(group = {Constants.CONSUMER, Constants.PROVIDER}, value = Constants.CACHE_KEY)
public class CacheFilter implements Filter {
    /**
     *  CacheFactory$Adaptive 对象。
     *  通过 Dubbo SPI 机制，调用 {@link #setCacheFactory(CacheFactory)} 方法，进行注入
     */
    private CacheFactory cacheFactory;

    public void setCacheFactory(CacheFactory cacheFactory) {
        this.cacheFactory = cacheFactory;
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        //方法开启cache功能
        if (cacheFactory != null && ConfigUtils.isNotEmpty(invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.CACHE_KEY))) {
            // 基于 URL + Method 为维度，获得 Cache 对象。
            Cache cache = cacheFactory.getCache(invoker.getUrl(), invocation);
            if (cache != null) {
                //获得cache key`
                String key = StringUtils.toArgumentString(invocation.getArguments());
                // 从缓存中获得结果。若存在，创建 RpcResult 对象。
                Object value = cache.get(key);
                if (value != null) {
                    if (value instanceof ValueWrapper) {
                        return new RpcResult(((ValueWrapper)value).get());
                    } else {
                        return new RpcResult(value);
                    }
                }
                //服务调用
                Result result = invoker.invoke(invocation);
                //如果返回并且没有出现异常，则添加到缓存中
                if (!result.hasException()) {
                    cache.put(key, new ValueWrapper(result.getValue()));
                }
                return result;
            }
        }
        // 服务调用
        return invoker.invoke(invocation);
    }
    
    static class ValueWrapper implements Serializable{

        private static final long serialVersionUID = -1777337318019193256L;

        private final Object value;

        public ValueWrapper(Object value){
            this.value = value;
        }

        public Object get() {
            return this.value;
        }
    }
}
