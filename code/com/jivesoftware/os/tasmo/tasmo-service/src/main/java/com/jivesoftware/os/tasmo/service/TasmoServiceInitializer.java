package com.jivesoftware.os.tasmo.service;

import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.jivesoftware.os.jive.utils.base.interfaces.CallbackStream;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.DefaultRowColumnValueStoreMarshaller;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.NeverAcceptsFailureSetOfSortedMaps;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.RowColumnValueStore;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.SetOfSortedMapsImplInitializer;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.timestamper.CurrentTimestamper;
import com.jivesoftware.os.jive.utils.row.column.value.store.marshall.api.TypeMarshaller;
import com.jivesoftware.os.jive.utils.row.column.value.store.marshall.primatives.ByteArrayTypeMarshaller;
import com.jivesoftware.os.jive.utils.row.column.value.store.marshall.primatives.LongTypeMarshaller;
import com.jivesoftware.os.jive.utils.row.column.value.store.marshall.primatives.StringTypeMarshaller;
import com.jivesoftware.os.tasmo.id.ObjectId;
import com.jivesoftware.os.tasmo.id.ObjectIdMarshaller;
import com.jivesoftware.os.tasmo.id.TenantId;
import com.jivesoftware.os.tasmo.id.TenantIdAndCentricId;
import com.jivesoftware.os.tasmo.id.TenantIdAndCentricIdMarshaller;
import com.jivesoftware.os.tasmo.id.TenantIdMarshaller;
import com.jivesoftware.os.tasmo.lib.TasmoViewMaterializer;
import com.jivesoftware.os.tasmo.lib.TasmoViewModel;
import com.jivesoftware.os.tasmo.lib.concur.ConcurrencyAndExistanceCommitChange;
import com.jivesoftware.os.tasmo.lib.events.EventValueCacheProvider;
import com.jivesoftware.os.tasmo.lib.events.EventValueStore;
import com.jivesoftware.os.tasmo.lib.process.WrittenEventProcessor;
import com.jivesoftware.os.tasmo.lib.process.WrittenEventProcessorDecorator;
import com.jivesoftware.os.tasmo.lib.process.WrittenInstanceHelper;
import com.jivesoftware.os.tasmo.lib.process.bookkeeping.BookkeepingEvent;
import com.jivesoftware.os.tasmo.lib.process.bookkeeping.EventBookKeeper;
import com.jivesoftware.os.tasmo.lib.process.bookkeeping.TasmoEventBookkeeper;
import com.jivesoftware.os.tasmo.lib.process.notification.ViewChangeNotificationProcessor;
import com.jivesoftware.os.tasmo.lib.write.CommitChange;
import com.jivesoftware.os.tasmo.model.ViewsProvider;
import com.jivesoftware.os.tasmo.model.process.OpaqueFieldValue;
import com.jivesoftware.os.tasmo.model.process.WrittenEvent;
import com.jivesoftware.os.tasmo.model.process.WrittenEventProvider;
import com.jivesoftware.os.tasmo.reference.lib.ClassAndField_IdKey;
import com.jivesoftware.os.tasmo.reference.lib.ClassAndField_IdKeyMarshaller;
import com.jivesoftware.os.tasmo.reference.lib.ReferenceStore;
import com.jivesoftware.os.tasmo.reference.lib.concur.ConcurrencyStore;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.merlin.config.Config;
import org.merlin.config.defaults.IntDefault;
import org.merlin.config.defaults.StringDefault;

/**
 *
 *
 */
public class TasmoServiceInitializer {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    static public interface TasmoServiceConfig extends Config {

        @StringDefault("dev")
        public String getTableNameSpace();

        @IntDefault(-1)
        public Integer getSessionIdCreatorId();

        @StringDefault("master")
        public String getModelMasterTenantId();

        @IntDefault(10)
        public Integer getPollForModelChangesEveryNSeconds();
    }

    public static CallbackStream<List<WrittenEvent>> initializeEventIngressCallbackStream(
            OrderIdProvider threadTimestamp,
            ViewsProvider viewsProvider,
            WrittenEventProvider eventProvider,
            SetOfSortedMapsImplInitializer<Exception> setOfSortedMapsImplInitializer,
            EventValueCacheProvider eventValueCacheProvider,
            CommitChange changeWriter,
            ViewChangeNotificationProcessor viewChangeNotificationProcessor,
            CallbackStream<List<BookkeepingEvent>> bookKeepingStream,
            final Optional<WrittenEventProcessorDecorator> writtenEventProcessorDecorator,
            TasmoServiceConfig config) throws IOException {

        RowColumnValueStore<TenantId, ObjectId, String, String, RuntimeException> existenceTable
                = new NeverAcceptsFailureSetOfSortedMaps<>(setOfSortedMapsImplInitializer.initialize(config.getTableNameSpace(), "existenceTable", "v",
                                new DefaultRowColumnValueStoreMarshaller<>(new TenantIdMarshaller(),
                                        new ObjectIdMarshaller(), new StringTypeMarshaller(),
                                        new StringTypeMarshaller()), new CurrentTimestamper()));

        RowColumnValueStore<TenantIdAndCentricId, ObjectId, String, OpaqueFieldValue, RuntimeException> eventStore
                = new NeverAcceptsFailureSetOfSortedMaps<>(setOfSortedMapsImplInitializer.initialize(config.getTableNameSpace(), "eventValueTable", "v",
                                new DefaultRowColumnValueStoreMarshaller<>(new TenantIdAndCentricIdMarshaller(),
                                        new ObjectIdMarshaller(), new StringTypeMarshaller(),
                                        (TypeMarshaller<OpaqueFieldValue>) eventProvider.getLiteralFieldValueMarshaller()), new CurrentTimestamper()));

        RowColumnValueStore<TenantIdAndCentricId, ObjectId, String, Long, RuntimeException> concurrencyTable
                = new NeverAcceptsFailureSetOfSortedMaps<>(setOfSortedMapsImplInitializer.initialize(config.getTableNameSpace(), "concurrencyTable", "v",
                                new DefaultRowColumnValueStoreMarshaller<>(new TenantIdAndCentricIdMarshaller(),
                                        new ObjectIdMarshaller(), new StringTypeMarshaller(),
                                        new LongTypeMarshaller()), new CurrentTimestamper()));

        ConcurrencyStore concurrencyStore = new ConcurrencyStore(concurrencyTable);

        EventValueStore eventValueStore = new EventValueStore(concurrencyStore, eventStore, eventValueCacheProvider);
        RowColumnValueStore<TenantIdAndCentricId, ClassAndField_IdKey, ObjectId, byte[], RuntimeException> multiLinks
                = new NeverAcceptsFailureSetOfSortedMaps<>(setOfSortedMapsImplInitializer.initialize(config.getTableNameSpace(), "multiLinkTable", "v",
                                new DefaultRowColumnValueStoreMarshaller<>(new TenantIdAndCentricIdMarshaller(), new ClassAndField_IdKeyMarshaller(),
                                        new ObjectIdMarshaller(), new ByteArrayTypeMarshaller()), new CurrentTimestamper()));

        RowColumnValueStore<TenantIdAndCentricId, ClassAndField_IdKey, ObjectId, byte[], RuntimeException> multiBackLinks
                = new NeverAcceptsFailureSetOfSortedMaps<>(setOfSortedMapsImplInitializer.initialize(config.getTableNameSpace(), "multiBackLinkTable", "v",
                                new DefaultRowColumnValueStoreMarshaller<>(new TenantIdAndCentricIdMarshaller(), new ClassAndField_IdKeyMarshaller(),
                                        new ObjectIdMarshaller(), new ByteArrayTypeMarshaller()), new CurrentTimestamper()));

        ReferenceStore referenceStore = new ReferenceStore(concurrencyStore, multiLinks, multiBackLinks);

        TasmoEventBookkeeper bookkeeper
                = new TasmoEventBookkeeper(bookKeepingStream);
        TenantId masterTenantId = new TenantId(config.getModelMasterTenantId());
        ConcurrencyAndExistanceCommitChange existenceCommitChange = new ConcurrencyAndExistanceCommitChange(concurrencyStore, changeWriter);

        final TasmoViewModel tasmoViewModel = new TasmoViewModel(
                masterTenantId,
                viewsProvider,
                eventProvider,
                concurrencyStore,
                referenceStore,
                eventValueStore,
                existenceCommitChange);

        tasmoViewModel.loadModel(masterTenantId);

        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    tasmoViewModel.reloadModels();
                } catch (Exception x) {
                    LOG.error("Scheduled reloadig of tasmo view model failed. ", x);
                }
            }
        }, config.getPollForModelChangesEveryNSeconds(), config.getPollForModelChangesEveryNSeconds(), TimeUnit.SECONDS);

        WrittenEventProcessorDecorator bookKeepingEventProcessor = new WrittenEventProcessorDecorator() {

            @Override
            public WrittenEventProcessor decorateWrittenEventProcessor(WrittenEventProcessor writtenEventProcessor) {
                EventBookKeeper eventBookKeeper = new EventBookKeeper(writtenEventProcessor);
                if (writtenEventProcessorDecorator.isPresent()) {
                    return writtenEventProcessorDecorator.get().decorateWrittenEventProcessor(eventBookKeeper);
                } else {
                    return eventBookKeeper;
                }

            }
        };

        TasmoViewMaterializer materializer = new TasmoViewMaterializer(bookkeeper,
                bookKeepingEventProcessor,
                tasmoViewModel,
                viewChangeNotificationProcessor,
                new WrittenInstanceHelper(),
                concurrencyStore,
                eventValueStore,
                referenceStore,
                threadTimestamp);


        EventIngressCallbackStream eventIngressCallbackStream = new EventIngressCallbackStream(materializer);
          Retryer<Boolean> retryer = RetryerBuilder.<Boolean>newBuilder()
                .withWaitStrategy(WaitStrategies.fixedWait(1, TimeUnit.SECONDS))
                .withStopStrategy(StopStrategies.stopAfterDelay(TimeUnit.SECONDS.toSeconds(30))) // TODO expose to config
                .retryIfException(new Predicate<Throwable>() {
                    @Override
                    public boolean apply(Throwable t) {
                        LOG.error("Ingress failed due to: " + t);
                        LOG.debug("", t);
                        LOG.inc("ingress>errors");
                        if (t instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            return false;
                        } else {
                            return Exception.class.isAssignableFrom(t.getClass()) | RuntimeException.class.isAssignableFrom(t.getClass());
                        }
                    }
                })
                .build();

        return new EventIngressRetryingCallbackStream(eventIngressCallbackStream, retryer);
    }
}
