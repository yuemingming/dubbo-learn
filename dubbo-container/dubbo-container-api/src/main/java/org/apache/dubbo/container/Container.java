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
package org.apache.dubbo.container;

import org.apache.dubbo.common.extension.SPI;

/**
 * Container. (SPI, Singleton, ThreadSafe)
 * 服务容器是一个standalone的启动程序，因为后台服务不需要Tomcat或JBoss等web容器的功能，如果硬要用web容器去加载
 * 服务提供方，增加复杂性，也浪费资源。
 * 服务容器只是一个简单的Main方法，并加载一个简单的Spring容器，用于暴露服务。
 * 容器的加载内容可以扩展，内置了spring，jetty，log4j等加载，可通过容器扩展点进行扩展。配置配在java命令的-D参数
 * 或者dubbo.properties
 */
@SPI("spring")
public interface Container {

    /**
     * start.
     *
     * 启动
     */
    void start();

    /**
     * stop.
     *
     *
     * 停止
     */
    void stop();

}