/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.managers.communication;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.plugin.extensions.communication.MessageFactoryProvider;
import org.apache.ignite.spi.communication.tcp.messages.NodeIdMessage;

import static org.apache.ignite.internal.util.IgniteUtils.toBytes;

/** */
public class IgniteIoCommunicationMessageSerializationTest extends AbstractCommunicationMessageSerializationTest {
    /** {@inheritDoc} */
    @Override protected MessageFactoryProvider messageFactory() {
        return new GridIoMessageFactory();
    }

    /** {@inheritDoc} */
    @Override protected Message initializeMessage(Message msg) throws Exception {
        if (msg instanceof NodeIdMessage) {
            int msgSize = U.field(NodeIdMessage.class, "MESSAGE_SIZE");

            FieldUtils.writeField(msg, "nodeIdBytes", new byte[msgSize], true);
        }

        return msg;
    }

    /** {@inheritDoc} */
    @Override protected AbstractTestMessageReader createMessageReader(int capacity) {
        return new TestIoMessageReader(capacity);
    }

    /** */
    private static class TestIoMessageReader extends AbstractTestMessageReader {
        /** */
        private static final byte[] BYTE_ARR = toBytes(null);

        /** */
        public TestIoMessageReader(int capacity) {
            super(capacity);
        }

        /** {@inheritDoc} */
        @Override public byte[] readByteArray() {
            super.readByteArray();

            return BYTE_ARR;
        }
    }
}
