package dev.worldforge.ops;

/**
 * Where a running tool reports its progress.
 *
 * <p>Implementations must be non-blocking and must never assume they are on
 * the server thread; the tool layer hops onto the server thread itself before
 * touching world state.</p>
 */
@FunctionalInterface
public interface ToolEventSink {

	/**
	 * @param event the observable fact
	 */
	void emit(ToolEvent event);

	/** A sink that throws everything away (used by read-only introspection). */
	ToolEventSink DISCARD = event -> {
	};
}
