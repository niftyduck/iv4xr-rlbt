package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;

import burlap.behavior.singleagent.Episode;
import burlap.mdp.auxiliary.DomainGenerator;
import burlap.mdp.singleagent.SADomain;
import eu.fbk.iv4xr.minecraftlib.MinecraftEnv;
import eu.fbk.iv4xr.rlbt.RlbtMain;
import eu.fbk.iv4xr.rlbt.configuration.BurlapConfiguration;
import eu.fbk.iv4xr.rlbt.labrecruits.LabRecruitsDomainGenerator;
import eu.iv4xr.framework.spatial.Vec3;

public class MineAgent {

	// Predefined defaults
	static String defaultTestbenchUrl = "http://localhost:3000";
	static String defaultLevelCsv = "sut/minecraft/mineflayer-testbench/examples/arena.csv";
	static String defaultGameMode = "training";

	static BurlapConfiguration burlapConfiguration = new BurlapConfiguration();

	private static void executeDeepQLearningTrainingOnMinecraft(String testbenchUrl, String levelCsv) throws InterruptedException, FileNotFoundException {
		MinecraftEnv minecraftEnv = new MinecraftEnv(testbenchUrl);
		initializeLevel(testbenchUrl, levelCsv, minecraftEnv);

		DomainGenerator mcDomainGenerator = new MinecraftDomainGenerator();
		final SADomain domain = (SADomain) mcDomainGenerator.generateDomain();

	}

	private static void executeQLearningTrainingOnMinecraft(String testbenchUrl, String levelCsv) throws InterruptedException, FileNotFoundException {
		System.out.println("-------------------------- !QLEARNING HAS NOT BEEN IMPLEMENTED YET! --------------------------");
	}

	private void executeTraining(String testbenchUrl, String levelCsv) throws FileNotFoundException, InterruptedException {
		System.out.println("-------------------------- Starting Training on Minecraft ---------------------");
		String alg = (String)burlapConfiguration.getParameterValue("burlap.algorithm");

		if (alg.equalsIgnoreCase(RlbtMain.BurlapAlgorithm.QLearning.toString()))
			executeQLearningTrainingOnMinecraft(testbenchUrl, levelCsv);
		else if (alg.equalsIgnoreCase(RlbtMain.BurlapAlgorithm.DeepQLearning.toString()))
			executeDeepQLearningTrainingOnMinecraft(testbenchUrl, levelCsv);
		else
			throw new RuntimeException("Algorithm "+alg+" not supported");
	}

	public void executeTesting(String testbenchUrl, String levelCsv) {
		System.out.println("-------------------------- !TESTING HAS NOT BEEN IMPLEMENTED YET! --------------------------");
	}


	/**
	 * Connect to the testbench and build the level
	 * @param testbenchUrl url of the testbench
	 * @param levelCsv path of the level
	 */
	private static void initializeLevel(String testbenchUrl, String levelCsv, MinecraftEnv env) {
		String levelPath = new File(levelCsv).getAbsolutePath();

		System.out.println("Connecting to MineflayerTestbench at " + testbenchUrl);
		System.out.println("Building level: " + levelPath);

		Map<String, Vec3> tags = env.buildLevel(levelPath, 16, 65, 0);
		System.out.println("Arena built. Tags: " + tags);
	}

	/**
	 * Connect to a running MineflayerTestbench server and build the arena level.
	 * @param args [0] = testbench URL (default localhost:3000),
	 *             [1] = level csv path (default the arena example),
	 *             [2] = mode (training, testing, random)
	 */
	public static void main(String[] args) throws FileNotFoundException, InterruptedException {
		MineAgent main = new MineAgent();

		// Get the testbench URL and level CSV path from command line arguments or use defaults
		String testbenchUrl = args.length > 0 ? args[0] : defaultTestbenchUrl;
		String levelCsv = args.length > 1 ? args[1] : defaultLevelCsv;
		String mode = args.length > 2 ? args[2] : defaultGameMode;

		// TODO: add baseline choice here (or in command line!)
		switch(mode) {
			case "training":
				main.executeTraining(testbenchUrl, levelCsv);
				break;
			case "testing":
				main.executeTesting(testbenchUrl, levelCsv);
				break;
			default:
				// random: 0 = training, 1 = testing
				int randomMode = new java.util.Random().nextInt(2);
                if (randomMode == 0)
                    main.executeTraining(testbenchUrl, levelCsv);
                else
                    main.executeTesting(testbenchUrl, levelCsv);
                break;
		}




	}
}
