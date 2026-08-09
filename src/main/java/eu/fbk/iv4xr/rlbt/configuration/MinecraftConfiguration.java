package eu.fbk.iv4xr.rlbt.configuration;

import java.util.LinkedHashMap;

/**
 * SUT configuration of the Minecraft scenario, the counterpart of
 * {@link LRConfiguration} for LabRecruits: it holds the defaults of every
 * parameter mineAgent.config may set.
 *
 * Every key that can appear in the file has to be listed here, defaults
 * included: {@link Configuration#updateParameters} refuses a property it does
 * not already know, so a missing entry makes the whole load fail.
 */
public class MinecraftConfiguration extends Configuration {

	public MinecraftConfiguration() {
		parameters = new LinkedHashMap<String, Object>();
		parameters.put("mine.address", "localhost");
		parameters.put("mine.level", "sut/minecraft/mineflayer-testbench/examples/arena.csv");
		parameters.put("mine.testbenchUrl", "http://localhost:3000");
		parameters.put("mine.max_ticks_per_action", 120);
		parameters.put("mine.max_actions_per_episode", 30);
		parameters.put("mine.mob_tag", "mob1");
		parameters.put("mine.reward_type", "CoverageOriented");
		parameters.put("mine.weapon", "iron_sword");
	}

}
