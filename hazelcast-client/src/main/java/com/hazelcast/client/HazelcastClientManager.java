/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client;

import java.util.HashSet;
import java.util.Collection;
import java.util.Collections;

import com.hazelcast.util.EmptyStatement;

import java.util.concurrent.ConcurrentMap;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.OutOfMemoryHandler;

import java.util.concurrent.ConcurrentHashMap;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.impl.HazelcastClientProxy;
import com.hazelcast.instance.OutOfMemoryErrorDispatcher;
import com.hazelcast.core.DuplicateInstanceNameException;
import com.hazelcast.client.config.XmlClientConfigBuilder;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.ClientConnectionManagerFactory;
import com.hazelcast.client.impl.DefaultClientConnectionManagerFactory;

/***
 * This is central manager for all Hazelcast clients in JVM;
 * All creation functionality will be stored here and particular;
 * instance of Client will delegate here;
 */
public final class HazelcastClientManager {
    /***
     * Instance for clientManagers
     */
    public static final HazelcastClientManager INSTANCE = new HazelcastClientManager();

    static {
        OutOfMemoryErrorDispatcher.setClientHandler(new ClientOutOfMemoryHandler());
    }

    private final ConcurrentMap<String, HazelcastClientProxy> clients
            = new ConcurrentHashMap<String, HazelcastClientProxy>(5);

    private HazelcastClientManager() {
    }

    public static HazelcastInstance newHazelcastClient(HazelcastClientFactory hazelcastClientFactory) {
        return newHazelcastClient(new XmlClientConfigBuilder().build(), hazelcastClientFactory);
    }

    @SuppressWarnings("unchecked")
    public static HazelcastInstance newHazelcastClient(ClientConfig config, HazelcastClientFactory hazelcastClientFactory) {
        if (config == null) {
            config = new XmlClientConfigBuilder().build();
        }

        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        HazelcastClientProxy proxy;
        try {
            Thread.currentThread().setContextClassLoader(HazelcastClient.class.getClassLoader());
            ClientConnectionManagerFactory clientConnectionManagerFactory = new DefaultClientConnectionManagerFactory();
            final HazelcastClientInstanceImpl client = hazelcastClientFactory.createHazelcastInstanceClient(
                    config,
                    clientConnectionManagerFactory
            );
            client.start();
            OutOfMemoryErrorDispatcher.registerClient(client);
            proxy = hazelcastClientFactory.createProxy(client);

            if (INSTANCE.clients.putIfAbsent(client.getName(), proxy) != null) {
                throw new DuplicateInstanceNameException("HazelcastClientInstance with name '" + client.getName()
                        + "' already exists!");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(tccl);
        }
        return proxy;
    }

    public static HazelcastInstance getHazelcastClientByName(String instanceName) {
        return INSTANCE.clients.get(instanceName);
    }

    public static Collection<HazelcastInstance> getAllHazelcastClients() {
        Collection<HazelcastClientProxy> values = INSTANCE.clients.values();
        return Collections.unmodifiableCollection(new HashSet<HazelcastInstance>(values));
    }

    public static void shutdownAll() {
        for (HazelcastClientProxy proxy : INSTANCE.clients.values()) {
            HazelcastClientInstanceImpl client = proxy.client;
            if (client == null) {
                continue;
            }
            proxy.client = null;
            try {
                client.shutdown();
            } catch (Throwable ignored) {
                EmptyStatement.ignore(ignored);
            }
        }
        OutOfMemoryErrorDispatcher.clearClients();
        INSTANCE.clients.clear();
    }


    public static void shutdown(HazelcastInstance instance) {
        if (instance instanceof HazelcastClientProxy) {
            final HazelcastClientProxy proxy = (HazelcastClientProxy) instance;
            HazelcastClientInstanceImpl client = proxy.client;
            if (client == null) {
                return;
            }
            proxy.client = null;
            INSTANCE.clients.remove(client.getName());

            try {
                client.shutdown();
            } catch (Throwable ignored) {
                EmptyStatement.ignore(ignored);
            } finally {
                OutOfMemoryErrorDispatcher.deregisterClient(client);
            }
        }
    }

    public static void shutdown(String instanceName) {
        HazelcastClientProxy proxy = INSTANCE.clients.remove(instanceName);
        if (proxy == null) {
            return;
        }
        HazelcastClientInstanceImpl client = proxy.client;
        if (client == null) {
            return;
        }
        proxy.client = null;
        try {
            client.shutdown();
        } catch (Throwable ignored) {
            EmptyStatement.ignore(ignored);
        } finally {
            OutOfMemoryErrorDispatcher.deregisterClient(client);
        }
    }

    public static void setOutOfMemoryHandler(OutOfMemoryHandler outOfMemoryHandler) {
        OutOfMemoryErrorDispatcher.setClientHandler(outOfMemoryHandler);
    }
}
