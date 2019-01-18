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
package org.apache.dubbo.remoting.zookeeper.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 实现ZookeeperClient接口，Zookeeper客户端抽象类，实现通用逻辑。
 * @param <TargetChildListener>
 */
public abstract class AbstractZookeeperClient<TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);
    /**
     * 注册中心URL
     */
    private final URL url;
    /**
     * StateListenner集合
     */
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();
    /**
     * ChildListener集合
     * key1:节点路径
     * key2:ChildListener对象
     * value:监听器具体对象。不同zookeeper客户端，实现会不同
     */
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();
    /**
     * 是否关闭
     */
    private volatile boolean closed = false;

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void create(String path, boolean ephemeral) {
        //如果是持久节点，先检查节点是否存在？
        if (!ephemeral) {
            if (checkExists(path)) {
                return;
            }
        }
        //循环创建父路径
        int i = path.lastIndexOf('/');
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        //创建临时节点
        if (ephemeral) {
            createEphemeral(path);
        //创建持久节点
        } else {
            createPersistent(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    /**
     * 添加ChildListener监听器
     * @param path 节点路径
     * @param listener 监听器
     * @return
     */
    @Override
    public List<String> addChildListener(String path, final ChildListener listener) {
        //获得路径下的监听器数组
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners == null) {
            childListeners.putIfAbsent(path, new ConcurrentHashMap<ChildListener, TargetChildListener>());
            listeners = childListeners.get(path);
        }
        //获得是否已经有监听器
        TargetChildListener targetListener = listeners.get(listener);
        if (targetListener == null) {
            listeners.putIfAbsent(listener, createTargetChildListener(path, listener));
            targetListener = listeners.get(listener);
        }
        //向zookeeper，真正发起订阅
        return addTargetChildListener(path, targetListener);
    }

    @Override
    public void removeChildListener(String path, ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                //想zookeeper，真正发起取消订阅
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    /**
     * StateListener 数组，回调
     * @param state 状态
     */
    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();//关闭zookeeper连接
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    protected abstract void doClose();

    protected abstract void createPersistent(String path);

    protected abstract void createEphemeral(String path);

    protected abstract boolean checkExists(String path);

    /**
     * 创建真正的ChildListener
     * @param path
     * @param listener
     * @return
     */
    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    /**
     * 向Zookeeper，真正发起订阅
     * @param path
     * @param listener
     * @return
     */
    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    /**
     * 向Zookeeper，真正发起取消订阅
     * @param path
     * @param listener
     */
    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

}
