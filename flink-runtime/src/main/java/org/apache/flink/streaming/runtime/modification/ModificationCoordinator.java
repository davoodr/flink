package org.apache.flink.streaming.runtime.modification;

import com.google.common.base.Joiner;
import org.apache.commons.lang.StringUtils;
import org.apache.curator.shaded.com.google.common.collect.Sets;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.java.ClosureCleaner;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.blob.BlobKey;
import org.apache.flink.runtime.checkpoint.CheckpointIDCounter;
import org.apache.flink.runtime.checkpoint.SubtaskState;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.deployment.InputChannelDeploymentDescriptor;
import org.apache.flink.runtime.deployment.InputGateDeploymentDescriptor;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.*;
import org.apache.flink.runtime.instance.SimpleSlot;
import org.apache.flink.runtime.instance.SlotProvider;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.*;
import org.apache.flink.runtime.jobmanager.scheduler.ScheduledUnit;
import org.apache.flink.runtime.messages.modification.AcknowledgeModification;
import org.apache.flink.runtime.messages.modification.DeclineModification;
import org.apache.flink.runtime.messages.modification.IgnoreModification;
import org.apache.flink.runtime.messages.modification.StateMigrationModification;
import org.apache.flink.runtime.state.TaskStateHandles;
import org.apache.flink.runtime.taskmanager.DispatcherThreadFactory;
import org.apache.flink.runtime.taskmanager.TaskExecutionState;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.graph.StreamEdge;
import org.apache.flink.streaming.api.operators.StreamFilter;
import org.apache.flink.streaming.runtime.modification.exceptions.OperatorNotFoundException;
import org.apache.flink.streaming.runtime.tasks.OneInputStreamTask;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ModificationCoordinator {

	public enum ModificationAction {
		PAUSING, // For introducing operators to the job
		STOPPING // For migrating state between TaskManagers
	}

	private static final long MODIFICATION_TIMEOUT = 90;

	private static final Logger LOG = LoggerFactory.getLogger(ModificationCoordinator.class);

	private final Object lock = new Object();

	private final Object triggerLock = new Object();

	private final AtomicLong modificationIdCounter = new AtomicLong(1);

	private final Map<Long, PendingModification> pendingModifications = new LinkedHashMap<>();

	private final Map<Long, CompletedModification> completedModifications = new LinkedHashMap<>();

	private final Map<Long, PendingModification> failedModifications = new LinkedHashMap<Long, PendingModification>();

	private final Map<ExecutionAttemptID, SubtaskState> storedState = new ConcurrentHashMap<>();

	private final Map<ExecutionAttemptID, ExecutionVertex> vertexToRestart = new LinkedHashMap<>();

	private final ExecutionGraph executionGraph;

	private final Time rpcCallTimeout;

	private final Collection<BlobKey> blobKeys;

	private final ScheduledThreadPoolExecutor timer;

	private ExecutionAttemptID stoppedExecutionAttemptID;

	private int stoppedSubTaskIndex;

	public ModificationCoordinator(ExecutionGraph executionGraph, Time rpcCallTimeout) {
		this.executionGraph = Preconditions.checkNotNull(executionGraph);
		this.rpcCallTimeout = rpcCallTimeout;
		this.blobKeys = new HashSet<>();

		this.timer = new ScheduledThreadPoolExecutor(1,
			new DispatcherThreadFactory(Thread.currentThread().getThreadGroup(), "Modification Timer"));
	}

	public boolean receiveAcknowledgeMessage(AcknowledgeModification message) {

		if (message == null) {
			return false;
		}

		if (message.getJobID() != executionGraph.getJobID()) {
			LOG.error("Received wrong AcknowledgeCheckpoint message for job id {}: {}", message.getJobID(), message);
		}

		final long modificationID = message.getModificationID();

		synchronized (lock) {

			final PendingModification modification = pendingModifications.get(modificationID);

			if (modification != null && !modification.isDiscarded()) {

				switch (modification.acknowledgeTask(message.getTaskExecutionId())) {
					case SUCCESS:
						LOG.debug("Received acknowledge message for modification {} from task {} of job {}.",
							modificationID, message.getTaskExecutionId(), message.getJobID());

						if (modification.isFullyAcknowledged()) {
							completePendingCheckpoint(modification);
						}

						ExecutionVertex executionVertex = vertexToRestart.get(message.getTaskExecutionId());

						if (executionVertex != null) {
							restartIfStoppedAndStateReceived(executionVertex);
						}

						break;
					case DUPLICATE:
						LOG.debug("Received a duplicate acknowledge message for modification {}, task {}, job {}.",
							message.getModificationID(), message.getTaskExecutionId(), message.getJobID());
						break;
					case UNKNOWN:
						LOG.warn("Could not acknowledge the modification {} for task {} of job {}, " +
								"because the task's execution attempt id was unknown.",
							message.getModificationID(), message.getTaskExecutionId(), message.getJobID());

						break;
					case DISCARDED:
						LOG.warn("Could not acknowledge the modification {} for task {} of job {}, " +
								"because the pending modification had been discarded",
							message.getModificationID(), message.getTaskExecutionId(), message.getJobID());
				}

				return true;
			} else if (modification != null) {
				// this should not happen
				throw new IllegalStateException(
					"Received message for discarded but non-removed modification " + modificationID);
			} else {
				boolean wasPendingModification;

				// message is for an unknown modification, or comes too late (modification disposed)
				if (completedModifications.containsKey(modificationID)) {
					wasPendingModification = true;
					LOG.warn("Received late message for now expired modification attempt {} from " +
						"{} of job {}.", modificationID, message.getTaskExecutionId(), message.getJobID());
				} else {
					LOG.debug("Received message for an unknown modification {} from {} of job {}.",
						modificationID, message.getTaskExecutionId(), message.getJobID());
					wasPendingModification = false;
				}

				return wasPendingModification;
			}
		}
	}

	/**
	 * Try to complete the given pending modification.
	 * <p>
	 * Important: This method should only be called in the checkpoint lock scope.
	 *
	 * @param pendingModification to complete
	 */
	@GuardedBy("lock")
	private void completePendingCheckpoint(PendingModification pendingModification) {

		assert (Thread.holdsLock(lock));

		final long checkpointId = pendingModification.getModificationId();

		CompletedModification completedModification = pendingModification.finalizeCheckpoint();

		pendingModifications.remove(pendingModification.getModificationId());

		if (completedModification != null) {
			Preconditions.checkState(pendingModification.isFullyAcknowledged());
			completedModifications.put(pendingModification.getModificationId(), completedModification);

			LOG.info("Completed modification {} ({}) in {} ms.",
				pendingModification.getModificationDescription(), checkpointId, completedModification.getDuration());
		} else {
			failedModifications.put(pendingModification.getModificationId(), pendingModification);

			LOG.info("Modification {} ({}) failed.", pendingModification.getModificationDescription(), checkpointId);
		}

		// Maybe modify operators of completed modification
	}

	public void receiveDeclineMessage(DeclineModification message) {

		if (message == null) {
			return;
		}

		if (message.getJobID() != executionGraph.getJobID()) {
			LOG.error("Received wrong AcknowledgeCheckpoint message for job id {}: {}", message.getJobID(), message);
		}

		final long modificationID = message.getModificationID();
		final String reason = (message.getReason() != null ? message.getReason().getMessage() : "");

		PendingModification pendingModification;

		synchronized (lock) {

			pendingModification = pendingModifications.get(modificationID);

			if (pendingModification != null && !pendingModification.isDiscarded()) {
				LOG.info("Discarding pendingModification {} because of modification decline from task {} : {}",
					modificationID, message.getTaskExecutionId(), reason);

				pendingModifications.remove(modificationID);
				pendingModification.abortDeclined();
				failedModifications.put(modificationID, pendingModification);
			} else if (pendingModification != null) {
				// this should not happen
				throw new IllegalStateException(
					"Received message for discarded but non-removed pendingModification " + modificationID);
			} else {
				if (failedModifications.containsKey(modificationID)) {
					// message is for an unknown pendingModification, or comes too late (pendingModification disposed)
					LOG.debug("Received another decline message for now expired pendingModification attempt {} : {}",
						modificationID, reason);
				} else {
					// message is for an unknown pendingModification. might be so old that we don't even remember it any more
					LOG.debug("Received decline message for unknown (too old?) pendingModification attempt {} : {}",
						modificationID, reason);
				}
			}
		}
	}

	public void receiveIgnoreMessage(IgnoreModification message) {

		if (message == null) {
			return;
		}

		if (message.getJobID() != executionGraph.getJobID()) {
			LOG.error("Received wrong IgnoreModification message for job id {}: {}", message.getJobID(), message);
		}

		final long modificationID = message.getModificationID();

		PendingModification pendingModification;

		synchronized (lock) {

			pendingModification = pendingModifications.get(modificationID);

			if (pendingModification != null && !pendingModification.isDiscarded()) {
				LOG.info("Received ignoring modification for {} from task {}",
					modificationID, message.getTaskExecutionId());
			} else if (pendingModification != null) {
				// this should not happen
				throw new IllegalStateException(
					"Received message for discarded but non-removed pendingModification " + modificationID);
			} else {
				if (failedModifications.containsKey(modificationID)) {
					// message is for an unknown pendingModification, or comes too late (pendingModification disposed)
					LOG.debug("Received another ignore message for now expired pendingModification attempt {} : {}",
						modificationID, message.getTaskExecutionId());
				} else {
					// message is for an unknown pendingModification. might be so old that we don't even remember it any more
					LOG.debug("Received ignore message for unknown (too old?) pendingModification attempt {} : {}",
						modificationID, message.getTaskExecutionId());
				}
			}
		}
	}

	public void receiveStateMigrationMessage(StateMigrationModification message) {

		if (message == null) {
			return;
		}

		if (message.getJobID() != executionGraph.getJobID()) {
			LOG.error("Received wrong StateMigrationModification message for job id {}: {}", message.getJobID(), message);
		}

		final long modificationID = message.getModificationID();

		synchronized (lock) {

			if (storedState.put(message.getTaskExecutionId(), message.getSubtaskState()) != null) {
				LOG.info("Received duplicate StateMigrationModification for {} from task {}. Removed previous.",
					modificationID, message.getTaskExecutionId());
			} else {
				LOG.info("Received valid StateMigrationModification for {} from task {}",
					modificationID, message.getTaskExecutionId());
			}

			PendingModification pendingModification = pendingModifications.get(modificationID);

			if (pendingModification != null && !pendingModification.isDiscarded()) {
				LOG.info("Received ignoring modification for {} from task {}",
					modificationID, message.getTaskExecutionId());
			} else if (pendingModification != null && pendingModification.isDiscarded()) {
				if (failedModifications.containsKey(modificationID)) {
					// message is for an unknown pendingModification, or comes too late (pendingModification disposed)
					LOG.debug("Received another ignore message for now expired StateMigrationModification attempt {} : {}",
						modificationID, message.getTaskExecutionId());
				} else {
					// message is for an unknown pendingModification. might be so old that we don't even remember it any more
					LOG.debug("Received ignore message for unknown (too old?) StateMigrationModification attempt {} : {}",
						modificationID, message.getTaskExecutionId());
				}
			} else {
				LOG.debug("Received message for discarded but non-removed pendingModification {}.", modificationID);
			}
		}

		ExecutionVertex executionVertex = vertexToRestart.get(message.getTaskExecutionId());

		if (executionVertex != null) {
			restartIfStoppedAndStateReceived(executionVertex);
		}
	}

	public void vertexUpdatedState(TaskExecutionState state) {

		ExecutionVertex executionVertex = vertexToRestart.get(state.getID());

		if (executionVertex == null) {
			LOG.info("Informed about vertex, that should not be restarted {}", state.getID());
			return;
		}

		switch (state.getExecutionState()) {
			case RUNNING:
			case FINISHED:
			case CANCELED:
			case FAILED:
			case PAUSING:
			case RESUMING:
				return;

			case PAUSED:
				restartIfStoppedAndStateReceived(executionVertex);
				break;

			default:
				// we mark as failed and return false, which triggers the TaskManager to remove the task
				executionGraph.failGlobal(new Exception("TaskManager sent illegal state update: " + state.getExecutionState()));
		}
	}

	private synchronized void restartIfStoppedAndStateReceived(ExecutionVertex vertex) {

		ExecutionAttemptID attemptID = vertex.getCurrentExecutionAttempt().getAttemptId();

		boolean correctState = vertex.getCurrentExecutionAttempt().getState() == ExecutionState.PAUSED;

		if (vertexToRestart.containsKey(attemptID) && storedState.containsKey(attemptID) && correctState) {

			vertexToRestart.remove(attemptID);
			SubtaskState state = storedState.remove(attemptID);

			restartOperatorInstanceWithState(vertex, state);
		}
	}

	private void restartOperatorInstanceWithState(ExecutionVertex vertex, SubtaskState state) {
		try {

			Execution currentExecutionAttempt = vertex.resetForNewExecutionMigration(
				System.currentTimeMillis(),
				executionGraph.getGlobalModVersion());

			if (state == null) {
				throw new IllegalStateException("Could not find state to restore for ExecutionAttempt: "
					+ stoppedExecutionAttemptID);
			} else {
				TaskStateHandles taskStateHandles = new TaskStateHandles(state);

				currentExecutionAttempt.setInitialState(taskStateHandles);
			}

			currentExecutionAttempt.scheduleForMigration();

		} catch (Exception exception) {
			executionGraph.failGlobal(exception);
			LOG.error("Failed to restart operator from migration", exception);
		}
	}

	private void triggerModification(ExecutionJobVertex instancesToPause, final String description, ModificationAction action) {

		ArrayList<ExecutionVertex> indicesToPause = new ArrayList<>(instancesToPause.getTaskVertices().length);
		indicesToPause.addAll(Arrays.asList(instancesToPause.getTaskVertices()));

		ExecutionJobVertex upstreamOperator = getUpstreamOperator(indicesToPause.get(0));

		ArrayList<ExecutionAttemptID> upstream = new ArrayList<>();
		for (ExecutionVertex executionVertex : upstreamOperator.getTaskVertices()) {
			upstream.add(executionVertex.getCurrentExecutionAttempt().getAttemptId());
		}

		triggerModification(upstream, indicesToPause, description, action);
	}

	private void triggerModification(List<ExecutionAttemptID> operatorsIdsToSpill,
									 List<ExecutionVertex> subtaskIndicesToPause,
									 final String description,
									 ModificationAction action) {

		Preconditions.checkNotNull(operatorsIdsToSpill);
		Preconditions.checkNotNull(subtaskIndicesToPause);
		Preconditions.checkNotNull(description);
		Preconditions.checkArgument(subtaskIndicesToPause.size() >= 1);
		Preconditions.checkArgument(operatorsIdsToSpill.size() >= 1);

		LOG.info("Triggering modification '{}'.", description);

		Map<ExecutionAttemptID, ExecutionVertex> ackTasks = new HashMap<>(subtaskIndicesToPause.size());
		Set<Integer> operatorSubTaskIndices = new HashSet<>();

		for (ExecutionVertex executionVertex : subtaskIndicesToPause) {
			ackTasks.put(executionVertex.getCurrentExecutionAttempt().getAttemptId(), executionVertex);
			operatorSubTaskIndices.add(executionVertex.getCurrentExecutionAttempt().getParallelSubtaskIndex());

			if (executionVertex.getExecutionState() != ExecutionState.RUNNING) {
				throw new RuntimeException("ExecutionVertex " + executionVertex + " is not in running state.");
			}
		}

		synchronized (triggerLock) {

			final long modificationId = modificationIdCounter.getAndIncrement();
			final long timestamp = System.currentTimeMillis();

			final PendingModification modification = new PendingModification(
				executionGraph.getJobID(),
				modificationId,
				timestamp,
				ackTasks,
				description);

			// schedule the timer that will clean up the expired checkpoints
			final Runnable canceller = new Runnable() {
				@Override
				public void run() {
					synchronized (lock) {

						LOG.info("Checking if Modification {} ({}) is still ongoing.", description, modificationId);

						// only do the work if the modification is not discarded anyways
						// note that modification completion discards the pending modification object
						if (!modification.isDiscarded()) {
							LOG.info("Modification {} expired before completing.", description);

							modification.abortExpired();
							pendingModifications.remove(modificationId);
							failedModifications.remove(modificationId);
						} else {
							LOG.info("Modification {} already completed.", description);
						}
					}
				}
			};

			try {
				// re-acquire the coordinator-wide lock
				synchronized (lock) {

					LOG.info("Triggering modification {}@{} - {}.", modificationId, timestamp, description);

					pendingModifications.put(modificationId, modification);

					ScheduledFuture<?> cancellerHandle = timer.schedule(
						canceller,
						MODIFICATION_TIMEOUT, TimeUnit.SECONDS);

					modification.setCancellerHandle(cancellerHandle);
				}

				long checkpointIDToModify = -1;
				CheckpointIDCounter checkpointIdCounter = executionGraph.getCheckpointCoordinator().getCheckpointIdCounter();

				// Check if checkpointing is enabled
				if (checkpointIdCounter.getCurrent() >= 2) {
					checkpointIDToModify = checkpointIdCounter.getCurrent() + 2;
				}

				ExecutionJobVertex source = findSource();

				// send the messages to the tasks that trigger their modification
				for (ExecutionVertex execution : source.getTaskVertices()) {
					execution.getCurrentExecutionAttempt().triggerMigration(
						modificationId,
						timestamp,
						new HashSet<>(operatorsIdsToSpill),
						operatorSubTaskIndices,
						action,
						checkpointIDToModify); // KeySet not serializable
				}

			} catch (Throwable t) {
				// guard the map against concurrent modifications
				synchronized (lock) {
					pendingModifications.remove(modificationId);
				}

				if (!modification.isDiscarded()) {
					modification.abortError(new Exception("Failed to trigger modification", t));
				}
			}
		}
	}

	public String getTMDetails() {

		StringBuilder details = new StringBuilder();

		for (ExecutionVertex executionVertex : executionGraph.getAllExecutionVertices()) {
			details.append("\nAttemptID: ")
				.append(executionVertex.getCurrentExecutionAttempt().getAttemptId())
				.append(" - TM Location: ")
				.append(executionVertex.getCurrentAssignedResource().getTaskManagerLocation())
				.append(" - Name: ")
				.append(executionVertex.getTaskNameWithSubtaskIndex());
		}

		return details.toString();
	}

	public void migrateAllFromTaskmanager(ResourceID taskmanagerID) throws ExecutionGraphException {
		Collection<ExecutionJobVertex> allVertices = executionGraph.getAllVertices().values();

		List<ExecutionVertex> allVerticesOnTM = new ArrayList<>();

		for (ExecutionJobVertex vertex : allVertices) {
			for (ExecutionVertex executionVertex : vertex.getTaskVertices()) {
				if (executionVertex.getCurrentExecutionAttempt().getAssignedResource().getTaskManagerID().equals(taskmanagerID)) {
					allVerticesOnTM.add(executionVertex);
					executionVertex.prepareForMigration();
					vertexToRestart.put(executionVertex.getCurrentExecutionAttempt().getAttemptId(), executionVertex);
				}
			}
		}

		Map<ExecutionAttemptID, SimpleSlot> reservedSlots = allocateSlotsOnDifferentTaskmanagers(taskmanagerID, allVerticesOnTM);

		Map<ExecutionAttemptID, Set<Integer>> spillingToDiskIDs = new HashMap<>();
		Map<ExecutionAttemptID, List<InputChannelDeploymentDescriptor>> pausingIDs = new HashMap<>();

		for (ExecutionVertex vertex : allVerticesOnTM) {

			ExecutionJobVertex upstreamOperator = getUpstreamOperator(vertex);

			if (upstreamOperator != null) {
				// Non-Source operator
				ExecutionVertex[] executionVertices = upstreamOperator.getTaskVertices();
				for (ExecutionVertex executionVertex : executionVertices) {
					// spillingToDiskIDs.add(executionVertex.getCurrentExecutionAttempt().getAttemptId());

					Set<Integer> subIndices = spillingToDiskIDs.get(executionVertex.getCurrentExecutionAttempt().getAttemptId());

					if (subIndices == null) {
						HashSet<Integer> indices = Sets.newHashSet(vertex.getParallelSubtaskIndex());
						spillingToDiskIDs.put(executionVertex.getCurrentExecutionAttempt().getAttemptId(), indices);
					} else {
						subIndices.add(vertex.getParallelSubtaskIndex());
					}
				}
			}

			// Now create a list of icdd for the migrating task, that will be send downstream to all consuming tasks
			List<InputChannelDeploymentDescriptor> list = new ArrayList<>();
			ExecutionJobVertex downstreamOperator = getDownstreamOperator(vertex);

			if (downstreamOperator != null) {
				for (ExecutionVertex executionVertex : downstreamOperator.getTaskVertices()) {
					// TODO Currently only works for one input streams

					assert executionVertex.getNumberOfInputs() == 1;

					InputChannelDeploymentDescriptor icdd = InputChannelDeploymentDescriptor.fromEdgesForSpecificPartition(
						executionVertex.getInputEdges(0),
						executionVertex.getCurrentAssignedResource(),
						executionGraph.isQueuedSchedulingAllowed(),
						vertex.getParallelSubtaskIndex());

					list.add(icdd);
				}
			}

			ExecutionAttemptID attemptId = vertex.getCurrentExecutionAttempt().getAttemptId();
			pausingIDs.put(attemptId, list);
		}

		for (ExecutionAttemptID executionAttemptID : pausingIDs.keySet()) {
			spillingToDiskIDs.remove(executionAttemptID);
		}

		triggerMigration(spillingToDiskIDs, pausingIDs, "Migrating all operators from " + taskmanagerID);
	}

	private Map<ExecutionAttemptID, SimpleSlot> allocateSlotsOnDifferentTaskmanagers(ResourceID taskmanagerIDToExclude, List<ExecutionVertex> vertices) {
		SlotProvider slotProvider = executionGraph.getSlotProvider();

		Map<ExecutionAttemptID, SimpleSlot> slots = new HashMap<>();

		for (ExecutionVertex executionVertex : vertices) {

			ScheduledUnit scheduledUnit = executionVertex.getCurrentExecutionAttempt().getScheduledUnit();

			SimpleSlot simpleSlot = slotProvider
				.allocateSlotExceptOnTaskmanager(scheduledUnit, executionGraph.isQueuedSchedulingAllowed(), taskmanagerIDToExclude);

			executionVertex.assignSlotForMigration(simpleSlot);

			slots.put(executionVertex.getCurrentExecutionAttempt().getAttemptId(), simpleSlot);
		}

		return slots;
	}

	private void triggerMigration(Map<ExecutionAttemptID, Set<Integer>> spillingToDiskIDs,
								  Map<ExecutionAttemptID, List<InputChannelDeploymentDescriptor>> pausingIDs,
								  final String description) {

		Preconditions.checkNotNull(spillingToDiskIDs);
		Preconditions.checkNotNull(pausingIDs);
		Preconditions.checkNotNull(description);

		LOG.info("Triggering modification '{}'.", description);

		Map<ExecutionAttemptID, ExecutionVertex> ackTasks = new HashMap<>();

		for (ExecutionVertex executionVertex : executionGraph.getAllExecutionVertices()) {
			ackTasks.put(executionVertex.getCurrentExecutionAttempt().getAttemptId(), executionVertex);
		}

		synchronized (triggerLock) {

			final long modificationId = modificationIdCounter.getAndIncrement();
			final long timestamp = System.currentTimeMillis();

			final PendingModification modification = new PendingModification(
				executionGraph.getJobID(),
				modificationId,
				timestamp,
				ackTasks,
				description);

			// schedule the timer that will clean up the expired checkpoints
			final Runnable canceller = new Runnable() {
				@Override
				public void run() {
					synchronized (lock) {

						LOG.info("Checking if Modification {} ({}) is still ongoing.", description, modificationId);

						// only do the work if the modification is not discarded anyways
						// note that modification completion discards the pending modification object
						if (!modification.isDiscarded()) {
							LOG.info("Modification {} expired before completing.", description);

							modification.abortExpired();
							pendingModifications.remove(modificationId);
							failedModifications.remove(modificationId);
						} else {
							LOG.info("Modification {} already completed.", description);
						}
					}
				}
			};

			try {
				// re-acquire the coordinator-wide lock
				synchronized (lock) {

					LOG.info("Triggering modification {}@{} - {}.", modificationId, timestamp, description);

					pendingModifications.put(modificationId, modification);

					ScheduledFuture<?> cancellerHandle =
						timer.schedule(canceller, MODIFICATION_TIMEOUT, TimeUnit.SECONDS);

					modification.setCancellerHandle(cancellerHandle);
				}

				long checkpointIDToModify = -1;
				CheckpointIDCounter checkpointIdCounter = executionGraph.getCheckpointCoordinator().getCheckpointIdCounter();

				// Check if checkpointing is enabled
				if (checkpointIdCounter.getCurrent() >= 2) {
					checkpointIDToModify = checkpointIdCounter.getCurrent() + 2;
				}

				ExecutionJobVertex source = findSource();

				// send the messages to the tasks that trigger their modification
				for (ExecutionVertex execution : source.getTaskVertices()) {
					execution.getCurrentExecutionAttempt().triggerMigration(
						modificationId,
						timestamp,
						spillingToDiskIDs,
						pausingIDs,
						checkpointIDToModify); // KeySet not serializable
				}

			} catch (Throwable t) {
				// guard the map against concurrent modifications
				synchronized (lock) {
					pendingModifications.remove(modificationId);
				}

				if (!modification.isDiscarded()) {
					modification.abortError(new Exception("Failed to trigger modification", t));
				}
			}
		}
	}

	private ExecutionVertex getMapExecutionVertexToStop(ResourceID taskManagerId) {
		ExecutionJobVertex map = findMap();

		ExecutionVertex[] taskVertices = map.getTaskVertices();

		for (ExecutionVertex executionVertex : taskVertices) {
			if (executionVertex.getCurrentAssignedResource().getTaskManagerID().equals(taskManagerId)) {
				return executionVertex;
			}
		}

		return null;
	}

	public void increaseDOPOfSink() {
		ExecutionJobVertex sink = findSink();

		ExecutionJobVertex filter = findFilter();
		ExecutionVertex[] taskVertices = filter.getTaskVertices();

		assert taskVertices.length == 3;

		ExecutionVertex taskVertex = taskVertices[2];

		Map<IntermediateResultPartitionID, IntermediateResultPartition> producedPartitions = taskVertex.getProducedPartitions();
		assert producedPartitions.size() == 1;
		IntermediateResultPartitionID irpidOfThirdFilterOperator = producedPartitions.keySet().iterator().next();

		IntermediateResultPartition next = producedPartitions.values().iterator().next();
		int connectionIndex = next.getIntermediateResult().getConnectionIndex();

		TaskManagerLocation filterTMLocation = taskVertex.getCurrentAssignedResource().getTaskManagerLocation();

		sink.getTaskVertices()[0].getCurrentExecutionAttempt().consumeNewProducer(
			rpcCallTimeout,
			taskVertex.getCurrentExecutionAttempt().getAttemptId(),
			irpidOfThirdFilterOperator,
			filterTMLocation,
			connectionIndex,
			2);
	}

	public void increaseDOPOfMap() {
		ExecutionJobVertex map = findMap();

		for (ExecutionVertex executionVertex : map.getTaskVertices()) {
			executionVertex.getCurrentExecutionAttempt().addNewConsumer();
		}
	}

	public void increaseDOPOfFilter() {
		ExecutionJobVertex filter = findFilter();

		ExecutionVertex executionVertex = filter.increaseDegreeOfParallelism(
			rpcCallTimeout,
			executionGraph.getGlobalModVersion(),
			System.currentTimeMillis(),
			executionGraph.getAllIntermediateResults());

		assert filter.getParallelism() == 3;

		ExecutionVertex[] taskVertices = filter.getTaskVertices();

		assert taskVertices.length == 3;

		ExecutionVertex taskVertex = taskVertices[2];

		taskVertex.scheduleForExecution(executionGraph.getSlotProvider(), executionGraph.isQueuedSchedulingAllowed());
	}

	public void pauseAll(String operatorName) {
		LOG.info("Attempting to pause all instances for operator {}.", operatorName);

		List<ExecutionVertex> operatorsIDs = getAllExecutionVerticesForName(operatorName);

		ExecutionJobVertex previousOperator = getUpstreamOperator(operatorsIDs.get(0));

		ArrayList<ExecutionAttemptID> upstreamIds = new ArrayList<>();
		for (ExecutionVertex executionVertex : previousOperator.getTaskVertices()) {
			upstreamIds.add(executionVertex.getCurrentExecutionAttempt().getAttemptId());
		}

		triggerModification(upstreamIds, operatorsIDs, "Pause " + operatorName + " instances", ModificationAction.PAUSING);
	}

	private List<ExecutionVertex> getAllExecutionVerticesForName(String operatorName) {
		Collection<ExecutionJobVertex> vertices = executionGraph.getAllVertices().values();

		for (ExecutionJobVertex vertex : vertices) {
			if (vertex.getName().contains(operatorName)) {
				return new ArrayList<>(Arrays.asList(vertex.getTaskVertices()));
			}
		}

		throw new IllegalStateException("Could not find any operator, who's name contains: " + operatorName);
	}

	private ExecutionJobVertex getUpstreamOperator(ExecutionVertex jobVertex) {
		Preconditions.checkNotNull(jobVertex);

		return getUpstreamOperator(jobVertex.getJobVertex());
	}

	private ExecutionJobVertex getUpstreamOperator(ExecutionJobVertex jobVertex) {
		Preconditions.checkNotNull(jobVertex);

		ExecutionVertex[] taskVertices = jobVertex.getTaskVertices();
		if (taskVertices == null || taskVertices.length == 0) {
			return null;
		}

		// TODO Assume single producer
		return taskVertices[0].getInputEdges(0)[0].getSource().getProducer().getJobVertex();
	}

	private ExecutionJobVertex getDownstreamOperator(ExecutionVertex jobVertex) {
		Preconditions.checkNotNull(jobVertex);

		return getDownstreamOperator(jobVertex.getJobVertex());
	}

	private ExecutionJobVertex getDownstreamOperator(ExecutionJobVertex jobVertex) {
		Preconditions.checkNotNull(jobVertex);

		ExecutionVertex[] taskVertices = jobVertex.getTaskVertices();
		if (taskVertices == null || taskVertices.length == 0) {
			return null;
		}

		Collection<IntermediateResultPartition> producedPartitions = taskVertices[0].getProducedPartitions().values();

		if (producedPartitions.size() != 1) {
			throw new IllegalStateException("Number of produced partitions is not 1");
		}

		// TODO Assume single producer
		return producedPartitions.iterator().next().getConsumers().get(0).get(0).getTarget().getJobVertex();
	}

	public void resumeAll(String operatorName) {
		Iterable<ExecutionVertex> allExecutionVertices = executionGraph.getAllExecutionVertices();

		LOG.info("Attempting to resume all instances for operator {}.", operatorName);

		boolean foundOperator = false;

		for (ExecutionVertex vertex : allExecutionVertices) {
			if (vertex.getTaskName().toLowerCase().contains(operatorName)) {
				Execution execution = vertex.getCurrentExecutionAttempt();

				execution.getAssignedResource()
					.getTaskManagerGateway()
					.resumeTask(execution.getAttemptId(), rpcCallTimeout);

				foundOperator = true;
			}
		}

		if (!foundOperator) {
			throw new RuntimeException("Could not find any operator, that contains: " + operatorName);
		}
	}

	public void restartOperatorInstance(ResourceID taskmanagerID) {
		ExecutionVertex stoppedExecutionVertex = null;

		for (ExecutionVertex executionVertex : executionGraph.getAllExecutionVertices()) {
			if (executionVertex.getCurrentExecutionAttempt().getAttemptId().equals(stoppedExecutionAttemptID)) {
				stoppedExecutionVertex = executionVertex;
				break;
			}
		}

		if (stoppedExecutionVertex == null) {
			executionGraph.failGlobal(new RuntimeException("Could not find stopped Map executionVertex"));
			return;
		}

		try {
			stoppedExecutionVertex.resetForNewExecutionModification(
				System.currentTimeMillis(),
				executionGraph.getGlobalModVersion());

			Execution currentExecutionAttempt = stoppedExecutionVertex.getCurrentExecutionAttempt();

			SubtaskState storedState = this.storedState.get(stoppedExecutionAttemptID);

			if (storedState == null) {
				throw new IllegalStateException("Could not find state to restore for ExecutionAttempt: "
					+ stoppedExecutionAttemptID);
			} else {
				TaskStateHandles taskStateHandles = new TaskStateHandles(storedState);

				currentExecutionAttempt.setInitialState(taskStateHandles);
			}

			currentExecutionAttempt
				.scheduleForMigration(
					executionGraph.getSlotProvider(),
					executionGraph.isQueuedSchedulingAllowed(),
					taskmanagerID);

		} catch (GlobalModVersionMismatch globalModVersionMismatch) {
			executionGraph.failGlobal(globalModVersionMismatch);
			globalModVersionMismatch.printStackTrace();
		}
	}

	public void addedNewOperatorJar(Collection<BlobKey> blobKeys) {
		LOG.debug("Adding BlobKeys {} for executionGraph {}.",
			StringUtils.join(blobKeys, ","),
			executionGraph.getJobID());

		this.blobKeys.addAll(blobKeys);
	}

	public void pauseSink() {
		ExecutionJobVertex sink = findSink();

		triggerModification(sink, "Pause Sink", ModificationAction.PAUSING);
	}

	public void modifySinkInstance() {
		ExecutionJobVertex sink = findSink();

		Preconditions.checkNotNull(sink);

		ExecutionVertex[] taskVertices = findMap().getTaskVertices();
		assert taskVertices != null;
		assert taskVertices.length >= stoppedSubTaskIndex;
		Execution newMapExecutionAttemptId = taskVertices[stoppedSubTaskIndex].getCurrentExecutionAttempt();
		assert newMapExecutionAttemptId.getAttemptId() != stoppedExecutionAttemptID;

		ExecutionVertex[] sinkTaskVertices = sink.getTaskVertices();

		for (ExecutionVertex executionVertex : sinkTaskVertices) {

			List<InputGateDeploymentDescriptor> inputGateDeploymentDescriptor;
			try {
				inputGateDeploymentDescriptor =
					executionVertex.createInputGateDeploymentDescriptor(executionVertex.getCurrentAssignedResource());
			} catch (ExecutionGraphException e) {
				throw new RuntimeException(e);
			}

			executionVertex.getCurrentExecutionAttempt()
				.triggerResumeWithDifferentInputs(
					rpcCallTimeout,
					stoppedSubTaskIndex,
					inputGateDeploymentDescriptor);
		}
	}

	public void modifyMapInstanceForFilter() {
		ExecutionJobVertex map = findMap();
		ExecutionJobVertex filter = findFilter();

		Preconditions.checkNotNull(map);

		List<IntermediateDataSet> producedDataSets = filter.getJobVertex().getProducedDataSets();
		assert producedDataSets.size() == 1;

		ExecutionVertex[] mapTaskVertices = map.getTaskVertices();

		for (ExecutionVertex executionVertex : mapTaskVertices) {

			List<InputGateDeploymentDescriptor> inputGateDeploymentDescriptor;
			try {
				inputGateDeploymentDescriptor =
					executionVertex.createInputGateDeploymentDescriptor(executionVertex.getCurrentAssignedResource());
			} catch (ExecutionGraphException e) {
				throw new RuntimeException(e);
			}

			executionVertex.getCurrentExecutionAttempt()
				.triggerResumeWithNewInput(
					rpcCallTimeout,
					inputGateDeploymentDescriptor);
		}
	}

	public void resumeSink() {
		ExecutionJobVertex source = findSink();

		ExecutionVertex[] taskVertices = source.getTaskVertices();

		for (ExecutionVertex vertex : taskVertices) {
			Execution execution = vertex.getCurrentExecutionAttempt();

			execution.getAssignedResource()
				.getTaskManagerGateway()
				.resumeTask(execution.getAttemptId(), rpcCallTimeout);
		}
	}

	public void resumeFilter() {
		ExecutionJobVertex source = findFilter();

		ExecutionVertex[] taskVertices = source.getTaskVertices();

		for (ExecutionVertex vertex : taskVertices) {
			Execution execution = vertex.getCurrentExecutionAttempt();

			execution.getAssignedResource()
				.getTaskManagerGateway()
				.resumeTask(execution.getAttemptId(), rpcCallTimeout);
		}
	}

	public void pauseFilter() {
		ExecutionJobVertex filter = findFilter();

		triggerModification(filter, "Pause Filter", ModificationAction.PAUSING);
	}

	public void pauseMap() {
		ExecutionJobVertex map = findMap();

		triggerModification(map, "Pause map", ModificationAction.PAUSING);
	}

	public void pauseSingleOperatorInstance(ExecutionAttemptID attemptID) {

		ExecutionVertex singleExecutionVertex = getSingleExecutionVertex(attemptID);

		ArrayList<ExecutionAttemptID> upstreamIds = new ArrayList<>();
		for (ExecutionVertex executionVertex : getUpstreamOperator(singleExecutionVertex).getTaskVertices()) {
			upstreamIds.add(executionVertex.getCurrentExecutionAttempt().getAttemptId());
		}

		stoppedExecutionAttemptID = singleExecutionVertex.getCurrentExecutionAttempt().getAttemptId();
		stoppedSubTaskIndex = singleExecutionVertex.getCurrentExecutionAttempt().getParallelSubtaskIndex();

		triggerModification(upstreamIds,
			Collections.singletonList(singleExecutionVertex),
			"Pause single map instance",
			ModificationAction.STOPPING);
	}

	private ExecutionVertex getSingleExecutionVertex(ExecutionAttemptID attemptID) {
		for (ExecutionVertex executionVertex : executionGraph.getAllExecutionVertices()) {
			if (executionVertex.getCurrentExecutionAttempt().getAttemptId().equals(attemptID)) {
				return executionVertex;
			}
		}

		executionGraph.failGlobal(new Exception("Failed to find map operator instance for " + attemptID));
		throw new IllegalStateException("Could not find operator with ExecutionAttemptID: " + attemptID);
	}

	public void resumeMapOperator() {
		ExecutionJobVertex source = findMap();

		ExecutionVertex[] taskVertices = source.getTaskVertices();

		for (ExecutionVertex vertex : taskVertices) {
			Execution execution = vertex.getCurrentExecutionAttempt();

			// TODO Masterthesis: Unnecessary, as getCurrentExecutionAttempt simply returns the last execution.
			// TODO Masterthesis: Therefore, the old attempt never gets resumed
			if (execution.getAttemptId().equals(stoppedExecutionAttemptID)) {
				LOG.info("Skipping resuming of map operator for ExecutionAttemptID {}");
				continue;
			}

			execution.getAssignedResource()
				.getTaskManagerGateway()
				.resumeTask(execution.getAttemptId(), rpcCallTimeout);
		}
	}

	public void startFilterOperator(int parallelism) {

		ExecutionJobVertex sourceOperator = findSource();

		if (sourceOperator == null) {
			executionGraph.failGlobal(new OperatorNotFoundException("Source", executionGraph.getJobID()));
			return;
		}

		ExecutionJobVertex filterExecutionJobVertex = buildFilterExecutionJobVertex(sourceOperator, parallelism);

		if (filterExecutionJobVertex == null) {
			throw new IllegalStateException("Could not create FilterExecutionJobVertex");
		} else {
			LOG.debug("Starting {} instances of the filter operator", filterExecutionJobVertex.getTaskVertices().length);
		}

		Configuration sourceConfiguration = sourceOperator.getJobVertex().getConfiguration();
		StreamConfig sourceStreamConfig = new StreamConfig(sourceConfiguration);
		List<StreamEdge> outEdges = sourceStreamConfig.getOutEdges(executionGraph.getUserClassLoader());

		LOG.debug("Found outEdges for SourceNode: {}", Joiner.on(",").join(outEdges));

		Configuration configuration = filterExecutionJobVertex.getJobVertex().getConfiguration();
		StreamConfig filterStreamConfig = new StreamConfig(configuration);

		filterStreamConfig.setOperatorName("NewFilterFUnctionConfigName");
		filterStreamConfig.setNonChainedOutputs(sourceStreamConfig.getNonChainedOutputs(executionGraph.getUserClassLoader()));
		filterStreamConfig.setOutEdges(sourceStreamConfig.getOutEdges(executionGraph.getUserClassLoader()));
		filterStreamConfig.setVertexID(42);
		try {
			filterStreamConfig.setTypeSerializerIn1(BasicTypeInfo.LONG_TYPE_INFO.createSerializer(executionGraph.getJobInformation().getSerializedExecutionConfig().deserializeValue(executionGraph.getUserClassLoader())));
		} catch (IOException | ClassNotFoundException e) {
			executionGraph.failGlobal(e);
			e.printStackTrace();
		}
		filterStreamConfig.setTypeSerializerOut(sourceStreamConfig.getTypeSerializerOut(executionGraph.getUserClassLoader()));
		filterStreamConfig.setNumberOfInputs(1);
		filterStreamConfig.setNumberOfOutputs(1);
		filterStreamConfig.setTransitiveChainedTaskConfigs(sourceStreamConfig.getTransitiveChainedTaskConfigs(executionGraph.getUserClassLoader()));
		filterStreamConfig.setOutEdgesInOrder(sourceStreamConfig.getOutEdgesInOrder(executionGraph.getUserClassLoader()));

		filterStreamConfig.setChainedOutputs(sourceStreamConfig.getChainedOutputs(executionGraph.getUserClassLoader()));
		filterStreamConfig.setStreamOperator(new StreamFilter<>(clean(new FilterFunction<Long>() {
			@Override
			public boolean filter(Long value) throws Exception {
				return value % 2 == 0;
			}
		})));

		for (ExecutionVertex executionVertex : filterExecutionJobVertex.getTaskVertices()) {
			boolean successful = executionVertex.getCurrentExecutionAttempt()
				.scheduleForExecution(
					executionGraph.getSlotProvider(),
					executionGraph.isQueuedSchedulingAllowed());
		}
	}

	private <F> F clean(F f) {
		ClosureCleaner.clean(f, true);
		ClosureCleaner.ensureSerializable(f);
		return f;
	}

	public String getDetails() throws JobException {

		StringBuilder currentPlan = new StringBuilder();

		currentPlan.append(executionGraph.getJsonPlan()).append("\n");

		for (ExecutionJobVertex ejv : executionGraph.getVerticesInCreationOrder()) {
			currentPlan.append(ejv.generateDebugString()).append("\n");
		}

		JobInformation jobInformation = executionGraph.getJobInformation();

		currentPlan.append("JobInfo: ").append(jobInformation).append(jobInformation.getJobConfiguration()).append("\n");

		for (Map.Entry<JobVertexID, ExecutionJobVertex> vertex : executionGraph.getTasks().entrySet()) {
			currentPlan
				.append("Vertex:")
				.append(vertex.getKey()).append(" - ")
				.append(vertex.getValue().generateDebugString())
				.append(vertex.getValue().getAggregateState())
				.append(" Outputs: ")
				.append(Arrays.toString(vertex.getValue().getProducedDataSets()))
				.append(" JobVertex: ")
				.append(vertex.getValue().getJobVertex())
				.append(" Inputs: ")
				.append(Joiner.on(",").join(vertex.getValue().getInputs()))
				.append(" IntermediateResultPartition: ");

			for (IntermediateResult result : vertex.getValue().getInputs()) {
				for (IntermediateResultPartition partition : result.getPartitions()) {
					currentPlan
						.append(" PartitionId: ")
						.append(partition.getPartitionId())
						.append(" PartitionNumber: ")
						.append(partition.getPartitionNumber())
						.append("\n");
				}
			}

			currentPlan.append("\n");
		}

		for (Map.Entry<ExecutionAttemptID, Execution> exec : executionGraph.getRegisteredExecutions().entrySet()) {
			currentPlan.append(exec.getKey())
				.append(exec.getValue())
				.append(exec.getValue().getVertexWithAttempt())
				.append(exec.getValue().getAssignedResourceLocation())
				.append(" InvokableName: ")
				.append(exec.getValue().getVertex().getJobVertex().getJobVertex().getInvokableClassName())
				.append("\n");
		}

		currentPlan.append("numVerticesTotal: ").append(executionGraph.getTotalNumberOfVertices());
		currentPlan.append("finishedVertices: ").append(executionGraph.getVerticesFinished());

		return currentPlan.toString();
	}

	public ExecutionJobVertex findFilter() {

		ExecutionJobVertex executionJobVertex = null;

		for (ExecutionJobVertex ejv : executionGraph.getVerticesInCreationOrder()) {
			// TODO Masterthesis Currently hardcoded
			if (ejv.getJobVertex().getName().toLowerCase().contains("filter")) {
				executionJobVertex = ejv;
			}
		}

		if (executionJobVertex == null) {
			executionGraph.failGlobal(new ExecutionGraphException("Could not find filter"));
			throw new RuntimeException("Could not find filter");
		} else {
			return executionJobVertex;
		}
	}

	public ExecutionJobVertex findSource() {

		ExecutionJobVertex executionJobVertex = null;

		for (ExecutionJobVertex ejv : executionGraph.getVerticesInCreationOrder()) {
			if (ejv.getJobVertex().getName().toLowerCase().contains("source")) {
				executionJobVertex = ejv;
			}
		}

		if (executionJobVertex == null) {
			executionGraph.failGlobal(new ExecutionGraphException("Could not find Source"));
		}

		List<IntermediateDataSet> producedDataSets = executionJobVertex.getJobVertex().getProducedDataSets();

		if (producedDataSets.size() != 1) {
			executionGraph.failGlobal(new ExecutionGraphException("Source has not one producing output dataset"));
		}

		return executionJobVertex;
	}

	public ExecutionJobVertex findMap() {

		ExecutionJobVertex executionJobVertex = null;

		for (ExecutionJobVertex ejv : executionGraph.getVerticesInCreationOrder()) {
			if (ejv.getJobVertex().getName().toLowerCase().contains("map")) {
				executionJobVertex = ejv;
			}
		}

		if (executionJobVertex == null) {
			executionGraph.failGlobal(new ExecutionGraphException("Could not find map"));
			return null;
		}

		List<JobEdge> producedDataSets = executionJobVertex.getJobVertex().getInputs();

		if (producedDataSets.size() != 1) {
			executionGraph.failGlobal(new ExecutionGraphException("Map has not one consuming input dataset"));
		}

		return executionJobVertex;
	}

	public ExecutionJobVertex findSink() {

		ExecutionJobVertex sink = null;

		for (ExecutionJobVertex ejv : executionGraph.getVerticesInCreationOrder()) {
			if (ejv.getJobVertex().getName().toLowerCase().contains("sink")) {
				sink = ejv;
			}
		}

		if (sink == null) {
			executionGraph.failGlobal(new ExecutionGraphException("Could not find map"));
			return null;
		} else {
			return sink;
		}
	}

	private ExecutionJobVertex buildFilterExecutionJobVertex(ExecutionJobVertex source, int parallelism) {

		String operatorName = "IntroducedFilterOperator";

		JobVertex filterJobVertex = new JobVertex(operatorName, new JobVertexID());
		filterJobVertex.setInvokableClass(OneInputStreamTask.class);

		LOG.info("Creating new operator '{}' with parallelism {}", operatorName, parallelism);

		IntermediateDataSet filterIDS = filterJobVertex.createAndAddResultDataSet(new IntermediateDataSetID(), ResultPartitionType.PIPELINED);

		List<IntermediateDataSet> sourceProducedDatasets = source.getJobVertex().getProducedDataSets();

		if (sourceProducedDatasets.size() != 1) {
			executionGraph.failGlobal(new IllegalStateException("Source has more than one producing dataset"));
			throw new IllegalStateException("Source has more than one producing dataset");
		}

		IntermediateDataSet sourceProducedDataset = sourceProducedDatasets.get(0);

		// Connect source IDS as input for FilterOperator
		filterJobVertex.connectDataSetAsInput(sourceProducedDataset, DistributionPattern.ALL_TO_ALL);

		try {
			ExecutionJobVertex vertex =
				new ExecutionJobVertex(executionGraph,
					filterJobVertex,
					parallelism,
					rpcCallTimeout,
					executionGraph.getGlobalModVersion(),
					System.currentTimeMillis());

			vertex.connectToPredecessorsRuntime(executionGraph.getIntermediateResults());

			ExecutionJobVertex previousTask = executionGraph.getTasks().putIfAbsent(filterJobVertex.getID(), vertex);
			if (previousTask != null) {
				throw new JobException(String.format("Encountered two job vertices with ID %s : previous=[%s] / new=[%s]",
					filterJobVertex.getID(), vertex, previousTask));
			}

			// Add IntermediateResult to ExecutionGraph
			for (IntermediateResult res : vertex.getProducedDataSets()) {

				LOG.debug("Adding IntermediateResult {} to ExecutionGraph", res);

				IntermediateResult previousDataSet = executionGraph.getIntermediateResults().putIfAbsent(res.getId(), res);
				if (previousDataSet != null) {
					throw new JobException(String.format("Encountered two intermediate data set with ID %s : previous=[%s] / new=[%s]",
						res.getId(), res, previousDataSet));
				}
			}

			executionGraph.getVerticesInCreationOrder().add(vertex);
			executionGraph.addNumVertices(vertex.getParallelism());

			// TODO Masterthesis Job specific, that filter is introduce between source and map
			// Clear all current inputs and add new inputs
			ExecutionJobVertex map = findMap();
			map.getJobVertex().getInputs().clear();
			map.getJobVertex().connectDataSetAsInput(filterIDS, DistributionPattern.ALL_TO_ALL);
			try {
				map.connectToPredecessors(executionGraph.getAllIntermediateResults());
			} catch (JobException e) {
				executionGraph.failGlobal(e);
				LOG.error("Failed to connectToPredecessors.", e);
			}

			return vertex;
		} catch (JobException jobException) {
			executionGraph.failGlobal(jobException);
			return null;
		}
	}
}
