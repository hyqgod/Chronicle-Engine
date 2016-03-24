/*
 *
 *  *     Copyright (C) 2016  higherfrequencytrading.com
 *  *
 *  *     This program is free software: you can redistribute it and/or modify
 *  *     it under the terms of the GNU Lesser General Public License as published by
 *  *     the Free Software Foundation, either version 3 of the License.
 *  *
 *  *     This program is distributed in the hope that it will be useful,
 *  *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *     GNU Lesser General Public License for more details.
 *  *
 *  *     You should have received a copy of the GNU Lesser General Public License
 *  *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.openhft.chronicle.engine.tree;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.util.ObjectUtils;
import net.openhft.chronicle.engine.api.pubsub.Publisher;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetNotFoundException;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.fs.Clusters;
import net.openhft.chronicle.engine.fs.EngineCluster;
import net.openhft.chronicle.engine.query.QueueSource;
import net.openhft.chronicle.engine.server.internal.QueueReplicationHandler;
import net.openhft.chronicle.network.cluster.ConnectionManager;
import net.openhft.chronicle.network.connection.CoreFields;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * @author Rob Austin.
 */
public class ChronicleQueueView<T, M> implements QueueView<T, M> {

    public static final String DEFAULT_BASE_PATH;
    private static final Logger LOG = LoggerFactory.getLogger(ChronicleQueueView.class);

    static {
        String dir = "/tmp";
        try {
            final Path tempDirectory = Files.createTempDirectory("engine-queue-");
            dir = tempDirectory.toAbsolutePath().toString();
        } catch (Exception ignore) {
        }

        DEFAULT_BASE_PATH = dir;
    }

    private final ChronicleQueue chronicleQueue;
    private final Class<T> messageTypeClass;
    @NotNull
    private final Class<M> elementTypeClass;
    private final ThreadLocal<ThreadLocalData> threadLocal;
    private AtomicLong uniqueCspid = new AtomicLong();
    private boolean isSource;
    private boolean isReplicating;

    public ChronicleQueueView(@NotNull RequestContext context, @NotNull Asset asset) {
        this(null, context, asset);
    }


    public ChronicleQueueView(@Nullable ChronicleQueue queue, @NotNull RequestContext context,
                              @NotNull Asset asset) {

        final HostIdentifier hostIdentifier = asset.findOrCreateView(HostIdentifier.class);
        final Byte hostId = hostIdentifier == null ? null : hostIdentifier.hostId();
        chronicleQueue = (queue == null) ? newInstance(context.name(), context
                .basePath(), hostId) : queue;
        messageTypeClass = context.messageType();
        elementTypeClass = context.elementType();
        LOG.info("context=" + context.name() + ", chronicleQueue=" + chronicleQueue);
        threadLocal = ThreadLocal.withInitial(() -> new ThreadLocalData(chronicleQueue));

        if (hostId != null)
            replication(context, asset);
    }

    public ChronicleQueue chronicleQueue() {
        return chronicleQueue;
    }

    public void replication(RequestContext context, Asset asset) {

        final QueueSource queueSource;
        final HostIdentifier hostIdentifier;

        try {
            hostIdentifier = asset.findOrCreateView(HostIdentifier.class);
            queueSource = asset.findView(QueueSource.class);
        } catch (AssetNotFoundException anfe) {
            if (LOG.isDebugEnabled())
                LOG.debug("replication not enabled " + anfe.getMessage());
            return;
        }

        final int remoteSourceIdentifier = queueSource.sourceHostId(context.fullName());

        if (hostIdentifier.hostId() == remoteSourceIdentifier) {
            isSource = true;
            return;
        }

        isReplicating = true;


        final Clusters clusters = asset.findView(Clusters.class);
        final EngineCluster engineCluster = clusters.get(context.cluster());


        final ConnectionManager connectionEventHandler = engineCluster.findConnectionEventHandler
                (remoteSourceIdentifier);

        long lastIndexReceived = -1;
        try {
            lastIndexReceived = threadLocalAppender().lastIndexAppended();
        } catch (IllegalStateException ignore) {

        }

        final QueueReplicationHandler h = new QueueReplicationHandler(lastIndexReceived, true);
        final String csp = context.fullName();

        connectionEventHandler.addListener((nc, isConnected) -> {
            if (!isConnected)
                return;

            long cid = QueueReplicationHandler.class.hashCode();
            nc.wireOutPublisher().publish(w -> w.writeDocument(true, d ->
                    d.writeEventName(CoreFields.csp).text(csp)
                            .writeEventName(CoreFields.cid).int64(cid)
                            .writeEventName(CoreFields.handler).typedMarshallable(h)));

        });
    }

    @NotNull
    public static String resourcesDir() {
        String path = ChronicleQueueView.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (path == null)
            return ".";
        return new File(path).getParentFile().getParentFile() + "/src/test/resources";
    }


    @Override
    public void registerTopicSubscriber
            (@NotNull TopicSubscriber<T, M> topicSubscriber) throws AssetNotFoundException {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void unregisterTopicSubscriber(@NotNull TopicSubscriber<T, M> topicSubscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public Publisher<M> publisher(@NotNull T topic) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void registerSubscriber(@NotNull T topic, @NotNull Subscriber<M> subscriber) {
        throw new UnsupportedOperationException("todo");
    }

    private ChronicleQueue newInstance(String name, @Nullable String basePath, @Nullable Byte hostID) {
        ChronicleQueue chronicleQueue;

        if (basePath == null)
            basePath = DEFAULT_BASE_PATH + "/" + hostID;

        File baseFilePath;
        try {
            baseFilePath = new File(basePath, name);
            baseFilePath.mkdirs();
            chronicleQueue = new SingleChronicleQueueBuilder(baseFilePath).build();
        } catch (Exception e) {
            throw Jvm.rethrow(e);
        }
        return chronicleQueue;
    }

    private ExcerptTailer threadLocalTailer() {
        return threadLocal.get().tailer;
    }

    private ExcerptAppender threadLocalAppender() {
        return threadLocal.get().appender;
    }

    @Override
    public Excerpt<T, M> next() {
        final ThreadLocalData threadLocalData = threadLocal.get();
        ExcerptTailer excerptTailer = threadLocalData.replayTailer;

        try (DocumentContext dc = excerptTailer.readingDocument()) {
            if (!dc.isPresent())
                return null;
            final StringBuilder topic = Wires.acquireStringBuilder();
            final ValueIn eventName = dc.wire().readEventName(topic);
            final M message = eventName.object(elementTypeClass);
            return threadLocalData.excerpt
                    .message(message)
                    .topic(ObjectUtils.convertTo(messageTypeClass, topic))
                    .index(excerptTailer.index());
        }

    }


    /**
     * @param index gets the except at the given index or {@code null} if the index is not valid
     * @return the except
     */
    @Nullable
    @Override
    public Excerpt<T, M> get(long index) {
        final ThreadLocalData threadLocalData = threadLocal.get();
        ExcerptTailer excerptTailer = threadLocalData.replayTailer;

        try {
            excerptTailer.moveToIndex(index);
        } catch (TimeoutException e) {
            return null;
        }
        try (DocumentContext dc = excerptTailer.readingDocument()) {
            if (!dc.isPresent())
                return null;
            final StringBuilder topic = Wires.acquireStringBuilder();
            final M message = dc.wire().readEventName(topic).object(elementTypeClass);

            return threadLocalData.excerpt
                    .message(message)
                    .topic(ObjectUtils.convertTo(messageTypeClass, topic))
                    .index(excerptTailer.index());
        }
    }


    @Override
    public Excerpt<T, M> get(T topic) {

        final ThreadLocalData threadLocalData = threadLocal.get();
        ExcerptTailer excerptTailer = threadLocalData.replayTailer;
        for (; ; ) {

            try (DocumentContext dc = excerptTailer.readingDocument()) {
                if (!dc.isPresent())
                    return null;
                final StringBuilder t = Wires.acquireStringBuilder();
                final ValueIn eventName = dc.wire().readEventName(t);

                final T topic1 = ObjectUtils.convertTo(messageTypeClass, topic);

                if (!topic.equals(topic1))
                    continue;

                final M message = eventName.object(elementTypeClass);
                return threadLocalData.excerpt
                        .message(message)
                        .topic(topic1)
                        .index(excerptTailer.index());
            }
        }
    }

    @Override
    public void publish(@NotNull T topic, @NotNull M message) {
        publishAndIndex(topic, message);
    }


    /**
     * @param consumer a consumer that provides that name of the event and value contained within
     *                 the except
     */
    public void get(@NotNull BiConsumer<CharSequence, M> consumer) {
        try {
            final ExcerptTailer tailer = threadLocalTailer();

            tailer.readDocument(w -> {
                final StringBuilder eventName = Wires.acquireStringBuilder();
                final ValueIn valueIn = w.readEventName(eventName);
                consumer.accept(eventName, valueIn.object(elementTypeClass));

            });
        } catch (Exception e) {
            e.printStackTrace();
            throw Jvm.rethrow(e);
        }
    }


    public long publishAndIndex(@NotNull T topic, @NotNull M message) {

        if (isReplicating && !isSource)
            throw new IllegalStateException("You can not publish to a sink used in replication, " +
                    "you have to publish to the source");

        final WireKey wireKey = topic instanceof WireKey ? (WireKey) topic : topic::toString;
        final ExcerptAppender excerptAppender = threadLocalAppender();

        try (final DocumentContext dc = excerptAppender.writingDocument()) {
            dc.wire().writeEventName(wireKey).object(message);
        }
        return excerptAppender.lastIndexAppended();
    }

    public long set(@NotNull M event) {
        if (isReplicating && !isSource)
            throw new IllegalStateException("You can not publish to a sink used in replication, " +
                    "you have to publish to the source");
        final ExcerptAppender excerptAppender = threadLocalAppender();
        excerptAppender.writeDocument(w -> w.writeEventName(() -> "").object(event));
        return excerptAppender.lastIndexAppended();
    }

    public void clear() {
        chronicleQueue.clear();
    }

    @NotNull
    public File path() {
        throw new UnsupportedOperationException("todo");
    }

    public long firstIndex() {
        return chronicleQueue.firstIndex();
    }

    public long lastIndex() {
        return chronicleQueue.lastIndex();
    }

    @NotNull
    public WireType wireType() {
        throw new UnsupportedOperationException("todo");
    }


    public void close() throws IOException {
        chronicleQueue.close();
    }

    public <M> void registerSubscriber(Subscriber<M> subscriber) {

    }

    public void unregisterSubscriber(Subscriber subscriber) {

    }

    public int subscriberCount() {
        throw new UnsupportedOperationException("todo");
    }

    public String dump() {
        return chronicleQueue.dump();
    }

    public static class LocalExcept<T, M> implements Excerpt<T, M>, Marshallable {

        private T topic;
        private M message;
        private long index;

        @Override
        public T topic() {
            return topic;
        }

        @Override
        public M message() {
            return message;
        }

        @Override
        public long index() {
            return this.index;
        }

        public LocalExcept<T, M> index(long index) {
            this.index = index;
            return this;
        }

        LocalExcept message(M message) {
            this.message = message;
            return this;
        }

        LocalExcept topic(T topic) {
            this.topic = topic;
            return this;
        }

        @Override
        public String toString() {
            return "Except{" + "topic=" + topic + ", message=" + message + '}';
        }

        @Override
        public void writeMarshallable(@NotNull WireOut wireOut) {
            wireOut.write(() -> "topic").object(topic);
            wireOut.write(() -> "message").object(message);
            wireOut.write(() -> "index").int64(index);
        }

        @Override
        public void readMarshallable(@NotNull WireIn wireIn) throws IORuntimeException {
            topic((T) wireIn.read(() -> "topic").object(Object.class));
            message((M) wireIn.read(() -> "message").object(Object.class));
            index(wireIn.read(() -> "index").int64());
        }
    }

    class ThreadLocalData {

        final ExcerptAppender appender;
        final ExcerptTailer tailer;
        final ExcerptTailer replayTailer;
        final LocalExcept excerpt;

        public ThreadLocalData(ChronicleQueue chronicleQueue) {
            appender = chronicleQueue.createAppender();
            tailer = chronicleQueue.createTailer();
            replayTailer = chronicleQueue.createTailer();
            excerpt = new LocalExcept();
        }
    }


}

