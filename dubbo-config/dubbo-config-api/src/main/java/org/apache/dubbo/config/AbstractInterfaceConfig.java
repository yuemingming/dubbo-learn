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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AbstractDefaultConfig
 *
 * @export
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long serialVersionUID = -1559314110797223229L;
    /**
     * 本地接口客户端本地代理类名，用于在客户端执行本地逻辑，如本地缓存等。
     *
     * 该本地代理类的构造函数必须允许传入远程代理对象，构造函数：public XxxServiceLocal(XxxService xxxsSrvice)
     * 设为true，表示使用缺省类名，即：接口名+Local后缀
     */
    // local impl class name for the service interface
    protected String local;

    // local stub class name for the service interface
    /**
     * 服务接口客户端本地代理类名，用于在客户端执行本地逻辑，如本地缓存等
     *
     * 该本地代理类的构造函数必须允许传入远程代理对象，构造函数如：Public XxxServiceStu(XxxsERVICE XxxService)
     *
     * 设为true表示使用缺省代理类名，即:接口名+Stub后缀
     */
    protected String stub;

    // service monitor
    protected MonitorConfig monitor;

    // proxy type
    protected String proxy;

    // cluster type
    protected String cluster;

    // filter
    protected String filter;

    // listener
    protected String listener;

    // owner
    protected String owner;

    // connection limits, 0 means shared connection, otherwise it defines the connections delegated to the
    // current service
    protected Integer connections;

    // layer
    protected String layer;

    // application info
    protected ApplicationConfig application;

    // module info
    protected ModuleConfig module;

    // registry centers
    protected List<RegistryConfig> registries;

    // connection events
    protected String onconnect;

    // disconnection events
    protected String ondisconnect;

    // callback limits
    private Integer callbacks;

    // the scope for referring/exporting a service, if it's local, it means searching in current JVM only.
    private String scope;

    /**
     * 在ServiceConfig的初始化时被调用
     * 校验RegistryConfig配置数组
     * 实际上，该方法会初始化RegistryConfig配置属性
     */
    protected void checkRegistry() {
        //当RegistryConfig对象数组为空是，若有'dubbo.registry.address'配置，进行创建
        // for backward compatibility向后兼容
        if (registries == null || registries.isEmpty()) {
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                registries = new ArrayList<>();
                //按照规则切分注册中心地址，并创建对应的注册中心配置类，并添加到registries
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registries.add(registryConfig);
                }
            }
        }
        if ((registries == null || registries.isEmpty())) {
            throw new IllegalStateException((getClass().getSimpleName().startsWith("Reference")
                    ? "No such any registry to refer service in consumer "
                    : "No such any registry to export service in provider ")
                    + NetUtils.getLocalHost()
                    + " use dubbo version "
                    + Version.getVersion()
                    + ", Please add <dubbo:registry address=\"...\" /> to your spring config. If you want unregister, please set <dubbo:service registry=\"N/A\" />");
        }
        //读取环境变量和propertis配置到RegistryConfig数组
        for (RegistryConfig registryConfig : registries) {
            appendProperties(registryConfig);
        }
    }

    /**
     * 在ServiceConfig的初始化时被调用
     * 校验ApplicationConfig的配置
     * 实际上，该方法会初始化ApplicationConfig的配置属性
     */
    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // for backward compatibility向后兼容
        //当ApplicationConfig对象为空时，若有‘dubbo.application.name’配置，进行创建。

        if (application == null) {
            String applicationName = ConfigUtils.getProperty("dubbo.application.name");
            if (applicationName != null && applicationName.length() > 0) {
                application = new ApplicationConfig();
            }
        }
        if (application == null) {
            throw new IllegalStateException(
                    "No such application config! Please add <dubbo:application name=\"...\" /> to your spring config.");
        }
        //读取环境变量和properties配置到ApplicationConfig对象
        appendProperties(application);
        // 初始化优雅停机的超时时长，参见 http://dubbo.io/books/dubbo-user-book/demos/graceful-shutdown.html 文档。
        String wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(Constants.SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }
    }

    /**
     * 加载注册中心URL数组
     * @param provider 是否是服务提供者
     * @return URL数组
     */
    protected List<URL> loadRegistries(boolean provider) {
        //校验RegistryConfig配置数组
        checkRegistry();
        /**
         * TODO 为什么上面已经校验过一次checkRegistryConfig了，此时相当于已经获取了所有的注册中心地址，下面遍历的时候还要再来一次。
         */
        //创建注册中心URL数组
        List<URL> registryList = new ArrayList<>();
        if (registries != null && !registries.isEmpty()) {
            for (RegistryConfig config : registries) {
                String address = config.getAddress();
                if (address == null || address.length() == 0) {
                    //若address为空，则将其设为0.0.0.0
                    address = Constants.ANYHOST_VALUE;
                }
                //从系统中获得注册中心地址
                String sysaddress = System.getProperty("dubbo.registry.address");
                //系统配置覆盖配置文件配置
                if (sysaddress != null && sysaddress.length() > 0) {
                    address = sysaddress;
                }
                //有效的地址
                if (address.length() > 0 && !RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                    Map<String, String> map = new HashMap<String, String>();
                    //将各种配置对象，添加到`map`集合中
                    appendParameters(map, application);
                    appendParameters(map, config);
                    //添加`path` `dubbo` `timestamp` `pid`到map集合
                    map.put("path", RegistryService.class.getName());
                    map.put("dubbo", Version.getProtocolVersion());
                    map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
                    if (ConfigUtils.getPid() > 0) {
                        map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
                    }
                    //若不存在`protocol`参数，默认`dubbo`添加到`map`集合中.
                    if (!map.containsKey("protocol")) {
                        if (ExtensionLoader.getExtensionLoader(RegistryFactory.class).hasExtension("remote")) {
                            map.put("protocol", "remote");
                        } else {
                            map.put("protocol", "dubbo");
                        }
                    }
                    //解析地址,创建Dubbo URL数组。（数组大小可以为一）
                    List<URL> urls = UrlUtils.parseURLs(address, map);
                    //循环`url`，设置`registry`和`protocol`属性
                    for (URL url : urls) {
                        // 设置 `registry=${protocol}` 和 `protocol=registry` 到 URL
                        url = url.addParameter(Constants.REGISTRY_KEY, url.getProtocol());
                        url = url.setProtocol(Constants.REGISTRY_PROTOCOL);//将协议头设置为registry
                        //通过判断条件，决定是否添加url到registryList中，条件如下：
                        //(服务提供者 && register = true 或 null)
                        // || （非服务提供者 && subscribe = true 或 null）
                        if ((provider && url.getParameter(Constants.REGISTER_KEY, true))
                                || (!provider && url.getParameter(Constants.SUBSCRIBE_KEY, true))) {
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        return registryList;
    }

    /**
     * 加载监控中心URL
     * @param registryURL 注册中心URL
     * @return 监控中心URL
     */
    protected URL loadMonitor(URL registryURL) {
        //从属性配置中加载配置到MonitorConfig对象
        if (monitor == null) {
            String monitorAddress = ConfigUtils.getProperty("dubbo.monitor.address");
            String monitorProtocol = ConfigUtils.getProperty("dubbo.monitor.protocol");
            if ((monitorAddress == null || monitorAddress.length() == 0) && (monitorProtocol == null || monitorProtocol.length() == 0)) {
                return null;
            }

            monitor = new MonitorConfig();
            if (monitorAddress != null && monitorAddress.length() > 0) {
                monitor.setAddress(monitorAddress);
            }
            if (monitorProtocol != null && monitorProtocol.length() > 0) {
                monitor.setProtocol(monitorProtocol);
            }
        }
        appendProperties(monitor);
        //添加`interface` `dubbo` `timestamp` `pid`到map集合中
        Map<String, String> map = new HashMap<String, String>();
        map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
        map.put("dubbo", Version.getProtocolVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        //set ip
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry == null || hostToRegistry.length() == 0) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (NetUtils.isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);
        //将MonitorConfig，添加到`map`集合中
        appendParameters(map, monitor);
        appendParameters(map, application);
        //获得地址
        String address = monitor.getAddress();
        String sysaddress = System.getProperty("dubbo.monitor.address");
        if (sysaddress != null && sysaddress.length() > 0) {
            address = sysaddress;
        }
        //直连监控中心服务器地址
        if (ConfigUtils.isNotEmpty(address)) {
            //若不存在`protocol`参数，默认`dubbo`添加到`map`集合中
            if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                if (ExtensionLoader.getExtensionLoader(MonitorFactory.class).hasExtension("logstat")) {
                    map.put(Constants.PROTOCOL_KEY, "logstat");
                } else {
                    map.put(Constants.PROTOCOL_KEY, "dubbo");
                }
            }
            //解析地址，创建Dubbo URL对象。
            return UrlUtils.parseURL(address, map);
            //从注册中心发现监控中心地址
        } else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
            return registryURL.setProtocol("dubbo").addParameter(Constants.PROTOCOL_KEY, "registry").addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map));
        }
        return null;
    }

    /**
     * 校验接口和方法，在ServiceConfig的初始化时被调用
     * 1.接口类非空，并是接口
     * 2.方法在接口中已定义
     * @param interfaceClass 接口类
     * @param methods 方法数组
     */
    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        // interface cannot be null
        if (interfaceClass == null) {
            throw new IllegalStateException("interface not allow null!");
        }
        // to verify interfaceClass is an interface
        if (!interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        // check if methods exist in the interface
        if (methods != null && !methods.isEmpty()) {
            for (MethodConfig methodBean : methods) {
                String methodName = methodBean.getName();
                if (methodName == null || methodName.length() == 0) {
                    throw new IllegalStateException("<dubbo:method> name attribute is required! Please check: <dubbo:service interface=\"" + interfaceClass.getName() + "\" ... ><dubbo:method name=\"\" ... /></<dubbo:reference>");
                }
                boolean hasMethod = false;
                for (java.lang.reflect.Method method : interfaceClass.getMethods()) {
                    if (method.getName().equals(methodName)) {
                        hasMethod = true;
                        break;
                    }
                }
                if (!hasMethod) {
                    throw new IllegalStateException("The interface " + interfaceClass.getName()
                            + " not found method " + methodName);
                }
            }
        }
    }

    /**
     * 在ServiceConfig初始化被调用
     * 校验Stub和Mock相关配置
     * @param interfaceClass
     */
    protected void checkStubAndMock(Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(local)) {
            Class<?> localClass = ConfigUtils.isDefault(local) ? ReflectUtils.forName(interfaceClass.getName() + "Local") : ReflectUtils.forName(local);
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceClass.getName());
            }
            try {
                ReflectUtils.findConstructor(localClass, interfaceClass);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
            }
        }
        //`Stub`配置项校验
        if (ConfigUtils.isNotEmpty(stub)) {
            //`stub = true`的情况，使用接口 + `Stub`字符串
            Class<?> localClass = ConfigUtils.isDefault(stub) ? ReflectUtils.forName(interfaceClass.getName() + "Stub") : ReflectUtils.forName(stub);
            //Stub类，必须实现服务接口
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceClass.getName());
            }
            //Stub类，必须带有服务接口的构造方法
            try {
                ReflectUtils.findConstructor(localClass, interfaceClass);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
            }
        }
        // mock 配置校验
        if (ConfigUtils.isNotEmpty(mock)) {
            if (mock.startsWith(Constants.RETURN_PREFIX)) {// 处理 "return " 开头的情况
                String value = mock.substring(Constants.RETURN_PREFIX.length());
                // 校验 Mock 值，配置正确
                try {
                    MockInvoker.parseMockValue(value);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock json value in <dubbo:service ... mock=\"" + mock + "\" />");
                }
            } else {
                // 获得 Mock 类
                Class<?> mockClass = ConfigUtils.isDefault(mock) ? ReflectUtils.forName(interfaceClass.getName() + "Mock") : ReflectUtils.forName(mock);
                // 校验是否实现接口类
                if (!interfaceClass.isAssignableFrom(mockClass)) {
                    throw new IllegalStateException("The mock implementation class " + mockClass.getName() + " not implement interface " + interfaceClass.getName());
                }
                // 校验是否有默认构造方法
                try {
                    mockClass.getConstructor(new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException("No such empty constructor \"public " + mockClass.getSimpleName() + "()\" in mock implementation class " + mockClass.getName());
                }
            }
        }
    }

    /**
     * @return local
     * @deprecated Replace to <code>getStub()</code>
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(Boolean)</code>
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(String.valueOf(local));
        }
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(String)</code>
     */
    @Deprecated
    public void setLocal(String local) {
        checkName("local", local);
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(Boolean stub) {
        if (stub == null) {
            setStub((String) null);
        } else {
            setStub(String.valueOf(stub));
        }
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, "cluster", cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, "proxy", proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = Constants.REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, "filter", filter);
        this.filter = filter;
    }

    @Parameter(key = Constants.INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    public void setListener(String listener) {
        checkMultiExtension(InvokerListener.class, "listener", listener);
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasSymbol("layer", layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    public void setApplication(ApplicationConfig application) {
        this.application = application;
    }

    public ModuleConfig getModule() {
        return module;
    }

    public void setModule(ModuleConfig module) {
        this.module = module;
    }

    public RegistryConfig getRegistry() {
        return registries == null || registries.isEmpty() ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        this.registries = registries;
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({"unchecked"})
    public void setRegistries(List<? extends RegistryConfig> registries) {
        this.registries = (List<RegistryConfig>) registries;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(String monitor) {
        this.monitor = new MonitorConfig(monitor);
    }

    public void setMonitor(MonitorConfig monitor) {
        this.monitor = monitor;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        checkMultiName("owner", owner);
        this.owner = owner;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }
}