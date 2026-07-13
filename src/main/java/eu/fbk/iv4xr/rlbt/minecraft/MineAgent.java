package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;

import eu.fbk.iv4xr.minecraftlib.MinecraftEnv;
import eu.iv4xr.framework.spatial.Vec3;

public class MineAgent {

	/**
	 * Connect to a running MineflayerTestbench server and build the arena level.
	 *
	 * @param args [0] = testbench URL (default http://localhost:3000),
	 *             [1] = level csv path (default the arena example)
	 */
	public static void main(String[] args) {
		String testbenchUrl = args.length > 0 ? args[0] : "http://localhost:3000";
		String levelCsv = args.length > 1 ? args[1] : "sut/minecraft/mineflayer-testbench/examples/arena.csv";

		String levelPath = new File(levelCsv).getAbsolutePath();
		MinecraftEnv env = new MinecraftEnv(testbenchUrl);

		System.out.println("Connecting to MineflayerTestbench at " + testbenchUrl);
		System.out.println("Building level: " + levelPath);

		Map<String, Vec3> tags = env.buildLevel(levelPath, 16, 65, 0);

		System.out.println("Arena built. Tags: " + tags);
	}

}
