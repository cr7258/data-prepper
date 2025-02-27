/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.source.opensearch.worker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.dataprepper.buffer.common.BufferAccumulator;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSet;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSetManager;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.model.source.coordinator.SourceCoordinator;
import org.opensearch.dataprepper.model.source.coordinator.SourcePartition;
import org.opensearch.dataprepper.plugins.source.opensearch.OpenSearchIndexProgressState;
import org.opensearch.dataprepper.plugins.source.opensearch.OpenSearchSourceConfiguration;
import org.opensearch.dataprepper.plugins.source.opensearch.configuration.SchedulingParameterConfiguration;
import org.opensearch.dataprepper.plugins.source.opensearch.configuration.SearchConfiguration;
import org.opensearch.dataprepper.plugins.source.opensearch.metrics.OpenSearchSourcePluginMetrics;
import org.opensearch.dataprepper.plugins.source.opensearch.worker.client.SearchAccessor;
import org.opensearch.dataprepper.plugins.source.opensearch.worker.client.exceptions.IndexNotFoundException;
import org.opensearch.dataprepper.plugins.source.opensearch.worker.client.model.NoSearchContextSearchRequest;
import org.opensearch.dataprepper.plugins.source.opensearch.worker.client.model.SearchWithSearchAfterResults;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.opensearch.dataprepper.plugins.source.opensearch.worker.WorkerCommonUtils.ACKNOWLEDGEMENT_SET_TIMEOUT_SECONDS;

@ExtendWith(MockitoExtension.class)
public class NoSearchContextWorkerTest {

    @Mock
    private OpenSearchSourceConfiguration openSearchSourceConfiguration;

    @Mock
    private OpenSearchIndexPartitionCreationSupplier openSearchIndexPartitionCreationSupplier;

    @Mock
    private SourceCoordinator<OpenSearchIndexProgressState> sourceCoordinator;

    @Mock
    private SearchAccessor searchAccessor;

    @Mock
    private BufferAccumulator<Record<Event>> bufferAccumulator;

    @Mock
    private AcknowledgementSetManager acknowledgementSetManager;

    @Mock(lenient = true)
    private OpenSearchSourcePluginMetrics openSearchSourcePluginMetrics;

    @Mock
    private Counter documentsProcessedCounter;

    @Mock
    private Counter indicesProcessedCounter;

    @Mock
    private Counter processingErrorsCounter;

    @Mock
    private Timer indexProcessingTimeTimer;

    private ExecutorService executorService;

    @BeforeEach
    void setup() {
        executorService = Executors.newSingleThreadExecutor();
        lenient().when(openSearchSourceConfiguration.isAcknowledgmentsEnabled()).thenReturn(false);
        when(openSearchSourcePluginMetrics.getDocumentsProcessedCounter()).thenReturn(documentsProcessedCounter);
        when(openSearchSourcePluginMetrics.getIndicesProcessedCounter()).thenReturn(indicesProcessedCounter);
        when(openSearchSourcePluginMetrics.getProcessingErrorsCounter()).thenReturn(processingErrorsCounter);
        when(openSearchSourcePluginMetrics.getIndexProcessingTimeTimer()).thenReturn(indexProcessingTimeTimer);
    }

    private NoSearchContextWorker createObjectUnderTest() {
        return new NoSearchContextWorker(searchAccessor, openSearchSourceConfiguration, sourceCoordinator, bufferAccumulator, openSearchIndexPartitionCreationSupplier, acknowledgementSetManager, openSearchSourcePluginMetrics);
    }

    @Test
    void run_with_getNextPartition_returning_empty_will_sleep_and_exit_when_interrupted() throws InterruptedException {
        when(sourceCoordinator.getNextPartition(openSearchIndexPartitionCreationSupplier)).thenReturn(Optional.empty());


        final Future<?> future = executorService.submit(() -> createObjectUnderTest().run());
        Thread.sleep(100);
        executorService.shutdown();
        future.cancel(true);
        assertThat(future.isCancelled(), equalTo(true));

        assertThat(executorService.awaitTermination(100, TimeUnit.MILLISECONDS), equalTo(true));
    }

    @Test
    void run_when_search_without_search_context_throws_index_not_found_exception_completes_the_partition() throws Exception {
        mockTimerCallable();

        final SourcePartition<OpenSearchIndexProgressState> sourcePartition = mock(SourcePartition.class);
        final String partitionKey = UUID.randomUUID().toString();
        when(sourcePartition.getPartitionKey()).thenReturn(partitionKey);
        when(sourcePartition.getPartitionState()).thenReturn(Optional.empty());

        final SearchConfiguration searchConfiguration = mock(SearchConfiguration.class);
        when(searchConfiguration.getBatchSize()).thenReturn(2);
        when(openSearchSourceConfiguration.getSearchConfiguration()).thenReturn(searchConfiguration);

        when(sourceCoordinator.getNextPartition(openSearchIndexPartitionCreationSupplier)).thenReturn(Optional.of(sourcePartition))
                .thenReturn(Optional.empty());

        when(searchAccessor.searchWithoutSearchContext(any(NoSearchContextSearchRequest.class))).thenThrow(IndexNotFoundException.class);


        final Future<?> future = executorService.submit(() -> createObjectUnderTest().run());
        Thread.sleep(100);
        executorService.shutdown();
        future.cancel(true);
        assertThat(future.isCancelled(), equalTo(true));

        assertThat(executorService.awaitTermination(100, TimeUnit.MILLISECONDS), equalTo(true));

        verify(sourceCoordinator).completePartition(partitionKey);
        verify(sourceCoordinator, never()).closePartition(anyString(), any(Duration.class), anyInt());

        verifyNoInteractions(documentsProcessedCounter);
        verifyNoInteractions(indicesProcessedCounter);
        verifyNoInteractions(processingErrorsCounter);
    }

    @Test
    void run_with_getNextPartition_with_non_empty_partition_processes_and_closes_that_partition() throws Exception {
        mockTimerCallable();

        final SourcePartition<OpenSearchIndexProgressState> sourcePartition = mock(SourcePartition.class);
        final String partitionKey = UUID.randomUUID().toString();
        when(sourcePartition.getPartitionKey()).thenReturn(partitionKey);
        when(sourcePartition.getPartitionState()).thenReturn(Optional.empty());

        final SearchConfiguration searchConfiguration = mock(SearchConfiguration.class);
        when(searchConfiguration.getBatchSize()).thenReturn(2);
        when(openSearchSourceConfiguration.getSearchConfiguration()).thenReturn(searchConfiguration);

        final SearchWithSearchAfterResults searchWithSearchAfterResults = mock(SearchWithSearchAfterResults.class);
        when(searchWithSearchAfterResults.getNextSearchAfter()).thenReturn(Collections.singletonList(UUID.randomUUID().toString()));
        when(searchWithSearchAfterResults.getDocuments()).thenReturn(List.of(mock(Event.class), mock(Event.class))).thenReturn(List.of(mock(Event.class), mock(Event.class)))
                .thenReturn(List.of(mock(Event.class))).thenReturn(List.of(mock(Event.class)));

        final ArgumentCaptor<NoSearchContextSearchRequest> searchRequestArgumentCaptor = ArgumentCaptor.forClass(NoSearchContextSearchRequest.class);
        when(searchAccessor.searchWithoutSearchContext(searchRequestArgumentCaptor.capture())).thenReturn(searchWithSearchAfterResults);

        doNothing().when(bufferAccumulator).add(any(Record.class));
        doNothing().when(bufferAccumulator).flush();

        when(sourceCoordinator.getNextPartition(openSearchIndexPartitionCreationSupplier)).thenReturn(Optional.of(sourcePartition)).thenReturn(Optional.empty());

        final SchedulingParameterConfiguration schedulingParameterConfiguration = mock(SchedulingParameterConfiguration.class);
        when(schedulingParameterConfiguration.getIndexReadCount()).thenReturn(1);
        when(schedulingParameterConfiguration.getInterval()).thenReturn(Duration.ZERO);
        when(openSearchSourceConfiguration.getSchedulingParameterConfiguration()).thenReturn(schedulingParameterConfiguration);

        doNothing().when(sourceCoordinator).closePartition(partitionKey,
                Duration.ZERO, 1);


        final Future<?> future = executorService.submit(() -> createObjectUnderTest().run());
        Thread.sleep(100);
        executorService.shutdown();
        future.cancel(true);
        assertThat(future.isCancelled(), equalTo(true));

        assertThat(executorService.awaitTermination(100, TimeUnit.MILLISECONDS), equalTo(true));

        verify(searchAccessor, times(2)).searchWithoutSearchContext(any(NoSearchContextSearchRequest.class));
        verify(sourceCoordinator, times(2)).saveProgressStateForPartition(eq(partitionKey), any(OpenSearchIndexProgressState.class));

        final List<NoSearchContextSearchRequest> noSearchContextSearchRequests = searchRequestArgumentCaptor.getAllValues();
        assertThat(noSearchContextSearchRequests.size(), equalTo(2));
        assertThat(noSearchContextSearchRequests.get(0), notNullValue());
        assertThat(noSearchContextSearchRequests.get(0).getIndex(), equalTo(partitionKey));
        assertThat(noSearchContextSearchRequests.get(0).getPaginationSize(), equalTo(2));
        assertThat(noSearchContextSearchRequests.get(0).getSearchAfter(), equalTo(null));

        assertThat(noSearchContextSearchRequests.get(1), notNullValue());
        assertThat(noSearchContextSearchRequests.get(1).getIndex(), equalTo(partitionKey));
        assertThat(noSearchContextSearchRequests.get(1).getPaginationSize(), equalTo(2));
        assertThat(noSearchContextSearchRequests.get(1).getSearchAfter(), equalTo(searchWithSearchAfterResults.getNextSearchAfter()));

        verify(documentsProcessedCounter, times(3)).increment();
        verify(indicesProcessedCounter).increment();
        verifyNoInteractions(processingErrorsCounter);
    }

    @Test
    void run_with_getNextPartition_with_acknowledgments_processes_and_closes_that_partition() throws Exception {
        mockTimerCallable();

        final AcknowledgementSet acknowledgementSet = mock(AcknowledgementSet.class);
        AtomicReference<Integer> numEventsAdded = new AtomicReference<>(0);
        doAnswer(a -> {
            numEventsAdded.getAndSet(numEventsAdded.get() + 1);
            return null;
        }).when(acknowledgementSet).add(any());

        doAnswer(invocation -> {
            Consumer<Boolean> consumer = invocation.getArgument(0);
            consumer.accept(true);
            return acknowledgementSet;
        }).when(acknowledgementSetManager).create(any(Consumer.class), eq(Duration.ofSeconds(ACKNOWLEDGEMENT_SET_TIMEOUT_SECONDS)));
        when(openSearchSourceConfiguration.isAcknowledgmentsEnabled()).thenReturn(true);

        final SourcePartition<OpenSearchIndexProgressState> sourcePartition = mock(SourcePartition.class);
        final String partitionKey = UUID.randomUUID().toString();
        when(sourcePartition.getPartitionKey()).thenReturn(partitionKey);
        when(sourcePartition.getPartitionState()).thenReturn(Optional.empty());

        final SearchConfiguration searchConfiguration = mock(SearchConfiguration.class);
        when(searchConfiguration.getBatchSize()).thenReturn(2);
        when(openSearchSourceConfiguration.getSearchConfiguration()).thenReturn(searchConfiguration);

        final SearchWithSearchAfterResults searchWithSearchAfterResults = mock(SearchWithSearchAfterResults.class);
        when(searchWithSearchAfterResults.getNextSearchAfter()).thenReturn(Collections.singletonList(UUID.randomUUID().toString()));
        when(searchWithSearchAfterResults.getDocuments()).thenReturn(List.of(mock(Event.class), mock(Event.class))).thenReturn(List.of(mock(Event.class), mock(Event.class)))
                .thenReturn(List.of(mock(Event.class))).thenReturn(List.of(mock(Event.class)));

        final ArgumentCaptor<NoSearchContextSearchRequest> searchRequestArgumentCaptor = ArgumentCaptor.forClass(NoSearchContextSearchRequest.class);
        when(searchAccessor.searchWithoutSearchContext(searchRequestArgumentCaptor.capture())).thenReturn(searchWithSearchAfterResults);

        doNothing().when(bufferAccumulator).add(any(Record.class));
        doNothing().when(bufferAccumulator).flush();

        when(sourceCoordinator.getNextPartition(openSearchIndexPartitionCreationSupplier)).thenReturn(Optional.of(sourcePartition)).thenReturn(Optional.empty());

        final SchedulingParameterConfiguration schedulingParameterConfiguration = mock(SchedulingParameterConfiguration.class);
        when(schedulingParameterConfiguration.getIndexReadCount()).thenReturn(1);
        when(schedulingParameterConfiguration.getInterval()).thenReturn(Duration.ZERO);
        when(openSearchSourceConfiguration.getSchedulingParameterConfiguration()).thenReturn(schedulingParameterConfiguration);

        doNothing().when(sourceCoordinator).closePartition(partitionKey,
                Duration.ZERO, 1);


        final Future<?> future = executorService.submit(() -> createObjectUnderTest().run());
        Thread.sleep(100);
        executorService.shutdown();
        future.cancel(true);
        assertThat(future.isCancelled(), equalTo(true));

        assertThat(executorService.awaitTermination(100, TimeUnit.MILLISECONDS), equalTo(true));

        verify(searchAccessor, times(2)).searchWithoutSearchContext(any(NoSearchContextSearchRequest.class));
        verify(sourceCoordinator, times(2)).saveProgressStateForPartition(eq(partitionKey), any(OpenSearchIndexProgressState.class));

        final List<NoSearchContextSearchRequest> noSearchContextSearchRequests = searchRequestArgumentCaptor.getAllValues();
        assertThat(noSearchContextSearchRequests.size(), equalTo(2));
        assertThat(noSearchContextSearchRequests.get(0), notNullValue());
        assertThat(noSearchContextSearchRequests.get(0).getIndex(), equalTo(partitionKey));
        assertThat(noSearchContextSearchRequests.get(0).getPaginationSize(), equalTo(2));
        assertThat(noSearchContextSearchRequests.get(0).getSearchAfter(), equalTo(null));

        assertThat(noSearchContextSearchRequests.get(1), notNullValue());
        assertThat(noSearchContextSearchRequests.get(1).getIndex(), equalTo(partitionKey));
        assertThat(noSearchContextSearchRequests.get(1).getPaginationSize(), equalTo(2));
        assertThat(noSearchContextSearchRequests.get(1).getSearchAfter(), equalTo(searchWithSearchAfterResults.getNextSearchAfter()));

        verify(acknowledgementSet).complete();

        verify(documentsProcessedCounter, times(3)).increment();
        verify(indicesProcessedCounter).increment();
        verifyNoInteractions(processingErrorsCounter);
    }

    private void mockTimerCallable() {
        doAnswer(a -> {
            a.<Runnable>getArgument(0).run();
            return null;
        }).when(indexProcessingTimeTimer).record(any(Runnable.class));
    }
}
