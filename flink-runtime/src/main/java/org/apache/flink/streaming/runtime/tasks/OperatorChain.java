/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import java.io.IOException;
import java.util.*;

import com.google.common.base.Joiner;
import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.metrics.Counter;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.deployment.InputChannelDeploymentDescriptor;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.io.network.api.CancelCheckpointMarker;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.PausingOperatorMarker;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.plugable.SerializationDelegate;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.streaming.api.collector.selector.CopyingDirectedOutput;
import org.apache.flink.streaming.api.collector.selector.DirectedOutput;
import org.apache.flink.streaming.api.collector.selector.OutputSelector;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.graph.StreamEdge;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.StreamFilter;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.RecordWriterOutput;
import org.apache.flink.streaming.runtime.io.StreamRecordWriter;
import org.apache.flink.streaming.runtime.modification.ModificationCoordinator;
import org.apache.flink.streaming.runtime.modification.ModificationMetaData;
import org.apache.flink.streaming.runtime.modification.events.CancelModificationMarker;
import org.apache.flink.streaming.runtime.modification.events.StartMigrationMarker;
import org.apache.flink.streaming.runtime.modification.events.StartModificationMarker;
import org.apache.flink.streaming.runtime.partitioner.ConfigurableStreamPartitioner;
import org.apache.flink.streaming.runtime.partitioner.ForwardPartitioner;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusProvider;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.XORShiftRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code OperatorChain} contains all operators that are executed as one chain within a single
 * {@link StreamTask}.
 *
 * @param <OUT> The type of elements accepted by the chain, i.e., the input type of the chain's
 *              head operator.
 */
@Internal
public class OperatorChain<OUT, OP extends StreamOperator<OUT>> implements StreamStatusMaintainer {

	private static final Logger LOG = LoggerFactory.getLogger(OperatorChain.class);

	private final StreamOperator<?>[] allOperators;

	private RecordWriterOutput<?>[] streamOutputs;

	private final Output<StreamRecord<OUT>> chainEntryPoint;

	private final OP headOperator;

	private final String name;

	/**
	 * Current status of the input stream of the operator chain.
	 * Watermarks explicitly generated by operators in the chain (i.e. timestamp
	 * assigner / watermark extractors), will be blocked and not forwarded if
	 * this value is {@link StreamStatus#IDLE}.
	 */
	private StreamStatus streamStatus = StreamStatus.ACTIVE;

	public OperatorChain(StreamTask<OUT, OP> containingTask) {

		name = containingTask.getName();

		final ClassLoader userCodeClassloader = containingTask.getUserCodeClassLoader();
		final StreamConfig configuration = containingTask.getConfiguration();

		headOperator = configuration.getStreamOperator(userCodeClassloader);

		// we read the chained configs, and the order of record writer registrations by output name
		Map<Integer, StreamConfig> chainedConfigs = configuration.getTransitiveChainedTaskConfigs(userCodeClassloader);
		chainedConfigs.put(configuration.getVertexID(), configuration);

//		LOG.info("ChainedConfig key for {}: {}", containingTask.getName(), Joiner.on(",").join(chainedConfigs.keySet()));
//		LOG.info("ChainedConfig values for {}: {}", containingTask.getName(), Joiner.on(",").join(chainedConfigs.values()));

		// create the final output stream writers
		// we iterate through all the out edges from this job vertex and create a stream output
		List<StreamEdge> outEdgesInOrder = configuration.getOutEdgesInOrder(userCodeClassloader);

//		LOG.info("OutputEdges for {}: {}", containingTask.getName(), Joiner.on("\t").join(outEdgesInOrder));
//		LOG.info("OutputInOrderEdges for {}: {}", containingTask.getName(), Joiner.on("\t").join(outEdgesInOrder));

		Map<StreamEdge, RecordWriterOutput<?>> streamOutputMap = new HashMap<>(outEdgesInOrder.size());
		this.streamOutputs = new RecordWriterOutput<?>[outEdgesInOrder.size()];

		// from here on, we need to make sure that the output writers are shut down again on failure
		boolean success = false;
		try {
			for (int i = 0; i < outEdgesInOrder.size(); i++) {
				StreamEdge outEdge = outEdgesInOrder.get(i);

				// TODO Masterthesis chainedConfig returns null as getVertexID and outEdge.getSourceId do not match
				StreamConfig tmpStreamConfig = chainedConfigs.get(outEdge.getSourceId());

//				LOG.info("Creating StreamOutput for {} for edge {} with config {}", name, outEdge, tmpStreamConfig);

				if (tmpStreamConfig == null) {
					LOG.info("Creating StreamOutput for {} for edge {} is null", name, outEdge);
					tmpStreamConfig = configuration;
				}

				RecordWriterOutput<?> streamOutput = createStreamOutput(
					outEdge, tmpStreamConfig, i,
					containingTask.getEnvironment(), containingTask.getName());

				this.streamOutputs[i] = streamOutput;
				streamOutputMap.put(outEdge, streamOutput);
			}

			LOG.info("OperatorChain for task {}", containingTask.getName());

			StreamEdge customEdge = null;
			StreamConfig streamConfig = null;

			if (outEdgesInOrder.size() > 0) {
				customEdge = outEdgesInOrder.get(0);
				streamConfig = chainedConfigs.get(customEdge.getSourceId());
			}

			List<StreamEdge> nonChainedOutputs = configuration.getNonChainedOutputs(userCodeClassloader);

			LOG.debug("Custom Edge: {}", customEdge);
//			LOG.debug("StreamConfig: {}", streamConfig);
//			LOG.debug("NonChainedOutputs: {}", Joiner.on(",").join(nonChainedOutputs));

			// we create the chain of operators and grab the collector that leads into the chain
			List<StreamOperator<?>> allOps = new ArrayList<>(chainedConfigs.size());
			this.chainEntryPoint = createOutputCollector(containingTask, configuration,
					chainedConfigs, userCodeClassloader, streamOutputMap, allOps);

//			LOG.info("OperatorChain with {} and {}", chainEntryPoint, headOperator);

			if (headOperator != null) {
				Output output = getChainEntryPoint();
				LOG.info(name + "Setting up HeadOperator with with {}", output);
				headOperator.setup(containingTask, configuration, output);
			}

			// add head operator to end of chain
			allOps.add(headOperator);

			this.allOperators = allOps.toArray(new StreamOperator<?>[allOps.size()]);

//			LOG.info("{} OperatorChain size {} ", name, allOperators.length);

			success = true;
		} finally {
			// make sure we clean up after ourselves in case of a failure after acquiring
			// the first resources
			if (!success) {
				for (RecordWriterOutput<?> output : this.streamOutputs) {
					if (output != null) {
						output.close();
						output.clearBuffers();
					}
				}
			}
		}
	}

	@Override
	public StreamStatus getStreamStatus() {
		return streamStatus;
	}

	@Override
	public void toggleStreamStatus(StreamStatus status) {
		if (!status.equals(this.streamStatus)) {
			this.streamStatus = status;

			// try and forward the stream status change to all outgoing connections
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {
				streamOutput.emitStreamStatus(status);
			}
		}
	}

	public void broadcastCheckpointBarrier(long id, long timestamp, CheckpointOptions checkpointOptions) throws IOException {
		try {
			CheckpointBarrier barrier = new CheckpointBarrier(id, timestamp, checkpointOptions);
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {
				streamOutput.broadcastEvent(barrier);
			}
		}
		catch (InterruptedException e) {
			throw new IOException("Interrupted while broadcasting checkpoint barrier");
		}
	}

	public void broadcastOperatorPausedEvent(List<InputChannelDeploymentDescriptor> newLocation) throws IOException {
		try {
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {

				if (streamOutput.getRecordWriter().getNumChannels() != newLocation.size()) {
					throw new IllegalStateException("Number of new icdd does not fit outgoing channels");
				}

				for (int i = 0; i < newLocation.size(); i++) {
					streamOutput.sendToTarget(new PausingOperatorMarker(newLocation.get(i)), i);
				}

				streamOutput.flush();
			}
		}
		catch (InterruptedException e) {
			throw new IOException("Interrupted while broadcasting checkpoint barrier");
		}
	}

	public void broadcastStartModificationEvent(ModificationMetaData metaData,
												Set<ExecutionAttemptID> executionAttemptIDS,
												Set<Integer> subTasksToPause,
												ModificationCoordinator.ModificationAction action) throws IOException {
		try {
			StartModificationMarker startModificationMarker =
				new StartModificationMarker(metaData.getModificationID(), metaData.getTimestamp(), executionAttemptIDS, subTasksToPause, action);
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {
				streamOutput.broadcastEvent(startModificationMarker);
			}
		}
		catch (InterruptedException e) {
			throw new IOException("Interrupted while broadcasting checkpoint barrier");
		}
	}

	public void broadcastCancelModificationEvent(ModificationMetaData metaData, Set<ExecutionAttemptID> vertexIDS) throws IOException {
		try {
			CancelModificationMarker cancelModificationMarker =
				new CancelModificationMarker(metaData.getModificationID(), metaData.getTimestamp(), vertexIDS);
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {
				streamOutput.broadcastEvent(cancelModificationMarker);
			}
		}
		catch (InterruptedException e) {
			throw new IOException("Interrupted while broadcasting checkpoint barrier");
		}
	}

	public void broadcastCheckpointCancelMarker(long id) throws IOException {
		try {
			CancelCheckpointMarker barrier = new CancelCheckpointMarker(id);
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {
				streamOutput.broadcastEvent(barrier);
			}
		}
		catch (InterruptedException e) {
			throw new IOException("Interrupted while broadcasting checkpoint cancellation");
		}
	}

	public RecordWriterOutput<?>[] getStreamOutputs() {
		return streamOutputs;
	}

	public StreamOperator<?>[] getAllOperators() {
		return allOperators;
	}

	public Output<StreamRecord<OUT>> getChainEntryPoint() {
		return chainEntryPoint;
	}

	/**
	 * This method should be called before finishing the record emission, to make sure any data
	 * that is still buffered will be sent. It also ensures that all data sending related
	 * exceptions are recognized.
	 *
	 * @throws IOException Thrown, if the buffered data cannot be pushed into the output streams.
	 */
	public void flushOutputs() throws IOException {
		for (RecordWriterOutput<?> streamOutput : getStreamOutputs()) {
			streamOutput.flush();
		}
	}

	/**
	 * This method releases all resources of the record writer output. It stops the output
	 * flushing thread (if there is one) and releases all buffers currently held by the output
	 * serializers.
	 *
	 * <p>This method should never fail.
	 */
	public void releaseOutputs() {
		try {
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {
				streamOutput.close();
			}
		}
		finally {
			// make sure that we release the buffers in any case
			for (RecordWriterOutput<?> output : streamOutputs) {
				output.clearBuffers();
			}
		}
	}

	public OP getHeadOperator() {
		return headOperator;
	}

	public int getChainLength() {
		return allOperators == null ? 0 : allOperators.length;
	}

	// ------------------------------------------------------------------------
	//  initialization utilities
	// ------------------------------------------------------------------------

	private <T> Output<StreamRecord<T>> createOutputCollector(
			StreamTask<?, ?> containingTask,
			StreamConfig operatorConfig,
			Map<Integer, StreamConfig> chainedConfigs,
			ClassLoader userCodeClassloader,
			Map<StreamEdge, RecordWriterOutput<?>> streamOutputs,
			List<StreamOperator<?>> allOperators) {
		List<Tuple2<Output<StreamRecord<T>>, StreamEdge>> allOutputs = new ArrayList<>(4);

		// create collectors for the network outputs
		for (StreamEdge outputEdge : operatorConfig.getNonChainedOutputs(userCodeClassloader)) {
			LOG.info("Connecting network {} for {}", outputEdge, name);

			@SuppressWarnings("unchecked")
			RecordWriterOutput<T> output = (RecordWriterOutput<T>) streamOutputs.get(outputEdge);

			allOutputs.add(new Tuple2<Output<StreamRecord<T>>, StreamEdge>(output, outputEdge));
		}

		// Create collectors for the chained outputs
		for (StreamEdge outputEdge : operatorConfig.getChainedOutputs(userCodeClassloader)) {
			LOG.info("Connecting chained {} for {}", outputEdge, name);

			int outputId = outputEdge.getTargetId();
			StreamConfig chainedOpConfig = chainedConfigs.get(outputId);

			Output<StreamRecord<T>> output = createChainedOperator(
					containingTask, chainedOpConfig, chainedConfigs, userCodeClassloader, streamOutputs, allOperators, outputEdge.getOutputTag());
			allOutputs.add(new Tuple2<>(output, outputEdge));
		}

		// if there are multiple outputs, or the outputs are directed, we need to
		// wrap them as one output

		List<OutputSelector<T>> selectors = operatorConfig.getOutputSelectors(userCodeClassloader);

		if (selectors == null || selectors.isEmpty()) {
			// simple path, no selector necessary

			LOG.info(name + " No Selector present");

			if (allOutputs.size() == 1) {

				LOG.info(name + " allOutputsSize {} with Output {} ", allOutputs.size(), allOutputs.get(0).f0 );

				return allOutputs.get(0).f0;
			}
			else {
				// send to N outputs. Note that this includes teh special case
				// of sending to zero outputs

				LOG.info(name + " allOutputsSize: " + allOutputs.size());

				@SuppressWarnings({"unchecked", "rawtypes"})
				Output<StreamRecord<T>>[] asArray = new Output[allOutputs.size()];
				for (int i = 0; i < allOutputs.size(); i++) {
					asArray[i] = allOutputs.get(i).f0;
				}

				// This is the inverse of creating the normal ChainingOutput.
				// If the chaining output does not copy we need to copy in the broadcast output,
				// otherwise multi-chaining would not work correctly.
				if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
					return new CopyingBroadcastingOutputCollector<>(asArray, this);
				} else  {
					return new BroadcastingOutputCollector<>(asArray, this);
				}
			}
		}
		else {
			// selector present, more complex routing necessary

			// This is the inverse of creating the normal ChainingOutput.
			// If the chaining output does not copy we need to copy in the broadcast output,
			// otherwise multi-chaining would not work correctly.
			if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
				return new CopyingDirectedOutput<>(selectors, allOutputs);
			} else {
				return new DirectedOutput<>(selectors, allOutputs);
			}

		}
	}

	private <IN, OUT> Output<StreamRecord<IN>> createChainedOperator(
			StreamTask<?, ?> containingTask,
			StreamConfig operatorConfig,
			Map<Integer, StreamConfig> chainedConfigs,
			ClassLoader userCodeClassloader,
			Map<StreamEdge, RecordWriterOutput<?>> streamOutputs,
			List<StreamOperator<?>> allOperators,
			OutputTag<IN> outputTag) {
		// create the output that the operator writes to first. this may recursively create more operators
		Output<StreamRecord<OUT>> output = createOutputCollector(
				containingTask, operatorConfig, chainedConfigs, userCodeClassloader, streamOutputs, allOperators);

		// now create the operator and give it the output collector to write its output to
		OneInputStreamOperator<IN, OUT> chainedOperator = operatorConfig.getStreamOperator(userCodeClassloader);

		chainedOperator.setup(containingTask, operatorConfig, output);

		allOperators.add(chainedOperator);

		if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
			return new ChainingOutput<>(chainedOperator, this, outputTag);
		} else {
			TypeSerializer<IN> inSerializer = operatorConfig.getTypeSerializerIn1(userCodeClassloader);
			return new CopyingChainingOutput<>(chainedOperator, inSerializer, outputTag, this);
		}
	}

	private <T> RecordWriterOutput<T> createStreamOutput(
			StreamEdge edge, StreamConfig upStreamConfig, int outputIndex,
			Environment taskEnvironment,
			String taskName) {
		OutputTag sideOutputTag = edge.getOutputTag(); // OutputTag, return null if not sideOutput

		TypeSerializer outSerializer = null;

//		LOG.info("CreateStreamOutput for {}: {}", name, upStreamConfig);

		if (edge.getOutputTag() != null) {
			// side output
			outSerializer = upStreamConfig.getTypeSerializerSideOut(
					edge.getOutputTag(), taskEnvironment.getUserClassLoader());
		} else {
			// main output
			outSerializer = upStreamConfig.getTypeSerializerOut(taskEnvironment.getUserClassLoader());
		}

		@SuppressWarnings("unchecked")
		StreamPartitioner<T> outputPartitioner = (StreamPartitioner<T>) edge.getPartitioner();

		LOG.info("Using partitioner {} for output j{} of task with partitioner {} and class {}",
			outputPartitioner, outputIndex, taskName, outputPartitioner, outputPartitioner.getClass().getName());

		ResultPartitionWriter bufferWriter = taskEnvironment.getWriter(outputIndex);

		// we initialize the partitioner here with the number of key groups (aka max. parallelism)
		if (outputPartitioner instanceof ConfigurableStreamPartitioner) {
			int numKeyGroups = bufferWriter.getNumTargetKeyGroups();
			if (0 < numKeyGroups) {
				((ConfigurableStreamPartitioner) outputPartitioner).configure(numKeyGroups);
			}
		}

		StreamRecordWriter<SerializationDelegate<StreamRecord<T>>> output =
				new StreamRecordWriter<>(bufferWriter, outputPartitioner, upStreamConfig.getBufferTimeout(), name);
		output.setMetricGroup(taskEnvironment.getMetricGroup().getIOMetricGroup());

		return new RecordWriterOutput<>(output, outSerializer, sideOutputTag, this, name);
	}

	public void broadcastStartMigrationEvent(ModificationMetaData metaData,
											 Map<ExecutionAttemptID, Set<Integer>> spillingVertices,
											 Map<ExecutionAttemptID, List<InputChannelDeploymentDescriptor>> stoppingVertices,
											 long upcomingCheckpointID) throws Exception {

		try {
			StartMigrationMarker startMigrationMarker =
				new StartMigrationMarker(
					metaData.getModificationID(),
					metaData.getTimestamp(),
					spillingVertices,
					stoppingVertices,
					upcomingCheckpointID);
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {
				streamOutput.broadcastEvent(startMigrationMarker);
			}
		}
		catch (InterruptedException e) {
			throw new IOException("Interrupted while broadcasting checkpoint barrier");
		}
	}

	// ------------------------------------------------------------------------
	//  Collectors for output chaining
	// ------------------------------------------------------------------------

	private static class ChainingOutput<T> implements Output<StreamRecord<T>> {

		protected final OneInputStreamOperator<T, ?> operator;
		protected final Counter numRecordsIn;

		protected final StreamStatusProvider streamStatusProvider;

		protected final OutputTag<T> outputTag;

		public ChainingOutput(
				OneInputStreamOperator<T, ?> operator,
				StreamStatusProvider streamStatusProvider,
				OutputTag<T> outputTag) {
			this.operator = operator;
			this.numRecordsIn = ((OperatorMetricGroup) operator.getMetricGroup()).getIOMetricGroup().getNumRecordsInCounter();
			this.streamStatusProvider = streamStatusProvider;
			this.outputTag = outputTag;
		}

		@Override
		public void collect(StreamRecord<T> record) {
			if (this.outputTag != null) {
				// we are only responsible for emitting to the main input
				return;
			}

			pushToOperator(record);
		}

		@Override
		public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
			if (this.outputTag == null || !this.outputTag.equals(outputTag)) {
				// we are only responsible for emitting to the side-output specified by our
				// OutputTag.
				return;
			}

			pushToOperator(record);
		}

		protected <X> void pushToOperator(StreamRecord<X> record) {
			try {
				// we know that the given outputTag matches our OutputTag so the record
				// must be of the type that our operator expects.
				@SuppressWarnings("unchecked")
				StreamRecord<T> castRecord = (StreamRecord<T>) record;

				numRecordsIn.inc();
				operator.setKeyContextElement1(castRecord);
				operator.processElement(castRecord);
			}
			catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}
		}

		@Override
		public void emitWatermark(Watermark mark) {
			try {
				if (streamStatusProvider.getStreamStatus().isActive()) {
					operator.processWatermark(mark);
				}
			}
			catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}
		}

		@Override
		public void emitLatencyMarker(LatencyMarker latencyMarker) {
			try {
				operator.processLatencyMarker(latencyMarker);
			}
			catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}
		}

		@Override
		public void close() {
			try {
				operator.close();
			}
			catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}
		}
	}

	private static final class CopyingChainingOutput<T> extends ChainingOutput<T> {

		private final TypeSerializer<T> serializer;

		public CopyingChainingOutput(
				OneInputStreamOperator<T, ?> operator,
				TypeSerializer<T> serializer,
				OutputTag<T> outputTag,
				StreamStatusProvider streamStatusProvider) {
			super(operator, streamStatusProvider, outputTag);
			this.serializer = serializer;
		}

		@Override
		public void collect(StreamRecord<T> record) {
			if (this.outputTag != null) {
				// we are only responsible for emitting to the main input
				return;
			}

			pushToOperator(record);
		}

		@Override
		public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
			if (this.outputTag == null || !this.outputTag.equals(outputTag)) {
				// we are only responsible for emitting to the side-output specified by our
				// OutputTag.
				return;
			}

			pushToOperator(record);
		}

		@Override
		protected <X> void pushToOperator(StreamRecord<X> record) {
			try {
				// we know that the given outputTag matches our OutputTag so the record
				// must be of the type that our operator (and Serializer) expects.
				@SuppressWarnings("unchecked")
				StreamRecord<T> castRecord = (StreamRecord<T>) record;

				numRecordsIn.inc();
				StreamRecord<T> copy = castRecord.copy(serializer.copy(castRecord.getValue()));
				operator.setKeyContextElement1(copy);
				operator.processElement(copy);
			} catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}

		}
	}

	private static class BroadcastingOutputCollector<T> implements Output<StreamRecord<T>> {

		protected final Output<StreamRecord<T>>[] outputs;

		private final Random random = new XORShiftRandom();

		private final StreamStatusProvider streamStatusProvider;

		public BroadcastingOutputCollector(
				Output<StreamRecord<T>>[] outputs,
				StreamStatusProvider streamStatusProvider) {
			this.outputs = outputs;
			this.streamStatusProvider = streamStatusProvider;
		}

		@Override
		public void emitWatermark(Watermark mark) {
			if (streamStatusProvider.getStreamStatus().isActive()) {
				for (Output<StreamRecord<T>> output : outputs) {
					output.emitWatermark(mark);
				}
			}
		}

		@Override
		public void emitLatencyMarker(LatencyMarker latencyMarker) {
			if (outputs.length <= 0) {
				// ignore
			} else if (outputs.length == 1) {
				outputs[0].emitLatencyMarker(latencyMarker);
			} else {
				// randomly select an output
				outputs[random.nextInt(outputs.length)].emitLatencyMarker(latencyMarker);
			}
		}

		@Override
		public void collect(StreamRecord<T> record) {
			for (Output<StreamRecord<T>> output : outputs) {
				output.collect(record);
			}
		}

		@Override
		public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
			for (Output<StreamRecord<T>> output : outputs) {
				output.collect(outputTag, record);
			}
		}

		@Override
		public void close() {
			for (Output<StreamRecord<T>> output : outputs) {
				output.close();
			}
		}
	}

	/**
	 * Special version of {@link BroadcastingOutputCollector} that performs a shallow copy of the
	 * {@link StreamRecord} to ensure that multi-chaining works correctly.
	 */
	private static final class CopyingBroadcastingOutputCollector<T> extends BroadcastingOutputCollector<T> {

		public CopyingBroadcastingOutputCollector(
				Output<StreamRecord<T>>[] outputs,
				StreamStatusProvider streamStatusProvider) {
			super(outputs, streamStatusProvider);
		}

		@Override
		public void collect(StreamRecord<T> record) {

			for (int i = 0; i < outputs.length - 1; i++) {
				Output<StreamRecord<T>> output = outputs[i];
				StreamRecord<T> shallowCopy = record.copy(record.getValue());
				output.collect(shallowCopy);
			}

			// don't copy for the last output
			outputs[outputs.length - 1].collect(record);
		}

		@Override
		public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
			for (int i = 0; i < outputs.length - 1; i++) {
				Output<StreamRecord<T>> output = outputs[i];

				StreamRecord<X> shallowCopy = record.copy(record.getValue());
				output.collect(outputTag, shallowCopy);
			}

			// don't copy for the last output
			outputs[outputs.length - 1].collect(outputTag, record);
		}
	}

	@Override
	public String toString() {
		return getClass().getName() + " - StreamOperators: " + Arrays.toString(allOperators)
			+ " - RecordWriterOutput: " + Arrays.toString(streamOutputs)
			+ " - Output<StreamRecord<OUT>>: " + chainEntryPoint
			+ " - OP: " + headOperator;
	}
}
