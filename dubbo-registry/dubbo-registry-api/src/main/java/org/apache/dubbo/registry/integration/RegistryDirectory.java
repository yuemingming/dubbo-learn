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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterFactory;
import org.apache.dubbo.rpc.cluster.directory.AbstractDirectory;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RegistryDirectory
 * 基于注册中心的Directory实现类 实现了NotifyListener接口之后，能够监听注册中心配置变化。
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);
    /**
     * Cluster$Adaptive对象
     */
    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();
    /**
     * RouterFactory$Adaptive对象
     */
    private static final RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();
    /**
     * ConfiguratoFactory$Adaptive对象
     */
    private static final ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).getAdaptiveExtension();
    //======================服务消费者相关========================
    /**
     * 服务类型
     */
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    /**
     * Consumer URL的配置项Map
     */
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    /**
     * 服务方法数组
     */
    private final String[] serviceMethods;
    /**
     * 是否引用多分组
     */
    private final boolean multiGroup;
    //==================注册中心==================
    /**
     * 注册中心服务类，目前是com.alibaba.dubboregistry.RegistryService
     *
     * 通过{@link #url}的{@link URL#getServiceKey()}获得
     */
    private final String serviceKey; // Initialization at construction time, assertion not null
    /**
     * 注册中心的Protocol类
     */
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    /**
     * 注册中心
     */
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    /**
     * 是否禁止访问
     * 有两种情况会导致
     *
     * 1.没有服务提供者
     * 2.服务提供者会被禁用
     */
    private volatile boolean forbidden = false;
    //=================配置规则相关======================
    /**
     * 原始的目录URL
     */
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    /**
     * 覆写的目录URL，结合配置规则
     */
    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    /**
     * 配置规则数组
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference
    //===================服务提供者相关BEGIN==================
    // Map<url, Invoker> cache service url to invoker mapping.
    /**
     * [url]与[服务提供者]invoker集合的映射缓存
     */
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference
    /**
     * [方法名]与[服务提供者]invoker集合的映射缓存
     */
    // Map<methodName, Invoker> cache service method to invokers mapping.
    private volatile Map<String, List<Invoker<T>>> methodInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference
    /**
     * 服务提供者Invoker集合缓存
     */
    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0) {
            throw new IllegalArgumentException("registry serviceKey is null.");
        }
        this.serviceType = serviceType;
        this.serviceKey = url.getServiceKey();
        //获得queryMap
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        //获得overrideDirectoryUrl和directoryUrl
        this.overrideDirectoryUrl = this.directoryUrl = url.setPath(url.getServiceInterface()).clearParameters().addParameters(queryMap).removeParameter(Constants.MONITOR_KEY);
        //初始化multiGroup
        String group = directoryUrl.getParameter(Constants.GROUP_KEY, "");
        this.multiGroup = group != null && ("*".equals(group) || group.contains(","));
        //初始化serviceMethods
        String methods = queryMap.get(Constants.METHODS_KEY);
        this.serviceMethods = methods == null ? null : Constants.COMMA_SPLIT_PATTERN.split(methods);
    }

    /**
     * Convert override urls to map for use when re-refer.
     * Send all rules every time, the urls will be reassembled and calculated
     *
     * @param urls Contract:
     *             </br>1.override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules (all of the providers take effect)
     *             </br>2.override://ip:port...?anyhost=false Special rules (only for a certain provider)
     *             </br>3.override:// rule is not supported... ,needs to be calculated by registry itself.
     *             </br>4.override://0.0.0.0/ without parameters means clearing the override
     * @return
     */
    public static List<Configurator> toConfigurators(List<URL> urls) {
        // 忽略，若配置规则 URL 集合为空
        if (urls == null || urls.isEmpty()) {
            return Collections.emptyList();
        }
        // 创建 Configurator 集合
        List<Configurator> configurators = new ArrayList<Configurator>(urls.size());
        for (URL url : urls) {
            // 若协议为 `empty://` ，意味着清空所有配置规则，因此返回空 Configurator 集合
            if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            // 对应第 4 条契约，不带参数的 override://0.0.0.0/ 表示清除 override
            Map<String, String> override = new HashMap<String, String>(url.getParameters());
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            // override 上的 anyhost 可能是自动添加的，不能影响改变url判断
            override.remove(Constants.ANYHOST_KEY);
            if (override.size() == 0) {
                configurators.clear();
                continue;
            }
            // 获得 Configurator 对象，并添加到 `configurators` 中
            configurators.add(configuratorFactory.getConfigurator(url));
        }
        // 排序
        Collections.sort(configurators);
        return configurators;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public void subscribe(URL url) {
        //设置消费者URL
        setConsumerUrl(url);
        //向注册中心发起订阅
        registry.subscribe(url, this);
    }

    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }
        // unsubscribe.
        try {
            //取消订阅
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
        } catch (Throwable t) {
            logger.warn("unexpected error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        //标记已销毁
        super.destroy(); // must be executed after unsubscribing
        //销毁所有Invoker
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    @Override
    public synchronized void notify(List<URL> urls) {
        //根据URL的分类或协议，分组成三个集合
        List<URL> invokerUrls = new ArrayList<URL>();//服务提供者URL
        List<URL> routerUrls = new ArrayList<URL>();//路由url
        List<URL> configuratorUrls = new ArrayList<URL>();//配置器url
        for (URL url : urls) {
            String protocol = url.getProtocol();
            //获取category参数
            String category = url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
            //根据category参数将url分别放到不同的列表中
            if (Constants.ROUTERS_CATEGORY.equals(category)
                    || Constants.ROUTE_PROTOCOL.equals(protocol)) {
                //添加路由器url
                routerUrls.add(url);
            } else if (Constants.CONFIGURATORS_CATEGORY.equals(category)
                    || Constants.OVERRIDE_PROTOCOL.equals(protocol)) {
                //添加配置器url
                configuratorUrls.add(url);
            } else if (Constants.PROVIDERS_CATEGORY.equals(category)) {
                //添加服务提供者url
                invokerUrls.add(url);
            } else {
                //忽略不支持的category
                logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
            }
        }
        // configurators 处理配置规则URL集合
        if (configuratorUrls != null && !configuratorUrls.isEmpty()) {
            //将url转成Configurator
            this.configurators = toConfigurators(configuratorUrls);
        }
        // routers 处理路由规则URL
        if (routerUrls != null && !routerUrls.isEmpty()) {
            //将url转成router
            List<Router> routers = toRouters(routerUrls);
            if (routers != null) { // null - do nothing
                setRouters(routers);
            }
        }
        //合并配置规则，到'diretocyUrl'中，形成overrideDirectoryUrl变量
        List<Configurator> localConfigurators = this.configurators; // local reference
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {
                //配置overrideDirectoryUrl
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
        // providers 处理服务提供者URL集合 刷新Invoker列表
        refreshInvoker(invokerUrls);
    }

    /**
     * Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows:
     * 1.If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache, and notice that any parameter changes in the URL will be re-referenced.
     * 2.If the incoming invoker list is not empty, it means that it is the latest invoker list
     * 3.If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route rule, which needs to be re-contrasted to decide whether to re-reference.
     * 根据 invokerURL 列表转换为 invoker 列表。转换规则如下：
     *
     * 如果 url 已经被转换为 invoker ，则不在重新引用，直接从缓存中获取，注意如果 url 中任何一个参数变更也会重新引用
     * 如果传入的 invoker 列表不为空，则表示最新的 invoker 列表
     * 如果传入的 invokerUrl 列表是空，则表示只是下发的 override 规则或 route 规则，需要重新交叉对比，决定是否需要重新引用。
     * @param invokerUrls this parameter can't be null
     */
    // TODO: 2017/8/31 FIXME The thread pool should be used to refresh the address, otherwise the task may be accumulated.
    private void refreshInvoker(List<URL> invokerUrls) {
        //invokerUrls仅有一个元素，切url协议头为empty，此时表示禁用所有服务
        if (invokerUrls != null && invokerUrls.size() == 1 && invokerUrls.get(0) != null
                && Constants.EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            //设置禁止访问
            this.forbidden = true; // Forbid to access
            //// methodInvokerMap 置空
            this.methodInvokerMap = null; // Set the method invoker map to null
            // 销毁所有 Invoker 集合
            destroyAllInvokers(); // Close all invokers
        } else {
            //设置允许访问
            this.forbidden = false; // Allow to access
            // 引用老的 urlInvokerMap
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            // 传入的 invokerUrls 为空，说明是路由规则或配置规则发生改变，此时 invokerUrls 是空的，直接使用 cachedInvokerUrls 。
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                invokerUrls.addAll(this.cachedInvokerUrls);
                // 传入的 invokerUrls 非空，更新 cachedInvokerUrls 。
            } else {
                this.cachedInvokerUrls = new HashSet<URL>();
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison//缓存invokerUrls列表，便于交叉对比
            }
            // 忽略，若无 invokerUrls
            if (invokerUrls.isEmpty()) {
                return;
            }
            // 将传入的 invokerUrls ，转成新的 urlInvokerMap
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map
            // 转换出新的 methodInvokerMap
            Map<String, List<Invoker<T>>> newMethodInvokerMap = toMethodInvokers(newUrlInvokerMap); // Change method name to map Invoker Map
            // state change
            // If the calculation is wrong, it is not processed. 如果计算错误，则不进行处理.
            if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls.toString()));
                return;
            }
            // 若服务引用多 group ，则按照 method + group 聚合 Invoker 集合
            this.methodInvokerMap = multiGroup ? toMergeMethodInvokerMap(newMethodInvokerMap) : newMethodInvokerMap;
            // 销毁不再使用的 Invoker 集合
            this.urlInvokerMap = newUrlInvokerMap;
            try {
                //销毁无用的Invoker
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    private Map<String, List<Invoker<T>>> toMergeMethodInvokerMap(Map<String, List<Invoker<T>>> methodMap) {
        Map<String, List<Invoker<T>>> result = new HashMap<String, List<Invoker<T>>>();
        // 循环方法，按照 method + group 聚合 Invoker 集合
        for (Map.Entry<String, List<Invoker<T>>> entry : methodMap.entrySet()) {
            String method = entry.getKey();
            List<Invoker<T>> invokers = entry.getValue();
            // 按照 Group 聚合 Invoker 集合的结果。其中，KEY：group VALUE：Invoker 集合。
            Map<String, List<Invoker<T>>> groupMap = new HashMap<String, List<Invoker<T>>>();
            // 循环 Invoker 集合，按照 group 聚合 Invoker 集合
            for (Invoker<T> invoker : invokers) {
                //获取分组配置
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY, "");
                List<Invoker<T>> groupInvokers = groupMap.get(group);
                if (groupInvokers == null) {
                    groupInvokers = new ArrayList<Invoker<T>>();
                    //缓存<group,List<Invoker>>到groupMap中
                    groupMap.put(group, groupInvokers);
                }
                //存储Invoker到groupInvokers
                groupInvokers.add(invoker);
            }
            // 大小为 1，使用第一个
            if (groupMap.size() == 1) {
                result.put(method, groupMap.values().iterator().next());
                // 大于 1，将每个 Group 的 Invoker 集合，创建成 Cluster Invoker 对象。
                // groupMap.size() > 1 成立，表示 groupMap 中包含多组键值对，比如：
                // {
                //     "dubbo": [invoker1, invoker2, invoker3, ...],
                //     "hello": [invoker4, invoker5, invoker6, ...]
                // }
            } else if (groupMap.size() > 1) {
                List<Invoker<T>> groupInvokers = new ArrayList<Invoker<T>>();
                for (List<Invoker<T>> groupList : groupMap.values()) {
                    //通过集群类合并每个分组对应的Invoker列表
                    groupInvokers.add(cluster.join(new StaticDirectory<>(groupList)));
                }
                //缓存结果
                result.put(method, groupInvokers);
                // 大小为 0 ，使用原有值
            } else {
                result.put(method, invokers);
            }
        }
        return result;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private List<Router> toRouters(List<URL> urls) {
        List<Router> routers = new ArrayList<Router>();
        if (urls == null || urls.isEmpty()) {
            return routers;
        }
        if (urls != null && !urls.isEmpty()) {
            for (URL url : urls) {
                if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                    continue;
                }
                String routerType = url.getParameter(Constants.ROUTER_KEY);
                if (routerType != null && routerType.length() > 0) {
                    url = url.setProtocol(routerType);
                }
                try {
                    Router router = routerFactory.getRouter(url);
                    if (!routers.contains(router)) {
                        routers.add(router);
                    }
                } catch (Throwable t) {
                    logger.error("convert router url to router error, url: " + url, t);
                }
            }
        }
        return routers;
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     *
     * @param urls
     * @return invokers
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        // 新的 `newUrlInvokerMap`
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<String, Invoker<T>>();
        // 若为空，直接返回
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        // 已初始化的服务器提供 URL 集合
        Set<String> keys = new HashSet<String>();
        // 获得引用服务的协议
        String queryProtocols = this.queryMap.get(Constants.PROTOCOL_KEY);
        // 循环服务提供者 URL 集合，转成 Invoker 集合
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            // 如果 reference 端配置了 protocol ，则只选择匹配的 protocol
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                String[] acceptProtocols = queryProtocols.split(",");// 可配置多个协议
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    continue;
                }
            }
            // 忽略，若为 `empty://` 协议
            if (Constants.EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }
            // 忽略，若应用程序不支持该协议
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() + " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost()
                        + ", supported protocol: " + ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            // 合并 URL 参数
            URL url = mergeUrl(providerUrl);
            // 忽略，若已经初始化
            String key = url.toFullString(); // The parameter urls are sorted
            if (keys.contains(key)) { // Repeated url
                continue;
            }
            //添加到keys
            keys.add(key);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            // 如果服务端 URL 发生变化，则重新 refer 引用
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            if (invoker == null) { // Not in the cache, refer again 未在缓存中，重新引用
                try {
                    //判断是否开启
                    boolean enabled = true;
                    if (url.hasParameter(Constants.DISABLED_KEY)) {
                        enabled = !url.getParameter(Constants.DISABLED_KEY, false);
                    } else {
                        enabled = url.getParameter(Constants.ENABLED_KEY, true);
                    }
                    //若开启，创建Invoker对象
                    if (enabled) {
                        //调用refer获取Invoker
                        invoker = new InvokerDelegate<T>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                //添加到 newUrlInvokerMap 中
                if (invoker != null) { // Put new invoker in cache
                    newUrlInvokerMap.put(key, invoker);
                }
            } else {// 在缓存中，直接使用缓存的 Invoker 对象，添加到 newUrlInvokerMap 中
                newUrlInvokerMap.put(key, invoker);
            }
        }
        //清空keys
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        //合并消费端参数
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters
        //合并配置规则
        List<Configurator> localConfigurators = this.configurators; // local reference
        if (localConfigurators != null && !localConfigurators.isEmpty()) {
            for (Configurator configurator : localConfigurators) {
                providerUrl = configurator.configure(providerUrl);
            }
        }
        //不检查连接是否成功，总是创建 Invoker ！
        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        // 仅合并提供者参数，因为 directoryUrl 与 override 合并是在 notify 的最后，这里不能够处理
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters// 合并提供者参数

        // 【忽略】因为是对 1.0 版本的兼容
        if ((providerUrl.getPath() == null || providerUrl.getPath().length() == 0)
                && "dubbo".equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(Constants.INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        //返回服务提供者的URL
        return providerUrl;
    }

    private List<Invoker<T>> route(List<Invoker<T>> invokers, String method) {
        Invocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
        List<Router> routers = getRouters();
        if (routers != null) {
            for (Router router : routers) {
                // If router's url not null and is not route by runtime,we filter invokers here
                if (router.getUrl() != null && !router.getUrl().getParameter(Constants.RUNTIME_KEY, false)) {
                    invokers = router.route(invokers, getConsumerUrl(), invocation);
                }
            }
        }
        return invokers;
    }

    /**
     * Transform the invokers list into a mapping relationship with a method
     *
     * @param invokersMap Invoker Map
     * @return Mapping relation between Invoker and method
     */
    private Map<String, List<Invoker<T>>> toMethodInvokers(Map<String, Invoker<T>> invokersMap) {
        //创建新的MethodInvokerMap
        Map<String, List<Invoker<T>>> newMethodInvokerMap = new HashMap<String, List<Invoker<T>>>();
        // According to the methods classification declared by the provider URL, the methods is compatible with the registry to execute the filtered methods
        // 创建 Invoker 集合
        List<Invoker<T>> invokersList = new ArrayList<Invoker<T>>();
        // 按服务提供者 URL 所声明的 methods 分类，兼容注册中心执行路由过滤掉的 methods
        if (invokersMap != null && invokersMap.size() > 0) {
            //循环每个服务的Invoker
            for (Invoker<T> invoker : invokersMap.values()) {
                //获取methods参数
                String parameter = invoker.getUrl().getParameter(Constants.METHODS_KEY);
                if (parameter != null && parameter.length() > 0) {
                    //切分method参数值，得到方法名数组
                    String[] methods = Constants.COMMA_SPLIT_PATTERN.split(parameter);
                    if (methods != null && methods.length > 0) {
                        // 循环每个方法，按照方法名为维度，聚合到 `methodInvokerMap` 中
                        for (String method : methods) {
                            // 当服务提供者的方法为 "*" ，代表泛化调用
                            if (method != null && method.length() > 0
                                    && !Constants.ANY_VALUE.equals(method)) {
                                //根据方法名获取Invoker列表
                                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                                if (methodInvokers == null) {
                                    methodInvokers = new ArrayList<Invoker<T>>();
                                    newMethodInvokerMap.put(method, methodInvokers);
                                }
                                //存储到Invoker到列表中
                                methodInvokers.add(invoker);
                            }
                        }
                    }
                }
                // 添加到 `invokersList` 中
                invokersList.add(invoker);
            }
        }
        // 路由全 `invokersList` ，匹配合适的 Invoker 集合。进行服务器级别路由，参考pull request #749
        List<Invoker<T>> newInvokersList = route(invokersList, null);
        // 添加 `newInvokersList` 到 `newMethodInvokerMap` 中，表示该服务提供者的全量 Invoker 集合
        //存储<*,newInvokerList>映射关系
        newMethodInvokerMap.put(Constants.ANY_VALUE, newInvokersList);
        // 循环，基于每个方法路由，匹配合适的 Invoker 集合
        if (serviceMethods != null && serviceMethods.length > 0) {
            for (String method : serviceMethods) {
                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                if (methodInvokers == null || methodInvokers.isEmpty()) {
                    methodInvokers = newInvokersList;
                }
                //进行方法级别路由
                newMethodInvokerMap.put(method, route(methodInvokers, method));
            }
        }
        // 循环排序每个方法的 Invoker 集合，并设置为不可变
        // sort and unmodifiable
        for (String method : new HashSet<String>(newMethodInvokerMap.keySet())) {
            List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
            Collections.sort(methodInvokers, InvokerComparator.getComparator());
            newMethodInvokerMap.put(method, Collections.unmodifiableList(methodInvokers));
        }
        return Collections.unmodifiableMap(newMethodInvokerMap);
    }

    /**
     * Close all invokers
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            // 循环 urlInvokerMap ，销毁所有服务提供者 Invoker
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            // urlInvokerMap 清空
            localUrlInvokerMap.clear();
        }
        // methodInvokerMap 置空
        methodInvokerMap = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     * 销毁不再使用的invoke集合
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        // 防御性编程，目前不存在这个情况
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            // 销毁所有服务提供者 Invoker
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        // 对比新老集合，计算需要销毁的 Invoker 集合
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            //获取新生成的Invoker集合列表
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            //遍历老的<url,Invoker>映射表
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                // 若不存在，添加到 `deleted` 中
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<String>();
                    }
                    deleted.add(entry.getKey());
                }
            }
        }
        // 若有需要销毁的 Invoker ，则进行销毁
        if (deleted != null) {
            //遍历deleted集合，并到老的<url,Invoker>映射关系表查出Invoker，销毁之。
            for (String url : deleted) {
                if (url != null) {
                    // 移除出 `urlInvokerMap`
                    //从oldUrlInvokerMap中移除url对应的Invoker
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            // 销毁 Invoker
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] faild. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<Invoker<T>> doList(Invocation invocation) {
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            //服务提供者关闭或禁用了服务，此时抛出No provider异常。
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION,
                    "No provider available from registry " + getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " + NetUtils.getLocalHost()
                            + " use dubbo version " + Version.getVersion() + ", please check status of providers(disabled, not registered or in blacklist).");
        }
        // 获得 Invoker 集合
        List<Invoker<T>> invokers = null;
        Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // 获取Invoker本地缓存
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            //获得方法名
            String methodName = RpcUtils.getMethodName(invocation);
            //获得方法参数
            Object[] args = RpcUtils.getArguments(invocation);
            // 【第一】检测参数列表第一个参数是否为String或enum类型
            if (args != null && args.length > 0 && args[0] != null
                    && (args[0] instanceof String || args[0].getClass().isEnum())) {
                //通过方法名 + 第一个参数名称 查询Invoker列表
                invokers = localMethodInvokerMap.get(methodName + "." + args[0]); // The routing can be enumerated according to the first parameter
            }
            // 【第二】根据方法名获得 Invoker 集合
            if (invokers == null) {
                invokers = localMethodInvokerMap.get(methodName);
            }
            // 【第三】使用全量 Invoker 集合。例如，`#$echo(name)` ，回声方法
            if (invokers == null) {
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }
            // 【第四】使用 `methodInvokerMap` 第一个 Invoker 集合。防御性编程。冗余逻辑，pull request #2861 移除了下面的 if 分支代码
            if (invokers == null) {
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }
        //返回Invoker列表，此时有可能返回一个空的列表。
        return invokers == null ? new ArrayList<Invoker<T>>(0) : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    @Override
    public boolean isAvailable() {
        //若已销毁 不可用
        if (isDestroyed()) {
            return false;
        }
        //任意一个Invoker可用，则返回
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    /**
     * Haomin: added for test purpose
     * 实现 Comparator 接口，Invoker 排序器实现类，根据 URL 升序
     */
    public Map<String, List<Invoker<T>>> getMethodInvokerMap() {
        return methodInvokerMap;
    }

    private static class InvokerComparator implements Comparator<Invoker<?>> {
        /**
         * 单例
         */
        private static final InvokerComparator comparator = new InvokerComparator();

        private InvokerComparator() {
        }

        public static InvokerComparator getComparator() {
            return comparator;
        }

        @Override
        public int compare(Invoker<?> o1, Invoker<?> o2) {
            return o1.getUrl().toString().compareTo(o2.getUrl().toString());
        }

    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     * Invoker代理类，主要用于存储注册中心下发的url地址（providerUrl），用于重新更新refer时能够根据providerUrl queryMap overrideMap重新组装
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        /**
         * 服务提供者 url
         * 未经过配置合并
         */
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }
}
