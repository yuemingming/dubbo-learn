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
package org.apache.dubbo.rpc.cluster.directory;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Router;

import java.util.List;

/**
 * StaticDirectory
 *
 */
public class StaticDirectory<T> extends AbstractDirectory<T> {
    /**
     * Invokers集合
     */
    private final List<Invoker<T>> invokers;

    public StaticDirectory(List<Invoker<T>> invokers) {
        this(null, invokers, null);
    }

    public StaticDirectory(List<Invoker<T>> invokers, List<Router> routers) {
        this(null, invokers, routers);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers) {
        this(url, invokers, null);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers, List<Router> routers) {
        // 默认使用 `url` 参数。当它为空时，使用 `invokers[0].url` 。
        super(url == null && invokers != null && !invokers.isEmpty() ? invokers.get(0).getUrl() : url, routers);
        if (invokers == null || invokers.isEmpty()) {
            throw new IllegalArgumentException("invokers == null");
        }
        this.invokers = invokers;
    }

    @Override
    public Class<T> getInterface() {
        //获取接口类
        return invokers.get(0).getInterface();
    }
    //检测服务目录是否可用
    @Override
    public boolean isAvailable() {
        // 若已经销毁，则不可用
        if (isDestroyed()) {
            return false;
        }
        // 任一一个 Invoker 可用，则为可用
        for (Invoker<T> invoker : invokers) {
            if (invoker.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // 若已经销毁， 跳过
        if (isDestroyed()) {
            return;
        }
        // 销毁
        super.destroy();
        // 销毁每个 Invoker
        for (Invoker<T> invoker : invokers) {
            invoker.destroy();
        }
        // 清空 Invoker 集合
        invokers.clear();
    }

    @Override
    protected List<Invoker<T>> doList(Invocation invocation) throws RpcException {

        return invokers;
    }

}
