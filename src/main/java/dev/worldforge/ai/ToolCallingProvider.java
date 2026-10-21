package dev.worldforge.ai;

import dev.worldforge.ops.ToolDescriptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The tool-calling extension of {@link ModelProvider}.
 *
 * <p>A provider implements this when it can exchange structured tool calls.
 * Two wire formats are supported by the same interface:</p>
 * <ul>
 *   <li><b>native</b> — the OpenAI-compatible {@code tools[]} parameter with
 *       {@code message.tool_calls} in the response;</li>
 *   <li><b>structured</b> — a strict JSON envelope in the assistant message,
 *       for services without native tool calling.</li>
 * </ul>
 *
 * <p>Both produce the SAME {@link ChatTurn.Turn}, so the permission and
 * validation boundary downstream is identical either way.</p>
 */
public interface ToolCallingProvider {

	/**
	 * One turn with tools available.
	 *
	 * @param systemPrompt system instructions
	 * @param messages     the conversation so far (never contains tool results
	 *                     as system content — they are always {@code TOOL} turns)
	 * @param tools        the tools this player may call
	 * @return the model's turn
	 */
	CompletableFuture<ChatTurn.Turn> completeWithTools(String systemPrompt,
			List<ChatTurn> messages, List<ToolDescriptor> tools);
}
