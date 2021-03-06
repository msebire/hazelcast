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

package com.hazelcast.map.impl.eviction;

import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizeConfig;
import com.hazelcast.map.impl.MapContainer;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.PartitionContainer;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.nio.Address;
import com.hazelcast.partition.IPartition;
import com.hazelcast.partition.IPartitionService;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.util.MemoryInfoAccessor;
import com.hazelcast.util.RuntimeMemoryInfoAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.hazelcast.cluster.memberselector.MemberSelectors.DATA_MEMBER_SELECTOR;
import static com.hazelcast.config.EvictionPolicy.NONE;

/**
 * Checks whether a specific threshold is exceeded or not
 * according to configured {@link com.hazelcast.config.MaxSizeConfig.MaxSizePolicy}
 * to start eviction process.
 *
 * @see EvictorImpl#evictionChecker
 */
public class EvictionChecker {

    protected static final int ONE_HUNDRED_PERCENT = 100;
    protected static final int ONE_KILOBYTE = 1024;
    protected static final int ONE_MEGABYTE = ONE_KILOBYTE * ONE_KILOBYTE;

    protected final MapServiceContext mapServiceContext;

    // not final for testing purposes.
    protected MemoryInfoAccessor memoryInfoAccessor;

    public EvictionChecker(MapServiceContext mapServiceContext) {
        this.memoryInfoAccessor = new RuntimeMemoryInfoAccessor();
        this.mapServiceContext = mapServiceContext;
    }

    public static boolean isEvictionEnabled(RecordStore recordStore) {
        // this can be updated dynamically by UpdateMapConfigOperation.
        // that is why this check was put in a method instead of a class final field.
        MapContainer mapContainer = recordStore.getMapContainer();
        MapConfig mapConfig = mapContainer.getMapConfig();
        EvictionPolicy evictionPolicy = mapConfig.getEvictionPolicy();
        return !NONE.equals(evictionPolicy);
    }

    public boolean checkEvictable(RecordStore recordStore) {
        if (recordStore.size() == 0 || !isEvictionEnabled(recordStore)) {
            return false;
        }

        String mapName = recordStore.getName();

        MapContainer mapContainer = recordStore.getMapContainer();
        MaxSizeConfig maxSizeConfig = mapContainer.getMapConfig().getMaxSizeConfig();
        MaxSizeConfig.MaxSizePolicy maxSizePolicy = maxSizeConfig.getMaxSizePolicy();

        switch (maxSizePolicy) {
            case PER_NODE:
                return checkPerNodeEviction(recordStore);
            case PER_PARTITION:
                int partitionId = recordStore.getPartitionId();
                return checkPerPartitionEviction(mapName, maxSizeConfig, partitionId);
            case USED_HEAP_PERCENTAGE:
                return checkHeapPercentageEviction(mapName, maxSizeConfig);
            case USED_HEAP_SIZE:
                return checkHeapSizeEviction(mapName, maxSizeConfig);
            case FREE_HEAP_PERCENTAGE:
                return checkFreeHeapPercentageEviction(maxSizeConfig);
            case FREE_HEAP_SIZE:
                return checkFreeHeapSizeEviction(maxSizeConfig);
            default:
                throw new IllegalArgumentException("Not an appropriate max size policy [" + maxSizePolicy + ']');
        }
    }

    protected boolean checkPerNodeEviction(RecordStore recordStore) {
        double maxExpectedRecordStoreSize = calculatePerNodeMaxRecordStoreSize(recordStore);
        return recordStore.size() > maxExpectedRecordStoreSize;
    }

    /**
     * Calculates and returns the expected maximum size of an evicted record-store
     * when {@link com.hazelcast.config.MaxSizeConfig.MaxSizePolicy#PER_NODE PER_NODE} max-size-policy is used.
     */
    public double calculatePerNodeMaxRecordStoreSize(RecordStore recordStore) {
        MapConfig mapConfig = recordStore.getMapContainer().getMapConfig();
        MaxSizeConfig maxSizeConfig = mapConfig.getMaxSizeConfig();
        int configuredMaxSize = maxSizeConfig.getSize();
        NodeEngine nodeEngine = mapServiceContext.getNodeEngine();
        int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        int memberCount = nodeEngine.getClusterService().getSize(DATA_MEMBER_SELECTOR);

        return (1D * configuredMaxSize * memberCount / partitionCount);

    }

    protected boolean checkPerPartitionEviction(String mapName, MaxSizeConfig maxSizeConfig, int partitionId) {
        final double maxSize = maxSizeConfig.getSize();
        final PartitionContainer container = mapServiceContext.getPartitionContainer(partitionId);
        if (container == null) {
            return false;
        }
        final int size = getRecordStoreSize(mapName, container);
        return size > maxSize;
    }

    protected boolean checkHeapSizeEviction(String mapName, MaxSizeConfig maxSizeConfig) {
        final long usedHeapSize = getUsedHeapSize(mapName);
        if (usedHeapSize == -1L) {
            return false;
        }
        final double maxSize = maxSizeConfig.getSize();
        return maxSize < (1D * usedHeapSize / ONE_MEGABYTE);
    }

    protected boolean checkFreeHeapSizeEviction(MaxSizeConfig maxSizeConfig) {
        final long currentFreeHeapSize = getAvailableMemory();
        final double minFreeHeapSize = maxSizeConfig.getSize();
        return minFreeHeapSize > (1D * currentFreeHeapSize / ONE_MEGABYTE);
    }

    protected boolean checkHeapPercentageEviction(String mapName, MaxSizeConfig maxSizeConfig) {
        final long usedHeapSize = getUsedHeapSize(mapName);
        if (usedHeapSize == -1L) {
            return false;
        }
        final double maxSize = maxSizeConfig.getSize();
        final long total = getTotalMemory();
        return maxSize < (1D * ONE_HUNDRED_PERCENT * usedHeapSize / total);
    }

    protected boolean checkFreeHeapPercentageEviction(MaxSizeConfig maxSizeConfig) {
        final long currentFreeHeapSize = getAvailableMemory();
        final double freeHeapPercentage = maxSizeConfig.getSize();
        final long total = getTotalMemory();
        return freeHeapPercentage > (1D * ONE_HUNDRED_PERCENT * currentFreeHeapSize / total);
    }

    protected long getTotalMemory() {
        return memoryInfoAccessor.getTotalMemory();
    }

    protected long getFreeMemory() {
        return memoryInfoAccessor.getFreeMemory();
    }

    protected long getMaxMemory() {
        return memoryInfoAccessor.getMaxMemory();
    }

    protected long getAvailableMemory() {
        final long totalMemory = getTotalMemory();
        final long freeMemory = getFreeMemory();
        final long maxMemory = getMaxMemory();
        return freeMemory + (maxMemory - totalMemory);
    }

    protected long getUsedHeapSize(String mapName) {
        long heapCost = 0L;
        final List<Integer> partitionIds = findPartitionIds();
        for (int partitionId : partitionIds) {
            final PartitionContainer container = mapServiceContext.getPartitionContainer(partitionId);
            if (container == null) {
                continue;
            }
            heapCost += getRecordStoreHeapCost(mapName, container);
        }

        MapContainer mapContainer = mapServiceContext.getMapContainer(mapName);
        heapCost += mapContainer.getNearCacheSizeEstimator().getSize();
        return heapCost;
    }

    protected int getRecordStoreSize(String mapName, PartitionContainer partitionContainer) {
        final RecordStore existingRecordStore = partitionContainer.getExistingRecordStore(mapName);
        if (existingRecordStore == null) {
            return 0;
        }
        return existingRecordStore.size();
    }

    protected long getRecordStoreHeapCost(String mapName, PartitionContainer partitionContainer) {
        final RecordStore existingRecordStore = partitionContainer.getExistingRecordStore(mapName);
        if (existingRecordStore == null) {
            return 0L;
        }
        return existingRecordStore.getHeapCost();
    }

    protected List<Integer> findPartitionIds() {
        final NodeEngine nodeEngine = mapServiceContext.getNodeEngine();
        final IPartitionService partitionService = nodeEngine.getPartitionService();
        final int partitionCount = partitionService.getPartitionCount();
        List<Integer> partitionIds = null;
        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            if (isOwnerOrBackup(partitionId)) {
                if (partitionIds == null) {
                    partitionIds = new ArrayList<Integer>();
                }
                partitionIds.add(partitionId);
            }
        }
        return partitionIds == null ? Collections.<Integer>emptyList() : partitionIds;
    }

    protected boolean isOwnerOrBackup(int partitionId) {
        final NodeEngine nodeEngine = mapServiceContext.getNodeEngine();
        final IPartitionService partitionService = nodeEngine.getPartitionService();
        final IPartition partition = partitionService.getPartition(partitionId, false);
        final Address thisAddress = nodeEngine.getThisAddress();
        return partition.isOwnerOrBackup(thisAddress);
    }

    // only used when testing.
    public void setMemoryInfoAccessor(MemoryInfoAccessor memoryInfoAccessor) {
        this.memoryInfoAccessor = memoryInfoAccessor;
    }
}


