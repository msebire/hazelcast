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

package com.hazelcast.nio.serialization;

/**
 * Data is basic unit of serialization. It stores binary form of an object serialized
 * by {@link com.hazelcast.internal.serialization.SerializationService#toData(Object)}.
 *
 */
public interface Data {

    /**
     * Returns byte array representation of internal binary format.
     *
     * @return binary data
     */
    byte[] toByteArray();

    /**
     * Returns serialization type of binary form. It's defined by
     * {@link Serializer#getTypeId()}
     *
     * @return serializer type id
     */
    int getType();

    /**
     * Returns the total size of Data in bytes
     * @return total size
     */
    int totalSize();

    /**
     * Returns size of internal binary data in bytes
     *
     * @return internal data size
     */
    int dataSize();

    /**
     * Returns approximate heap cost of this Data object in bytes.
     *
     * @return approximate heap cost
     */
    int getHeapCost();

    /**
     * Returns partition hash calculated for serialized object.
     * Partition hash is used to determine partition of a Data and is calculated using
     * {@link com.hazelcast.core.PartitioningStrategy} during serialization.
     * <p/>
     * If partition hash is not set then standard <tt>hashCode()</tt> is used.
     *
     * @return partition hash
     * @see com.hazelcast.core.PartitionAware
     * @see com.hazelcast.core.PartitioningStrategy
     * @see com.hazelcast.internal.serialization.SerializationService#toData(Object, com.hazelcast.core.PartitioningStrategy)
     */
    int getPartitionHash();

    /**
     * Returns true if Data has partition hash, false otherwise.
     * @return true if Data has partition hash, false otherwise.
     */
    boolean hasPartitionHash();

    /**
     * Returns 64-bit hash code for this Data object.
     * @return 64-bit hash code
     */
    long hash64();

    /**
     * Returns true if this Data is created from a {@link com.hazelcast.nio.serialization.Portable} object,
     * false otherwise.
     *
     * @return true if source object is <tt>Portable</tt>, false otherwise.
     */
    boolean isPortable();

}
