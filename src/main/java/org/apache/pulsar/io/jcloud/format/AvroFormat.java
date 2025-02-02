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
package org.apache.pulsar.io.jcloud.format;

import com.google.common.io.ByteSource;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.jcloud.util.AvroRecordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * avro format.
 */
public class AvroFormat implements Format<GenericRecord> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AvroFormat.class);

    private final DataFileWriter<Object> writer = new DataFileWriter<>(new GenericDatumWriter<>());

    private Schema rootAvroSchema;

    @Override
    public String getExtension() {
        return ".avro";
    }

    @Override
    public void initSchema(org.apache.pulsar.client.api.Schema<GenericRecord> schema) {
        rootAvroSchema = AvroRecordUtil.convertToAvroSchema(schema);
    }

    @Override
    public ByteSource recordWriter(Iterator<Record<GenericRecord>> records) throws Exception {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writer.setCodec(CodecFactory.snappyCodec());
        try (DataFileWriter<Object> fileWriter = writer.create(rootAvroSchema, byteArrayOutputStream)) {
            while (records.hasNext()) {
                org.apache.avro.generic.GenericRecord writeRecord = AvroRecordUtil
                        .convertGenericRecord(records.next().getValue(), rootAvroSchema);
                fileWriter.append(writeRecord);
            }
            fileWriter.flush();
        }
        return ByteSource.wrap(byteArrayOutputStream.toByteArray());
    }
}
