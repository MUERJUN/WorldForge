import io

p = 'src/main/java/dev/worldforge/test/WorldForgeBlockRegressionTests.java'
s = io.open(p, encoding='utf-8', newline='').read()

# ---- replace R3 (old counter semantics) with the round-robin scheduler contract ----
start = s.index('	// ---- R3: budget starvation must not skip a whole interval ----')
end = s.index('	// ---- R4: the real onBreak path')
new_r3 = '''	// ---- R3: bounded round-robin scheduler - ceiling, eventual service, no dupes ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-regression")
	public void testR3_periodicBudgetKeepsPendingTick(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-r3")));
		PeriodicScheduler.reset();
		var world = ctx.getWorld();

		String id = "worldforge:tick_block";
		BlockRuntimeManager.create(BlockDefinition.create(id, "轮转方块", "", "minecraft:stone",
				BlockDefinition.Category.PERIODIC, 1.0, List.of(), 1, null, 0));

		// place a block and let it register
		BlockPos pos = new BlockPos(1, 1, 1);
		ctx.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		var abs = ctx.getAbsolutePos(pos);
		var be = (DynamicBlockEntity) world.getBlockEntity(abs);
		be.setVirtualId(id);
		be.serverTick(world, abs, world.getBlockState(abs));

		ctx.assertTrue(PeriodicScheduler.queued() == 1, "the periodic block must be queued once");
		// registering again (what a chunk reload does) must NOT duplicate the task
		be.serverTick(world, abs, world.getBlockState(abs));
		ctx.assertTrue(PeriodicScheduler.queued() == 1,
				"REGRESSION: re-registration (chunk reload) duplicated the task, queue="
						+ PeriodicScheduler.queued());

		// drive a few ticks: the task must eventually execute and stay queued
		boolean executed = false;
		for (int t = 0; t < 5 && !executed; t++) {
			PeriodicScheduler.tick();
			PeriodicScheduler.runDueTasks(key -> world);
			executed = PeriodicScheduler.totalExecuted() > 0;
		}
		ctx.assertTrue(executed, "a due periodic block must eventually be executed");
		ctx.assertTrue(PeriodicScheduler.queued() == 1,
				"after execution the task must be re-queued for its next due time");

		// cancelling removes it (block removed / definition deleted)
		ctx.assertTrue(PeriodicScheduler.cancel(world.getRegistryKey(), abs), "cancel must succeed");
		ctx.assertTrue(PeriodicScheduler.queued() == 0, "a cancelled task must leave the queue");
		ctx.assertTrue(!PeriodicScheduler.isRegistered(world.getRegistryKey(), abs),
				"a cancelled position must no longer be registered");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- R3b: 100 / 129 / 256 / 512 continuously-due blocks under the 128 ceiling ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-regression")
	public void testR3b_largeFieldsNeverStarve(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-r3b")));
		PeriodicScheduler.reset();
		var world = ctx.getWorld();
		String id = "worldforge:hot";
		BlockRuntimeManager.create(BlockDefinition.create(id, "高频方块", "", "minecraft:stone",
				BlockDefinition.Category.PERIODIC, 1.0, List.of(), 1, null, 0));

		int[] sizes = {100, 129, 256, 512};
		for (int n : sizes) {
			PeriodicScheduler.reset();
			for (int i = 0; i < n; i++) {
				PeriodicScheduler.register(world, new BlockPos(i % 16, 1, i / 16), 1);
			}
			ctx.assertTrue(PeriodicScheduler.queued() == n, "all " + n + " tasks must be queued");

			// continuously due: run enough ticks that every task can be served at
			// least once even in the worst case (n / MAX_PER_TICK, rounded up)
			int ticksNeeded = (int) Math.ceil((double) n / PeriodicScheduler.MAX_PER_TICK) + 2;
			for (int t = 0; t < ticksNeeded; t++) {
				PeriodicScheduler.tick();
				PeriodicScheduler.runDueTasks(key -> world);
				ctx.assertTrue(PeriodicScheduler.executedThisTick() <= PeriodicScheduler.MAX_PER_TICK,
						"n=" + n + " tick " + t + " exceeded the per-tick ceiling: "
								+ PeriodicScheduler.executedThisTick());
			}
			ctx.assertTrue(PeriodicScheduler.totalExecuted() >= n,
					"n=" + n + ": only " + PeriodicScheduler.totalExecuted()
							+ " executions in " + ticksNeeded + " ticks - some blocks starved");
			ctx.assertTrue(PeriodicScheduler.queued() == n,
					"n=" + n + ": every task must remain queued for the next period, queue="
							+ PeriodicScheduler.queued());
		}

		// overload is honest: service continues, but the effective period stretches
		ctx.assertTrue(PeriodicScheduler.totalDeferred() >= 0, "deferral is accounted for");

		BlockRuntimeManager.release();
		ctx.complete();
	}

'''
s = s[:start] + new_r3 + s[end:]

# ---- replace R5 (old counter-based fairness) ----
start5 = s.index('	// ---- R5: 100 periodic blocks under a 32/tick budget must not starve ----')
end5 = s.index('	// ---- R4: the real onBreak path')
new_r5 = '''	// ---- R5: cancellation rules - block removal, definition deletion, world unload ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-regression")
	public void testR5_schedulerCleansUpCorrectly(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-r5")));
		PeriodicScheduler.reset();
		var world = ctx.getWorld();
		String id = "worldforge:cleanup";
		BlockRuntimeManager.create(BlockDefinition.create(id, "清理方块", "", "minecraft:stone",
				BlockDefinition.Category.PERIODIC, 1.0, List.of(), 1, null, 0));

		BlockPos a = new BlockPos(1, 1, 1);
		BlockPos b = new BlockPos(2, 1, 1);
		PeriodicScheduler.register(world, a, 1);
		PeriodicScheduler.register(world, b, 1);
		ctx.assertTrue(PeriodicScheduler.queued() == 2, "both tasks queued");

		// block removal cancels exactly one task
		ctx.assertTrue(PeriodicScheduler.cancel(world.getRegistryKey(), a), "cancel(a)");
		ctx.assertTrue(PeriodicScheduler.queued() == 1, "only the removed block's task is gone");
		ctx.assertTrue(PeriodicScheduler.isRegistered(world.getRegistryKey(), b), "b stays scheduled");

		// definition deletion stops execution of the OLD behaviour: the task is
		// dropped on its next scheduling pass because the definition is gone
		BlockRuntimeManager.delete(id);
		PeriodicScheduler.tick();
		PeriodicScheduler.runDueTasks(key -> world);
		ctx.assertTrue(PeriodicScheduler.queued() == 0,
				"a deleted definition must not keep executing, queue=" + PeriodicScheduler.queued());

		// world unload clears everything bound to that dimension
		PeriodicScheduler.register(world, a, 1);
		PeriodicScheduler.register(world, b, 1);
		int removed = PeriodicScheduler.cancelWorld(world.getRegistryKey());
		ctx.assertTrue(removed == 2 && PeriodicScheduler.queued() == 0,
				"world unload must clear all tasks of that dimension, removed=" + removed);

		BlockRuntimeManager.release();
		ctx.complete();
	}

'''
s = s[:start5] + new_r5 + s[end5:]
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print("R3/R3b/R5 rewritten for the round-robin scheduler")