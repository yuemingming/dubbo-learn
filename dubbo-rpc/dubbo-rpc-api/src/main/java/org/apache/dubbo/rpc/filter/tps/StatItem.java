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
package org.apache.dubbo.rpc.filter.tps;

import java.util.concurrent.atomic.AtomicInteger;

class StatItem {
    /**
     * 统计名 目前使用服务名
     */
    private String name;
    /**
     * 最后重置时间
     */
    private long lastResetTime;
    /**
     * 周期
     */
    private long interval;
    /**
     * 当前周期剩余种子数
     */
    private AtomicInteger token;
    /**
     * 限制大小
     */
    private int rate;

    StatItem(String name, int rate, long interval) {
        this.name = name;
        this.rate = rate;
        this.interval = interval;
        this.lastResetTime = System.currentTimeMillis();
        this.token = new AtomicInteger(rate);
    }

    public boolean isAllowable() {
        long now = System.currentTimeMillis();
        //若达到下一个周期，恢复可用种子数，设置最后重置时间。
        if (now > lastResetTime + interval) {
            token.set(rate);
            lastResetTime = now;
        }
        // CAS ，直到或得到一个种子，或者没有足够种子
        int value = token.get();
        boolean flag = false;
        while (value > 0 && !flag) {
            flag = token.compareAndSet(value, value - 1);
            value = token.get();
        }
        //是否成功
        return flag;
    }

    long getLastResetTime() {
        return lastResetTime;
    }

    int getToken() {
        return token.get();
    }

    @Override
    public String toString() {
        return new StringBuilder(32).append("StatItem ")
                .append("[name=").append(name).append(", ")
                .append("rate = ").append(rate).append(", ")
                .append("interval = ").append(interval).append("]")
                .toString();
    }

}
