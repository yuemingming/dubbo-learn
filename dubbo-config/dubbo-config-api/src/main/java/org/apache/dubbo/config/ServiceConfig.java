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
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.model.ApplicationModel;
import org.apache.dubbo.config.model.ProviderModel;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.ServiceClassHolder;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.utils.NetUtils.LOCALHOST;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 *
 * 服务提供者暴露服务配置
 * 参数详细：https://dubbo.gitbooks.io/dubbo-user-book/references/xml/dubbo-service.html
 * 对应文档： http://dubbo.apache.org/zh-cn/docs/source_code_guide/export-service.html
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();
    /**
     * 延迟暴露执行器
     */
    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));
    /**
     * 服务配置对应的Dubbo URL数组
     *
     * 非配置
     */
    private final List<URL> urls = new ArrayList<URL>();
    /**
     * 服务配置暴露的Exporter。
     * URL：Exporter不一定是1:1的关系
     * 例如 {@link #scope} 未设置时，会暴露 Local + Remote 两个，也就是 URL ：Exporter = 1：2
     *      {@link #scope} 设置为空时，不会暴露，也就是 URL ：Exporter = 1：0
     *      {@link #scope} 设置为 Local 或 Remote 任一时，会暴露 Local 或 Remote 一个，也就是 URL ：Exporter = 1：1
     * 非配置
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();
    // interface type
    private String interfaceName;
    /**
     * {@link #interfaceName}对应的接口类
     */
    private Class<?> interfaceClass;
    // reference to interface impl
    private T ref;
    // service name
    private String path;
    // method configuration
    private List<MethodConfig> methods;
    private ProviderConfig provider;
    /**
     * 是否已经暴露服务，参见{@link #doExport()}方法。
     *
     * 非配置
     */
    private transient volatile boolean exported;
    /**
     * 是否已取消暴露服务,参见{@link #unexport()}方法
     *
     * 非配置
     */
    private transient volatile boolean unexported;
    /**
     * 是否泛化实现，参见 <a href="https://dubbo.gitbooks.io/dubbo-user-book/demos/generic-service.html">实现泛化调用</a>
     * true / false
     *
     * 状态字段，非配置。
     */
    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
    }

    @Deprecated
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        if (RANDOM_PORT_MAP.containsKey(protocol)) {
            return RANDOM_PORT_MAP.get(protocol);
        }
        return Integer.MIN_VALUE;
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    /**
     * 暴露服务
     * 步骤：
     *  1.进一步初始化ServiceConfig对象
     *  2.校验ServiceConfig对象的配置项
     *  3.使用ServiceConfig对象，生成Dubbo URL数组
     *  4.使用 Dubbo URL对象，暴露服务。
     */
    public synchronized void export() {
        //当export或者delay未配置，从ProviderConfig对象读取
        if (provider != null) {
            if (export == null) {
                export = provider.getExport();
            }
            if (delay == null) {
                delay = provider.getDelay();
            }
        }
        //不暴露服务（export ==  false），则不进行暴露服务逻辑  <dubbo:provider export="false" />
        if (export != null && !export) {
            return;
        }
        //延迟暴露 delay > 0,延时导出服务
        if (delay != null && delay > 0) {
            delayExportExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    doExport();
                }
            }, delay, TimeUnit.MILLISECONDS);
            //立即暴露
        } else {
            doExport();
        }
    }

    /**
     * 执行暴露服务
     */
    protected synchronized void doExport() {
        //检查是否可以暴露，若可以，标记已经暴露
        if (unexported) {
            throw new IllegalStateException("Already unexported!");
        }
        if (exported) {
            return;
        }
        exported = true;
        //校验接口名是否合法
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }
        //检查provider是否为空，如果为空则创建一个，并拼接属性配置（环境变量+properties属性）到ProviderConfig对象
        checkDefault();
        //从ProviderConfig对象中，读取application、module、registries、monitor、protocols配置对象
        if (provider != null) {
            if (application == null) {
                application = provider.getApplication();
            }
            if (module == null) {
                module = provider.getModule();
            }
            if (registries == null) {
                registries = provider.getRegistries();
            }
            if (monitor == null) {
                monitor = provider.getMonitor();
            }
            if (protocols == null) {
                protocols = provider.getProtocols();
            }
        }
        //从ModuleConfig对象中，读取registries、monitor对象
        if (module != null) {
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        //从applicationConfig对象中，读取registries、monitor对象
        if (application != null) {
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        //泛化接口实现
        if (ref instanceof GenericService) {
            //设置interfaceClass为GenericService.class
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                //设置generic = true;
                generic = Boolean.TRUE.toString();
            }
            //ref 非 GenericService类型
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //校验接口和方法 对interfaceClass，以及<dubbo:method>标签中的必要字段进行检查
            checkInterfaceAndMethods(interfaceClass, methods);
            //校验指向的service对象 对ref合法性进行检查
            checkRef();
            //设置generic = "false"
            generic = Boolean.FALSE.toString();
        }
        //处理服务接口客户端本地代理（‘local相关’）。实际目前已经废弃，使用stub属性，参见 `AbstractInterfaceConfig#setLocal` 方法。
        if (local != null) {
            //设为true，表示使用缺省代理类名，即：接口名+local后缀
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                //获取本地存根类
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //检测本地存根类是否可赋值给接口类，若不可赋值则会抛出异常，提醒使用者本地存根类型不合法。
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //处理服务接口客户端本地代理（‘stub’）相关
        if (stub != null) {
            //设为true，表示使用缺省代理类名，即：接口名+Stub后缀。
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //校验ApplicationConfig配置。
        checkApplication();
        //校验RegistryConfig配置
        checkRegistry();
        //校验ProtocolConfig配置数组
        checkProtocol();
        //读取环境变量和properties到ServiceConfig对象。
        appendProperties(this);
        //校验Stub和Mock相关的配置
        checkStubAndMock(interfaceClass);
        //服务路径，缺省为接口名
        if (path == null || path.length() == 0) {
            path = interfaceName;
        }
        //暴露服务
        doExportUrls();
        //等待qos
        //ProviderModel表示服务类型提供者模型，此对象中存储了与服务提供者相关的信息。
        //比如服务的配置信息，服务实例等。每个被导出的服务对应一个providerModel。
        //ApplicationModel持有所有ProviderModel。
        ProviderModel providerModel = new ProviderModel(getUniqueServiceName(), this, ref);
        ApplicationModel.initProviderModel(getUniqueServiceName(), providerModel);
    }

    /**
     * 校验指向的service对象
     * 1.非空
     * 2.实现
     */
    private void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("unexpected err when unexport" + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    /**
     * 暴露 Dubbo URL
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        //加载注册中心URL数组
        List<URL> registryURLs = loadRegistries(true);
        //循环`protocols`，向逐个注册中心暴露服务
        for (ProtocolConfig protocolConfig : protocols) {
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    /**
     * 生成
     * @param protocolConfig
     * @param registryURLs
     */
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();
        //如果协议名为空，或空串，则将协议名设置为dubbo
        if (name == null || name.length() == 0) {
            name = "dubbo";
        }
        //将'side','dubbo','timestamp','pid'参数，添加到‘map’集合中
        Map<String, String> map = new HashMap<>();
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        //通过反射将将各种配置对象的字段，添加到‘map’集合中
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, provider, Constants.DEFAULT_KEY);
        appendParameters(map, protocolConfig);
        appendParameters(map, this);
        //将methodConfig对象数组，添加到‘map’集合中，对应标签<dubbo:method>的配置信息
        if (methods != null && !methods.isEmpty()) {
            //添加Callback配置到map中
            for (MethodConfig method : methods) {
                //将MethodConfig对象，添加到‘map’集合中，键 = 方法名.属性名
                //比如存储 <dubbo:method name = "sayHello" retries="2"> 对应的methodConfig
                //键 = sayHello.retries,map={"sayHello.retries":2,"xxx":"yyy"}
                appendParameters(map, method, method.getName());
                //当配置了‘MethodConfig.retry = false’时，强制禁用重试
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    //加测MethodConfig retry 是否为false,若是，则设置重试次数为0
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                //将ArgumentConfig对象数组，添加到‘Map’集合中
                List<ArgumentConfig> arguments = method.getArguments();
                if (arguments != null && !arguments.isEmpty()) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type 检测type属性是否为空，或者空串(分支1)
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods 对比方法名，查找目标方法
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {//找到指定方法
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        //检测ArgumentConfig中的type属性与方法参数列表中的参数是否一致，不一致则抛出异常
                                        if (argument.getIndex() != -1) {//指定单个参数的位置+类型
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                //将AtgumentConfig对象，添加到‘map’集合中
                                                //添加ArgumentConfig字段信息达到map中，
                                                //键前缀=方法名.index，比如：
                                                //map = {"sayHello.3":true}
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            //从参数列表中查找类型名称为argument.type的参数。
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            //用户未配置type属性，但配置了index属性，且index != -1
                        } else if (argument.getIndex() != -1) {//指定单个参数的位置
                            //将ArgumentConfig对象，添加到‘map’集合中
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }
        // generic、methods、revision
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(Constants.GENERIC_KEY, generic);
            map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
        } else {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }
            //为借口生成包裹类Wrapper，wrapper中包含了接口详细信息，比如接口方法名数组，字段信息等。
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            //添加方法名到map中，如果包含多个方法名，则用逗号隔开，比如method=init,destroy
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
            } else {
                //将逗号作为分隔符连接方法名，并将连接后的字符串放入map中
                map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<>(Arrays.asList(methods)), ","));
            }
        }
        // token ，参见《令牌校验》https://dubbo.gitbooks.io/dubbo-user-book/demos/token-authorization.html
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                //随机生成token
                map.put(Constants.TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(Constants.TOKEN_KEY, token);
            }
        }
        // 协议为 injvm 时，不注册，不通知。
        if (Constants.LOCAL_PROTOCOL.equals(protocolConfig.getName())) {
            protocolConfig.setRegister(false);
            map.put("notify", "false");
        }
        // export service 获取上下文路径
        String contextPath = protocolConfig.getContextpath();
        if ((contextPath == null || contextPath.length() == 0) && provider != null) {
            contextPath = provider.getContextpath();
        }
        //获取host和port
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        Integer port = this.findConfigedPorts(protocolConfig, name, map);
        //创建Dubbo URL对象
        URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath + "/") + path, map);

        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            //加载ConfiguratorFactory,并生成Configurator实例，然后通过实例配置url
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(Constants.SCOPE_KEY);
        //如果scope设置为none则什么都不做
        // don't export when none is configured
        if (!Constants.SCOPE_NONE.equalsIgnoreCase(scope)) {
            //没有设置为远程暴露时，导出到本地
            // export to local if the config is not remote (export to remote only when config is remote)
            if (!Constants.SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                exportLocal(url);
            }
            //服务远程暴露
            // export to remote if the config is not local (export to local only when config is local)
            if (!Constants.SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                if (registryURLs != null && !registryURLs.isEmpty()) {
                    for (URL registryURL : registryURLs) {
                        //“dynamic”：服务是否动态注册，如果设为false,注册后将显示后disable状态，需人工启用，并且服务提供者停止时，也不
                        //会自动取消，需人工禁用。
                        url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
                        //获得监控中心URL
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            //将监视器连接作为参数添加到url中
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        String proxy = url.getParameter(Constants.PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(Constants.PROXY_KEY, proxy);
                        }
                        //使用ProxyFactory创建Invoker对象。
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
                        //创建DelegateProviderMetaDataInvoker对象
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                        //使用Protocol暴露Invoker对象
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        //添加到'exporters'
                        exporters.add(exporter);
                    }
                } else {//用于被服务消费者直连服务提供者，参见文档 https://dubbo.gitbooks.io/dubbo-user-book/demos/explicit-target.html 。主要用于开发测试环境使用。
                    //使用ProxyFacory创建Invoker对象
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    //创建DelegateProviderMetaDataInvoker对象
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                    //使用Protocol暴露Invoker对象
                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    //添加到'exporters'
                    exporters.add(exporter);
                }
            }
        }
        this.urls.add(url);
    }

    /**
     *  本地暴露服务
     * @param url 注册中心URL
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        //如果URL的协议头等于injvm，说明已经到处到本地了，无需再次导出。
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            //创建本地URL
            URL local = URL.valueOf(url.toFullString())
                    .setProtocol(Constants.LOCAL_PROTOCOL)//设置协议头为jvm
                    .setHost(LOCALHOST)
                    .setPort(0);
            //添加服务的真是类名，例如DemoServiceImpl，仅用于RestProtocol中。
            ServiceClassHolder.getInstance().pushServiceClass(getServiceClass(ref));
            //使用ProxyFactory创建Invoker对象
            //使用Protocol暴露Invoker对象
            Exporter<?> exporter = protocol.export(
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
            //添加到`exporters`
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * 查找主机Host，参见文档《主机绑定》https://dubbo.gitbooks.io/dubbo-user-book/demos/hostname-binding.html
     * 推荐阅读文章《dubbo注册服务IP解析异常及IP解析源码分析》 https://segmentfault.com/a/1190000010550512
     * @param protocolConfig 协议配置对象
     * @param registryURLs 注册中心 URL 数组
     * @param map 参数集合
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;
        //第一优先级，从环境变量，获得绑定的host.可强制指定，参见仓库https://githup.com/dubbo/dubbo-docker-sample
        String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) { // 若是非法的本地 Host 【这个方法，胖友需要看下的】，抛出异常
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + Constants.DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (hostToBind == null || hostToBind.length() == 0) {
            //第二优先级，从ProtocolConfig获得Host
            hostToBind = protocolConfig.getHost();
            if (provider != null && (hostToBind == null || hostToBind.length() == 0)) {
                hostToBind = provider.getHost();
            }
            //第三优先级，若非合法的本地Host，使用InetAddress.getLocalHost().getHostAddress()获得Host
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                // 第四优先级，若是非法的本地 Host ，通过使用 `registryURLs` 启动 Server ，并本地连接，获得 Host 。
                if (isInvalidLocalHost(hostToBind)) {
                    if (registryURLs != null && !registryURLs.isEmpty()) {
                        for (URL registryURL : registryURLs) {
                            if (Constants.MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            try {
                                Socket socket = new Socket();
                                try {
                                    SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                    socket.connect(addr, 1000);
                                    hostToBind = socket.getLocalAddress().getHostAddress();
                                    break;
                                } finally {
                                    try {
                                        socket.close();
                                    } catch (Throwable e) {
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    //第五优先级，若是非法的本地Host，获得本地网卡，第一个合法的IP
                    if (isInvalidLocalHost(hostToBind)) {
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(Constants.BIND_IP_KEY, hostToBind);
        // 获得 `hostToRegistry` ，默认使用 `hostToBind` 。可强制指定，参见仓库 https://github.com/dubbo/dubbo-docker-sample
        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (hostToRegistry == null || hostToRegistry.length() == 0) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }

    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *查找端口，参见文档《主机绑定》https://dubbo.gitbooks.io/dubbo-user-book/demos/hostname-binding.html
     * @param protocolConfig 协议配置对象
     * @param name 协议名
     * @return 端口
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        //第一优先级，从环境变量中获取，获得绑定的Prot。可强制指定，参见仓库https://githup.com/dubbo/dubbo-docker-sample
        String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            //第二优先级，从ProtocolConfig获得Port
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            //第三优先级，获得协议对应的缺省端口
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            //第四优先级，随机获得端口
            if (portToBind == null || portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    //获得可用端口
                    portToBind = getAvailablePort(defaultPort);
                    //添加到缓存
                    putRandomPort(name, portToBind);
                }
                logger.warn("Use random available port(" + portToBind + ") for protocol " + name);
            }
        }

        // save bind port, used as url's key later
        map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));
        // 获得 `portToRegistry` ，默认使用 `portToBind` 。可强制指定，参见仓库 https://github.com/dubbo/dubbo-docker-sample
        // registry port, not used as bind port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (port == null || port.length() == 0) {
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    /**
     * 校验ProviderConfig配置
     * 实际上，会拼接属性配置（环境变量+properties属性）到ProviderConfig对象。
     */
    private void checkDefault() {
        if (provider == null) {
            provider = new ProviderConfig();
        }
        appendProperties(provider);
    }

    /**
     * 校验ProtocolConfig配置数组
     * 实际上，会拼接属性配置（环境变量+properties属性）到ProtocolConfig对象数组
     */
    private void checkProtocol() {
        //当ProtocolConfig对象数组为空时，优先使用'ProviderConfig.protocols'。其次，进行创建。
        if ((protocols == null || protocols.isEmpty())
                && provider != null) {
            setProtocols(provider.getProtocols());
        }
        // backward compatibility
        if (protocols == null || protocols.isEmpty()) {
            setProtocol(new ProtocolConfig());
        }
        //拼接属性配置（环境变量+properties属性）到ProtocolConfig对象数组
        for (ProtocolConfig protocolConfig : protocols) {
            //默认使用协议dubbo    public static final String DUBBO_VERSION_KEY = "dubbo";
            if (StringUtils.isEmpty(protocolConfig.getName())) {
                protocolConfig.setName(Constants.DUBBO_VERSION_KEY);
            }
            appendProperties(protocolConfig);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName(Constants.PATH_KEY, path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        StringBuilder buf = new StringBuilder();
        if (group != null && group.length() > 0) {
            buf.append(group).append("/");
        }
        buf.append(interfaceName);
        if (version != null && version.length() > 0) {
            buf.append(":").append(version);
        }
        return buf.toString();
    }
}
