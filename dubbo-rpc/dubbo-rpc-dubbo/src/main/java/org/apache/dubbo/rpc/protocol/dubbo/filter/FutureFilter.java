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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.PostProcessFilter;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.StaticContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * EventFilter
 */
@Activate(group = Constants.CONSUMER)
public class FutureFilter implements PostProcessFilter {

    protected static final Logger logger = LoggerFactory.getLogger(FutureFilter.class);

    @Override
    public Result invoke(final Invoker<?> invoker, final Invocation invocation) throws RpcException {
        //获得是否异步调用
        fireInvokeCallback(invoker, invocation);
        // need to configure if there's return value before the invocation in order to help invoker to judge if it's
        // necessary to return future.
        //调用方法
        return postProcessResult(invoker.invoke(invocation), invoker, invocation);
    }

    @Override
    public Result postProcessResult(Result result, Invoker<?> invoker, Invocation invocation) {
        //触发回调方法
        if (result instanceof AsyncRpcResult) {//异步回调
            AsyncRpcResult asyncResult = (AsyncRpcResult) result;
            asyncResult.thenApplyWithContext(r -> {
                asyncCallback(invoker, invocation, r);
                return r;
            });
            return asyncResult;
        } else {//同步回调
            syncCallback(invoker, invocation, result);
            return result;
        }
    }

    private void syncCallback(final Invoker<?> invoker, final Invocation invocation, final Result result) {
        if (result.hasException()) {//异常，触发异常回调
            fireThrowCallback(invoker, invocation, result.getException());
        } else {//正常，触发正常回调
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    private void asyncCallback(final Invoker<?> invoker, final Invocation invocation, Result result) {
        if (result.hasException()) {
            fireThrowCallback(invoker, invocation, result.getException());
        } else {
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    /**
     * 触发前置方法ß
     * @param invoker Invoker对象
     * @param invocation Invocation对象
     */
    private void fireInvokeCallback(final Invoker<?> invoker, final Invocation invocation) {
        //获得前置对象和方法
        final Method onInvokeMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_INVOKE_METHOD_KEY));
        final Object onInvokeInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_INVOKE_INSTANCE_KEY));

        if (onInvokeMethod == null && onInvokeInst == null) {
            return;
        }
        if (onInvokeMethod == null || onInvokeInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onInvokeMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onInvokeMethod.isAccessible()) {
            onInvokeMethod.setAccessible(true);
        }
        //调用前置方法
        Object[] params = invocation.getArguments();
        try {
            onInvokeMethod.invoke(onInvokeInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    private void fireReturnCallback(final Invoker<?> invoker, final Invocation invocation, final Object result) {
        //获得Onreturn方法和对象
        final Method onReturnMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_RETURN_METHOD_KEY));
        final Object onReturnInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_RETURN_INSTANCE_KEY));

        //not set onreturn callback
        if (onReturnMethod == null && onReturnInst == null) {
            return;
        }

        if (onReturnMethod == null || onReturnInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onReturnMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onReturnMethod.isAccessible()) {
            onReturnMethod.setAccessible(true);
        }

        Object[] args = invocation.getArguments();
        Object[] params;
        Class<?>[] rParaTypes = onReturnMethod.getParameterTypes();
        if (rParaTypes.length > 1) {
            if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                params = new Object[2];
                params[0] = result;
                params[1] = args;
            } else {
                params = new Object[args.length + 1];
                params[0] = result;
                System.arraycopy(args, 0, params, 1, args.length);
            }
        } else {
            params = new Object[]{result};
        }
        try {
            onReturnMethod.invoke(onReturnInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    private void fireThrowCallback(final Invoker<?> invoker, final Invocation invocation, final Throwable exception) {
        //获得'onreturn'方法和对象
        final Method onthrowMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_THROW_METHOD_KEY));
        final Object onthrowInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_THROW_INSTANCE_KEY));

        //onthrow callback not configured
        if (onthrowMethod == null && onthrowInst == null) {
            return;
        }
        if (onthrowMethod == null || onthrowInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onthrow callback config , but no such " + (onthrowMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onthrowMethod.isAccessible()) {
            onthrowMethod.setAccessible(true);
        }
        Class<?>[] rParaTypes = onthrowMethod.getParameterTypes();
        if (rParaTypes[0].isAssignableFrom(exception.getClass())) {
            try {
                Object[] args = invocation.getArguments();
                Object[] params;

                if (rParaTypes.length > 1) {
                    if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                        params = new Object[2];
                        params[0] = exception;
                        params[1] = args;
                    } else {
                        params = new Object[args.length + 1];
                        params[0] = exception;
                        System.arraycopy(args, 0, params, 1, args.length);
                    }
                } else {
                    params = new Object[]{exception};
                }
                //调用方法
                onthrowMethod.invoke(onthrowInst, params);
            } catch (Throwable e) {
                logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), e);
            }
        } else {//不符合异常，打印错误日志
            logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), exception);
        }
    }
}
