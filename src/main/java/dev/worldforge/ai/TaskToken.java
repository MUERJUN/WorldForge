package dev.worldforge.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The identity and the cancel token of ONE agent task.
 *
 * <p>v0.7.1: every task owns its own token. A task may only touch state that
 * belongs to it, and only a task that still owns the session may clear the
 * session's busy state. That is what stops a slow, cancelled task from
 * stomping on the state of the task that replaced it.</p>
 *
 * <p>The token also carries the CANCELLATION HOOKS: a tool that starts
 * something expensive (a Gradle build) registers how to stop it, so a player
 * pressing "cancel" really terminates the work instead of only changing a
 * flag.</p>
 */
public final class TaskToken implements dev.worldforge.ops.ToolTaskHandle {

	/** The lifecycle of a task. */
	public enum State {
		/** the task is running */
		RUNNING,
		/** a cancellation was requested; the task is unwinding */
		CANCELLING,
		/** the task was cancelled and will not do anything else */
		CANCELLED,
		/** the task finished (successfully or not) and is final */
		FINISHED
	}

	private final String taskId;
	private final String conversationId;
	private final java.util.UUID playerId;
	private final long generation;
	private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
	private final List<Runnable> cancelHooks = new CopyOnWriteArrayList<>();
	private final AtomicReference<java.util.concurrent.CompletableFuture<?>> modelRequest =
			new AtomicReference<>();
	private volatile String statusDetail = "";

	public TaskToken(String taskId, String conversationId, java.util.UUID playerId, long generation) {
		this.taskId = taskId == null ? "" : taskId;
		this.conversationId = conversationId == null ? "" : conversationId;
		this.playerId = playerId;
		this.generation = generation;
	}

	@Override
	public String taskId() {
		return taskId;
	}

	public String conversationId() {
		return conversationId;
	}

	public java.util.UUID playerId() {
		return playerId;
	}

	/** The generation of the task inside its conversation; higher is newer. */
	public long generation() {
		return generation;
	}

	public State state() {
		return state.get();
	}

	/** True once a cancellation was requested (or the task was cancelled). */
	@Override
	public boolean isCancelled() {
		State s = state.get();
		return s == State.CANCELLING || s == State.CANCELLED;
	}

	/**
	 * Requests cancellation.
	 *
	 * <p>Idempotent: only the FIRST request changes the state, and only the
	 * first request runs the cancel hooks. A task that has already finished is
	 * NOT turned into a cancelled task — an action that already happened cannot
	 * be taken back, and saying otherwise would be a lie.</p>
	 *
	 * @return true when this call started the cancellation
	 */
	public boolean requestCancel() {
		if (!state.compareAndSet(State.RUNNING, State.CANCELLING)) {
			return false;
		}
		runCancelHooks();
		return true;
	}

	/**
	 * Marks the task as finished.
	 *
	 * @return true when this call performed the transition
	 */
	public boolean finish() {
		return state.compareAndSet(State.RUNNING, State.FINISHED)
				|| state.compareAndSet(State.CANCELLING, State.CANCELLED);
	}

	/**
	 * Registers how to stop something this task started.
	 *
	 * <p>Registered hooks run once, when the cancellation is requested. A hook
	 * that is registered AFTER the cancellation already happened runs
	 * immediately — otherwise a tool that starts its work late would never be
	 * stopped.</p>
	 */
	@Override
	public void onCancel(Runnable action) {
		if (action == null) {
			return;
		}
		if (isCancelled()) {
			runSafely(action);
			return;
		}
		cancelHooks.add(action);
		if (isCancelled() && cancelHooks.remove(action)) {
			// the cancellation won the race: run it now
			runSafely(action);
		}
	}

	private void runCancelHooks() {
		for (Runnable hook : cancelHooks) {
			runSafely(hook);
		}
		cancelHooks.clear();
	}

	private static void runSafely(Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			dev.worldforge.WorldForge.LOGGER.warn("[WorldForge] cancel hook failed: {}", t.toString());
		}
	}

	/**
	 * Registers the in-flight model request so it can be aborted.
	 *
	 * <p>Cancelling the future interrupts the HTTP call, so a cancelled task
	 * does not keep a socket (and a provider thread) busy.</p>
	 */
	public void setModelRequest(java.util.concurrent.CompletableFuture<?> future) {
		modelRequest.set(future);
		if (isCancelled() && future != null) {
			future.cancel(true);
		}
	}

	/** Cancels the in-flight model request, if any. */
	public void cancelModelRequest() {
		var f = modelRequest.getAndSet(null);
		if (f != null) {
			f.cancel(true);
		}
	}

	/** A short human-readable status for the chat. */
	public String statusDetail() {
		return statusDetail;
	}

	public void setStatusDetail(String detail) {
		this.statusDetail = detail == null ? "" : detail;
	}

	@Override
	public String toString() {
		return "TaskToken{" + taskId + " gen=" + generation + " " + state.get() + "}";
	}
}
