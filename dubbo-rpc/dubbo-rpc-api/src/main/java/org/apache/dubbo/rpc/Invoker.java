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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.Node;

/**
 * Invoker. (API/SPI, Prototype, ThreadSafe)
 * Invoker是实体域，他是Dubbo的核心模型，其他模型都向他靠拢，或转换成它。
 * 他代表一个可执行体，可向他发起invoke调用。
 * 他有可能是一个本地实现，也有可能是一个远程实现，也可能是一个集群实现。
 * @see org.apache.dubbo.rpc.Protocol#refer(Class, org.apache.dubbo.common.URL)
 * @see org.apache.dubbo.rpc.InvokerListener
 * @see org.apache.dubbo.rpc.protocol.AbstractInvoker
 */
public interface Invoker<T> extends Node {

    /**
     * get service interface.
     * 获得Service接口
     * @return service interface.
     */
    Class<T> getInterface();

    /**
     * invoke.
     * @param invocation
     * @return result
     * @throws RpcException
     */
    Result invoke(Invocation invocation) throws RpcException;

}