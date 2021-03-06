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
package org.apache.dubbo.rpc.protocol.injvm;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.Map;

/**
 * InjvmProtocol
 */
public class InjvmProtocol extends AbstractProtocol implements Protocol {

    public static final String NAME = Constants.LOCAL_PROTOCOL;
    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 0;
    /**
     * 单例，在Dubbo SPI中，被初始化，有且仅有一次
     */
    private static InjvmProtocol INSTANCE;

    public InjvmProtocol() {
        INSTANCE = this;
    }

    public static InjvmProtocol getInjvmProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(InjvmProtocol.NAME); // load
        }
        return INSTANCE;
    }

    static Exporter<?> getExporter(Map<String, Exporter<?>> map, URL key) {
        Exporter<?> result = null;

        if (!key.getServiceKey().contains("*")) {
            result = map.get(key.getServiceKey());
        } else {
            if (map != null && !map.isEmpty()) {
                for (Exporter<?> exporter : map.values()) {
                    if (UrlUtils.isServiceKeyMatch(key, exporter.getInvoker().getUrl())) {
                        result = exporter;
                        break;
                    }
                }
            }
        }

        if (result == null) {
            return null;
        } else if (ProtocolUtils.isGeneric(
                result.getInvoker().getUrl().getParameter(Constants.GENERIC_KEY))) {
            return null;
        } else {
            return result;
        }
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
    }

    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        //创建InjvmInvoker对象。注意，传入的exporterMap参数，包含所有的InjvmExporter对象
        return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
    }

    /**
     * 是否是本地引用
     * @param url URL
     * @return 是否
     */
    public boolean isInjvmRefer(URL url) {
        final boolean isJvmRefer;
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // Since injvm protocol is configured explicitly, we don't need to set any extra flag, use normal refer process.
        //当`protocol = injvm`时，本身已经是jvm协议了，走正常流程就是了。
        if (Constants.LOCAL_PROTOCOL.toString().equals(url.getProtocol())) {
            isJvmRefer = false;
            //当`scope = local`或者`injvm = true`时，本地引用
        } else if (Constants.SCOPE_LOCAL.equals(scope) || (url.getParameter(Constants.LOCAL_PROTOCOL, false))) {
            // if it's declared as local reference
            // 'scope=local' is equivalent to 'injvm=true', injvm will be deprecated in the future release
            isJvmRefer = true;
            //当`scope = remote`时，远程引用。
        } else if (Constants.SCOPE_REMOTE.equals(scope)) {
            // it's declared as remote reference
            isJvmRefer = false;
            //当`generic = true`时，即使用泛化调用，远程使用
        } else if (url.getParameter(Constants.GENERIC_KEY, false)) {
            // generic invocation is not local reference
            isJvmRefer = false;
            //当本地有该Exporter时，本地引用
        } else if (getExporter(exporterMap, url) != null) {
            // by default, go through local reference if there's the service exposed locally
            isJvmRefer = true;
            //默认，远程引用
        } else {
            isJvmRefer = false;
        }
        return isJvmRefer;
    }
}
