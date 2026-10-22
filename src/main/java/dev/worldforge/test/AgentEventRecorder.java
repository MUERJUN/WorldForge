package dev.worldforge.test;

import dev.worldforge.ai.AgentOrchestrator;
import dev.worldforge.ops.ToolEvent;
import dev.worldforge.ops.ToolEventStatus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records everything the agent reported, so a test can assert on the REAL
 * event stream instead of on chat text.
 *
 * <p>Shared by the flow tests so they all see the same event shape.</p>
 */
public final class AgentEventRecorder implements AgentOrchestrator.AgentEventSink {

	private final List<ToolEvent> events = new CopyOnWriteArrayList<>();
	private final List<String> messages = new CopyOnWriteArrayList<>();
	private final List<String> states = new CopyOnWriteArrayList<>();

	@Override
	public void onMessage(String conversationId, String role, String text, String taskId) {
		messages.add(role + ": " + text);
	}

	@Override
	public void onToolEvent(ToolEvent event) {
		events.add(event);
	}

	@Override
	public void onStatus(String conversationId, String state, String detail, String taskId) {
		states.add(state + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"));
	}

	public List<ToolEvent> events() {
		return events;
	}

	public List<String> messages() {
		return messages;
	}

	/** The task states that were reported, in order. */
	public List<String> states() {
		return states;
	}

	/** The last reported state, or null. */
	public String lastState() {
		return states.isEmpty() ? null : states.get(states.size() - 1);
	}

	/** The event of a tool, or null. */
	public ToolEvent eventOf(String tool) {
		for (ToolEvent e : events) {
			if (tool.equals(e.toolName())) {
				return e;
			}
		}
		return null;
	}

	/** The final event of a tool, or null. */
	public ToolEvent finishedEventOf(String tool) {
		ToolEvent found = null;
		for (ToolEvent e : events) {
			if (tool.equals(e.toolName())
					&& (e.status() == ToolEventStatus.SUCCEEDED
							|| e.status() == ToolEventStatus.FAILED
							|| e.status() == ToolEventStatus.CANCELLED)) {
				found = e;
			}
		}
		return found;
	}

	/** True when the given state was reported at least once. */
	public boolean sawState(String state) {
		return states.stream().anyMatch(s -> s.equals(state) || s.startsWith(state + " ("));
	}

	/** A compact dump for failure diagnostics. */
	public String dump() {
		StringBuilder sb = new StringBuilder();
		for (ToolEvent e : events) {
			sb.append(" | ").append(e.status()).append(" ").append(e.toolName())
					.append(" -> ").append(e.summary());
			if (!e.errorCode().isBlank()) {
				sb.append(" (").append(e.errorCode()).append(')');
			}
		}
		for (String m : messages) {
			sb.append(" | ").append(m);
		}
		for (String st : states) {
			sb.append(" | state ").append(st);
		}
		return sb.toString();
	}
}
