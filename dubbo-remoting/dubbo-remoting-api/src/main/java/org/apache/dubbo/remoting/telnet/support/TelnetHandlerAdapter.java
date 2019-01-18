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
package org.apache.dubbo.remoting.telnet.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.telnet.TelnetHandler;
import org.apache.dubbo.remoting.transport.ChannelHandlerAdapter;

/**
 * 负责接收来自HeaderExchangeHandler的telenet命令，分发给对应的TelnetHandler的实现类，进行处理，返回命令结果。
 */
public class TelnetHandlerAdapter extends ChannelHandlerAdapter implements TelnetHandler {

    private final ExtensionLoader<TelnetHandler> extensionLoader = ExtensionLoader.getExtensionLoader(TelnetHandler.class);

    @Override
    public String telnet(Channel channel, String message) throws RemotingException {
        //处理telnet提示键
        String prompt = channel.getUrl().getParameterAndDecoded(Constants.PROMPT_KEY, Constants.DEFAULT_PROMPT);
        boolean noprompt = message.contains("--no-prompt");
        message = message.replace("--no-prompt", "");
        //拆除telnet命令和参数
        StringBuilder buf = new StringBuilder();
        message = message.trim();
        String command;//命令
        if (message.length() > 0) {
            int i = message.indexOf(' ');
            if (i > 0) {
                command = message.substring(0, i).trim();//命令
                message = message.substring(i + 1).trim();//参数
            } else {
                command = message;//命令
                message = "";//参数
            }
        } else {
            command = "";//命令
        }
        if (command.length() > 0) {
            //查找到对应的TelnetHandler对象，执行命令
            if (extensionLoader.hasExtension(command)) {
                try {
                    String result = extensionLoader.getExtension(command).telnet(channel, message);
                    if (result == null) {
                        return null;
                    }
                    buf.append(result);
                } catch (Throwable t) {
                    buf.append(t.getMessage());
                }
            } else {
                //查找不到对应的TelnetHandler对象，返回报错
                buf.append("Unsupported command: ");
                buf.append(command);
            }
        }
        //添加telnet提示语
        if (buf.length() > 0) {
            buf.append("\r\n");
        }
        if (prompt != null && prompt.length() > 0 && !noprompt) {
            buf.append(prompt);
        }
        //返回
        return buf.toString();
    }

}
