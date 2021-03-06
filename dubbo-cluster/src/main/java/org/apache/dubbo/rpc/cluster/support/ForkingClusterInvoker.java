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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link org.apache.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link org.apache.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            //检查invokers 即可用Invoker集合是否为空，如果为空，那么抛出异常
            checkInvokers(invokers, invocation);
            //保存选择的invoker集合
            final List<Invoker<T>> selected;
            //得到最大并行数，默认为Constants.DEFAULT_FORKS = 2
            final int forks = getUrl().getParameter(Constants.FORKS_KEY, Constants.DEFAULT_FORKS);
            //获得调用超时时间，默认为DEFAULT_TIMEOUT = 1000毫秒
            final int timeout = getUrl().getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
            //若最大并行数小于等于0，或者大于invokers的数量，直接使用invokers
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;
            } else {
                //循环，根据负载均衡机制从invokers中选择一个invoker，从而组成invoker集合
                //注意，因为增加了排重逻辑，所以不能保证获得的invoker集合的大小，小于最大并行书
                selected = new ArrayList<>();
                for (int i = 0; i < forks; i++) {
                    // TODO. Add some comment here, refer chinese version for more details.
                    //在Invoker列表（排除selected）后，如果没有选够，则存在重复循环问题，见select实现
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    if (!selected.contains(invoker)) {//防止重复添加invoker
                        //Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }
            //设置已经调用的Invoker集合，到Context集合中
            RpcContext.getContext().setInvokers((List) selected);
            //异常计数器
            final AtomicInteger count = new AtomicInteger();
            //创建消费队列
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<>();
            //循环selected集合，提交线程池，发起RPC调用
            for (final Invoker<T> invoker : selected) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            //RPC调用，获得Result结果
                            Result result = invoker.invoke(invocation);
                            //添加Reslut到ref阻塞队列
                            ref.offer(result);
                        } catch (Throwable e) {
                            //异常计数器 + 1
                            int value = count.incrementAndGet();
                            //若RPC调用结果都是异常，则添加异常到'ref'阻塞队列
                            if (value >= selected.size()) {
                                ref.offer(e);
                            }
                        }
                    }
                });
            }
            try {
                //从ref队列中获得结果
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                //若是正常结果，则直接返回
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread.
            RpcContext.getContext().clearAttachments();
        }
    }
}
