/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.deployment;

import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.*;
import org.apache.flink.runtime.instance.SimpleSlot;
import org.apache.flink.runtime.io.network.ConnectionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.Serializable;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Deployment descriptor for a single input channel instance.
 * <p>
 * <p> Each input channel consumes a single subpartition. The index of the subpartition to consume
 * is part of the {@link InputGateDeploymentDescriptor} as it is the same for each input channel of
 * the respective input gate.
 *
 * @see InputChannel
 * @see SingleInputGate
 */
public class InputChannelDeploymentDescriptor implements Serializable {

	private static final long serialVersionUID = 373711381640454080L;
	private static Logger LOG = LoggerFactory.getLogger(InputChannelDeploymentDescriptor.class);

	/** The ID of the partition the input channel is going to consume. */
	private final ResultPartitionID consumedPartitionId;

	/** The location of the partition the input channel is going to consume. */
	private final ResultPartitionLocation consumedPartitionLocation;

	public InputChannelDeploymentDescriptor(
			ResultPartitionID consumedPartitionId,
			ResultPartitionLocation consumedPartitionLocation) {

		this.consumedPartitionId = checkNotNull(consumedPartitionId);
		this.consumedPartitionLocation = checkNotNull(consumedPartitionLocation);
	}

	public ResultPartitionID getConsumedPartitionId() {
		return consumedPartitionId;
	}

	public ResultPartitionLocation getConsumedPartitionLocation() {
		return consumedPartitionLocation;
	}

	@Override
	public String toString() {
		return String.format("InputChannelDeploymentDescriptor [consumed partition id: %s, " +
						"consumed partition location: %s]",
				consumedPartitionId, consumedPartitionLocation);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof InputChannelDeploymentDescriptor)) return false;

		InputChannelDeploymentDescriptor that = (InputChannelDeploymentDescriptor) o;

		if (consumedPartitionId != null ? !consumedPartitionId.equals(that.consumedPartitionId) : that.consumedPartitionId != null)
			return false;
		return consumedPartitionLocation != null ? consumedPartitionLocation.equals(that.consumedPartitionLocation) : that.consumedPartitionLocation == null;
	}

	@Override
	public int hashCode() {
		int result = consumedPartitionId != null ? consumedPartitionId.hashCode() : 0;
		result = 31 * result + (consumedPartitionLocation != null ? consumedPartitionLocation.hashCode() : 0);
		return result;
	}

	// ------------------------------------------------------------------------

	/**
	 * Creates an input channel deployment descriptor for each partition.
	 */
	public static InputChannelDeploymentDescriptor[] fromEdges(ExecutionEdge[] edges,
															   SimpleSlot consumerSlot,
															   boolean allowLazyDeployment) throws ExecutionGraphException {
		final ResourceID consumerTaskManager = consumerSlot.getTaskManagerID();
		final InputChannelDeploymentDescriptor[] icdd = new InputChannelDeploymentDescriptor[edges.length];

		// Each edge is connected to a different result partition
		for (int i = 0; i < edges.length; i++) {
			final IntermediateResultPartition consumedPartition = edges[i].getSource();
			final Execution producer = consumedPartition.getProducer().getCurrentExecutionAttempt();

			final ExecutionState producerState = producer.getState();
			final SimpleSlot producerSlot = producer.getAssignedResource();

			final ResultPartitionLocation partitionLocation;

			// The producing task needs to be RUNNING or already FINISHED
			if (consumedPartition.isConsumable() && producerSlot != null &&
				(producerState == ExecutionState.RUNNING ||
					producerState == ExecutionState.FINISHED ||
					producerState == ExecutionState.SCHEDULED ||
					producerState == ExecutionState.DEPLOYING)) {

				final TaskManagerLocation partitionTaskManagerLocation = producerSlot.getTaskManagerLocation();
				final ResourceID partitionTaskManager = partitionTaskManagerLocation.getResourceID();

				if (partitionTaskManager.equals(consumerTaskManager)) {
					// Consuming task is deployed to the same TaskManager as the partition => local
					partitionLocation = ResultPartitionLocation.createLocal();
				} else {
					// Different instances => remote
					final ConnectionID connectionId = new ConnectionID(
						partitionTaskManagerLocation,
						consumedPartition.getIntermediateResult().getConnectionIndex());

					partitionLocation = ResultPartitionLocation.createRemote(connectionId);
				}
			} else if (allowLazyDeployment) {
				// The producing task might not have registered the partition yet
				partitionLocation = ResultPartitionLocation.createUnknown();
			} else if (producerState == ExecutionState.CANCELING
				|| producerState == ExecutionState.CANCELED
				|| producerState == ExecutionState.FAILED) {
				String msg = "Trying to schedule a task whose inputs were canceled or failed. " +
					"The producer is in state " + producerState + ".";
				throw new ExecutionGraphException(msg);
			} else {
				String msg = String.format("Trying to eagerly schedule a task whose inputs " +
						"are not ready (partition consumable? %s, producer state: %s, producer slot: %s) for producer %s.",
					consumedPartition.isConsumable(),
					producerState,
					producerSlot,
					producer.getVertex().getTaskNameWithSubtaskIndex());
				throw new ExecutionGraphException(msg);
			}

			ResultPartitionID consumedPartitionId =
				new ResultPartitionID(consumedPartition.getPartitionId(), producer.getAttemptId());

			icdd[i] = new InputChannelDeploymentDescriptor(consumedPartitionId, partitionLocation);
		}

		return icdd;
	}

	/**
	 * Creates an input channel deployment descriptor for each partition.
	 */
	public static InputChannelDeploymentDescriptor fromEdgesForSpecificPartition(ExecutionEdge[] edges,
																				   SimpleSlot consumerSlot,
																				   boolean allowLazyDeployment,
																				   int index) throws ExecutionGraphException {

		final ResourceID consumerTaskManager = consumerSlot.getTaskManagerID();

		if (edges.length <= index) {
			throw new IllegalStateException();
		}

		final IntermediateResultPartition consumedPartition = edges[index].getSource();

		final Execution producer = consumedPartition.getProducer().getCurrentExecutionAttempt();

		final ExecutionState producerState = producer.getState();

		SimpleSlot producerSlot;

		if (consumedPartition.getProducer().getFutureSlot() == null) {
			producerSlot = producer.getAssignedResource();
		} else {
			producerSlot = consumedPartition.getProducer().getFutureSlot(); // Get new assigned slot
		}

		final ResultPartitionLocation partitionLocation;

		// The producing task needs to be RUNNING or already FINISHED
		if (consumedPartition.isConsumable() && producerSlot != null &&
			(producerState == ExecutionState.RUNNING ||
				producerState == ExecutionState.FINISHED ||
				producerState == ExecutionState.SCHEDULED ||
				producerState == ExecutionState.DEPLOYING)) {

			final TaskManagerLocation partitionTaskManagerLocation = producerSlot.getTaskManagerLocation();
			final ResourceID partitionTaskManager = partitionTaskManagerLocation.getResourceID();

			if (partitionTaskManager.equals(consumerTaskManager)) {
				// Consuming task is deployed to the same TaskManager as the partition => local
				partitionLocation = ResultPartitionLocation.createLocal();
			} else {
				// Different instances => remote
				final ConnectionID connectionId = new ConnectionID(
					partitionTaskManagerLocation,
					consumedPartition.getIntermediateResult().getConnectionIndex());

				partitionLocation = ResultPartitionLocation.createRemote(connectionId);
			}
		} else if (allowLazyDeployment) {
			// The producing task might not have registered the partition yet
			partitionLocation = ResultPartitionLocation.createUnknown();
		} else if (producerState == ExecutionState.CANCELING
			|| producerState == ExecutionState.CANCELED
			|| producerState == ExecutionState.FAILED) {
			String msg = "Trying to schedule a task whose inputs were canceled or failed. " +
				"The producer is in state " + producerState + ".";
			throw new ExecutionGraphException(msg);
		} else {
			String msg = String.format("Trying to eagerly schedule a task whose inputs " +
					"are not ready (partition consumable? %s, producer state: %s, producer slot: %s).",
				consumedPartition.isConsumable(),
				producerState,
				producerSlot);
			throw new ExecutionGraphException(msg);
		}

		ResultPartitionID consumedPartitionId =
			new ResultPartitionID(consumedPartition.getPartitionId(), consumedPartition.getProducer().getUpcomingExecutionAttemptID());

		return new InputChannelDeploymentDescriptor(consumedPartitionId, partitionLocation);
	}

	/**
	 * Creates an input channel deployment descriptor for each partition.
	 */
	public static InputChannelDeploymentDescriptor fromEdgesForUpcomingOperator(SimpleSlot consumerSlot,
																				boolean allowLazyDeployment,
																				IntermediateResultPartition consumedPartition) throws ExecutionGraphException {

		final ResourceID consumerTaskManager = consumerSlot.getTaskManagerID();

		if (consumedPartition == null || consumedPartition.getProducer() == null) {
			throw new IllegalStateException();
		}

		final Execution producer = consumedPartition.getProducer().getCurrentExecutionAttempt();

		final ExecutionState producerState = producer.getState();

		SimpleSlot producerSlot;

		if (consumedPartition.getProducer().getFutureSlot() == null) {
			producerSlot = producer.getAssignedResource();
		} else {
			producerSlot = consumedPartition.getProducer().getFutureSlot(); // Get new assigned slot
		}

		final ResultPartitionLocation partitionLocation;

		// The producing task needs to be RUNNING or already FINISHED
		if (consumedPartition.isConsumable() && producerSlot != null &&
			(producerState == ExecutionState.RUNNING ||
				producerState == ExecutionState.FINISHED ||
				producerState == ExecutionState.CREATED ||
				producerState == ExecutionState.SCHEDULED ||
				producerState == ExecutionState.DEPLOYING)) {

			final TaskManagerLocation partitionTaskManagerLocation = producerSlot.getTaskManagerLocation();
			final ResourceID partitionTaskManager = partitionTaskManagerLocation.getResourceID();

			if (partitionTaskManager.equals(consumerTaskManager)) {
				// Consuming task is deployed to the same TaskManager as the partition => local
				partitionLocation = ResultPartitionLocation.createLocal();
			} else {
				// Different instances => remote
				final ConnectionID connectionId = new ConnectionID(
					partitionTaskManagerLocation,
					consumedPartition.getIntermediateResult().getConnectionIndex());

				partitionLocation = ResultPartitionLocation.createRemote(connectionId);
			}
		} else if (allowLazyDeployment) {
			// The producing task might not have registered the partition yet
			partitionLocation = ResultPartitionLocation.createUnknown();
		} else if (producerState == ExecutionState.CANCELING
			|| producerState == ExecutionState.CANCELED
			|| producerState == ExecutionState.FAILED) {
			String msg = "Trying to schedule a task whose inputs were canceled or failed. " +
				"The producer is in state " + producerState + ".";
			throw new ExecutionGraphException(msg);
		} else {
			String msg = String.format("Trying to eagerly schedule a task whose inputs " +
					"are not ready (partition consumable? %s, producer state: %s, producer slot: %s).",
				consumedPartition.isConsumable(),
				producerState,
				producerSlot);
			throw new ExecutionGraphException(msg);
		}

		ResultPartitionID consumedPartitionId = new ResultPartitionID(consumedPartition.getPartitionId(), producer.getAttemptId());

		return new InputChannelDeploymentDescriptor(consumedPartitionId, partitionLocation);
	}
}
