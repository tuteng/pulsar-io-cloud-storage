/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.jcloud.sink;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.pulsar.io.jcloud.util.AvroRecordUtil.getPulsarSchema;
import com.google.common.collect.Lists;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.jcloud.BlobStoreAbstractConfig;
import org.apache.pulsar.io.jcloud.format.AvroFormat;
import org.apache.pulsar.io.jcloud.format.BytesFormat;
import org.apache.pulsar.io.jcloud.format.Format;
import org.apache.pulsar.io.jcloud.format.JsonFormat;
import org.apache.pulsar.io.jcloud.format.ParquetFormat;
import org.apache.pulsar.io.jcloud.partitioner.Partitioner;
import org.apache.pulsar.io.jcloud.partitioner.SimplePartitioner;
import org.apache.pulsar.io.jcloud.partitioner.TimePartitioner;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.ContainerNotFoundException;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.options.PutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Simple abstract class for BlobStore sink.
 * Users need to implement extractKeyValue function to use this sink
 */
@Slf4j
public abstract class BlobStoreAbstractSink<V extends BlobStoreAbstractConfig> implements Sink<GenericRecord> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobStoreAbstractSink.class);

    private V sinkConfig;

    protected BlobStoreContext context;
    protected BlobStore blobStore;

    protected Partitioner<GenericRecord> partitioner;

    protected Format<GenericRecord> format;

    private List<Record<GenericRecord>> incomingList;

    private ReadWriteLock rwlock = new ReentrantReadWriteLock();

    private ScheduledExecutorService flushExecutor;

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
        sinkConfig = loadConfig(config, sinkContext);
        sinkConfig.validate();
        context = buildBlobStoreContext(sinkConfig);
        blobStore = context.getBlobStore();
        boolean testCase = "transient".equalsIgnoreCase(sinkConfig.getProvider());
        if (!blobStore.containerExists(sinkConfig.getBucket()) && testCase) {
            //test use
            blobStore.createContainerInLocation(null, sinkConfig.getBucket());
        }
        checkArgument(blobStore.containerExists(sinkConfig.getBucket()), "%s bucket not exist", sinkConfig.getBucket());
        format = buildFormat(sinkConfig);
        partitioner = buildPartitioner(sinkConfig);

        long batchTimeMs = sinkConfig.getBatchTimeMs();
        incomingList = Lists.newArrayList();
        flushExecutor = Executors.newScheduledThreadPool(1);
        flushExecutor.scheduleAtFixedRate(this::flush, batchTimeMs, batchTimeMs, TimeUnit.MILLISECONDS);
    }

    private Partitioner<GenericRecord> buildPartitioner(V sinkConfig) {
        Partitioner<GenericRecord> partitioner;
        String partitionerType = StringUtils.defaultIfBlank(sinkConfig.getPartitionerType(), "partition");
        switch (partitionerType) {
            case "time":
                partitioner = new TimePartitioner<>();
                break;
            case "partition":
                partitioner = new SimplePartitioner<>();
                break;
            default:
                throw new RuntimeException("not support partitioner type " + partitionerType);
        }
        partitioner.configure(sinkConfig);
        return partitioner;
    }

    private Format<GenericRecord> buildFormat(V sinkConfig) {
        String formatType = StringUtils.defaultIfBlank(sinkConfig.getFormatType(), "json");
        switch (formatType) {
            case "avro":
                return new AvroFormat();
            case "parquet":
                return new ParquetFormat();
            case "json":
                return new JsonFormat();
            case "bytes":
                return new BytesFormat();
            default:
                throw new RuntimeException("not support formatType " + formatType);
        }
    }

    protected abstract V loadConfig(Map<String, Object> config, SinkContext sinkContext) throws IOException;

    protected abstract BlobStoreContext buildBlobStoreContext(V sinkConfig);

    @Override
    public void close() throws Exception {
        if (null != context) {
            context.close();
        }
    }

    @Override
    public void write(Record<GenericRecord> record) throws Exception {
        final Long sequenceId = record.getRecordSequence().get();
        LOGGER.info("write message[recordSequence={}]", sequenceId);
        int currentSize;
        rwlock.writeLock().lock();
        try {
            incomingList.add(record);
            currentSize = incomingList.size();
        } finally {
            rwlock.writeLock().unlock();
        }
        LOGGER.info("build blob success[recordSequence={}]", sequenceId);
        if (currentSize == sinkConfig.getBatchSize()) {
            flushExecutor.submit(this::flush);
        }
    }

    private void flush() {
        final List<Record<GenericRecord>> recordsToInsert;

        if (incomingList.isEmpty()) {
            log.info("no pending data...");
            return;
        }
        rwlock.writeLock().lock();
        try {
            if (incomingList.isEmpty()) {
                return;
            }
            recordsToInsert = incomingList;
            incomingList = Lists.newArrayList();
        } finally {
            rwlock.writeLock().unlock();
        }
        Record<GenericRecord> firstRecord = recordsToInsert.get(0);
        Schema<GenericRecord> schema = getPulsarSchema(firstRecord);
        format.initSchema(schema);

        final Iterator<Record<GenericRecord>> iter = recordsToInsert.iterator();
        try {
            String filepath = buildPartitionPath(firstRecord, partitioner, format);
            ByteSource payload = bindValue(iter, format);
            Blob blob = blobStore.blobBuilder(filepath)
                    .payload(payload)
                    .contentLength(payload.size())
                    .build();
            log.info("upload blob {}", filepath);
            blobStore.putBlob(sinkConfig.getBucket(), blob, PutOptions.NONE);
            iter.forEachRemaining(Record::ack);
            log.info("write success {}", filepath);
        } catch (ContainerNotFoundException e) {
            log.error("Bad message", e);
            iter.forEachRemaining(Record::fail);
            iter.remove();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public ByteSource bindValue(Iterator<Record<GenericRecord>> message,
                                Format<GenericRecord> format) throws Exception {
        return format.recordWriter(message);
    }

    public String buildPartitionPath(Record<GenericRecord> message,
                                     Partitioner<GenericRecord> partitioner,
                                     Format<?> format) throws Exception {
        String encodePartition = partitioner.encodePartition(message, System.currentTimeMillis());
        String partitionedPath = partitioner.generatePartitionedPath(message.getTopicName().get(), encodePartition);
        String path = partitionedPath + format.getExtension();
        LOGGER.info("generate message[recordSequence={}] savePath: {}", message.getRecordSequence().get(), path);
        return path;
    }

}
