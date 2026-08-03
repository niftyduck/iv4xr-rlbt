package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import burlap.behavior.singleagent.Episode;
import burlap.mdp.auxiliary.DomainGenerator;
import burlap.mdp.singleagent.SADomain;
import burlap.statehashing.simple.SimpleHashableStateFactory;
import eu.fbk.iv4xr.minecraftlib.MinecraftEnv;
import eu.fbk.iv4xr.rlbt.QLearningRL;
import eu.fbk.iv4xr.rlbt.RlbtMain;
import eu.fbk.iv4xr.rlbt.configuration.BurlapConfiguration;
import eu.fbk.iv4xr.rlbt.configuration.MinecraftConfiguration;
import eu.fbk.iv4xr.rlbt.distance.NoStateSimilarity;
import eu.fbk.iv4xr.rlbt.utils.SerializationUtil;
import eu.iv4xr.framework.spatial.Vec3;

public class MineAgent {
	// Predefined defaults
	static String defaultTestbenchUrl = "http://localhost:3000";
	static String defaultLevelCsv = "sut/minecraft/mineflayer-testbench/examples/arena.csv";
	static String defaultGameMode = "training";

	static BurlapConfiguration burlapConfiguration = new BurlapConfiguration();
	static MinecraftConfiguration mineConfiguration = new MinecraftConfiguration();

	static String currentDir = System.getProperty("user.dir");
	static String outputDir = currentDir + File.separator + "rlbt-files" + File.separator + "minecraft-results";
	public static long systemtime = System.nanoTime();

	private static void executeDeepQLearningTrainingOnMinecraft(String testbenchUrl, String levelCsv) throws InterruptedException, FileNotFoundException {
		MinecraftEnv minecraftEnv = new MinecraftEnv(testbenchUrl);
		initializeLevel(testbenchUrl, levelCsv, minecraftEnv);

		DomainGenerator mcDomainGenerator = new MinecraftDomainGenerator();
		final SADomain domain = (SADomain) mcDomainGenerator.generateDomain();
	}

	private static List<Episode> executeQLearningTrainingOnMinecraft(String testbenchUrl, String levelCsv) throws InterruptedException, FileNotFoundException {
		MinecraftEnv minecraftEnv = new MinecraftEnv(testbenchUrl);
		initializeLevel(testbenchUrl, levelCsv, minecraftEnv);
		DomainGenerator mcDomainGenerator = new MinecraftDomainGenerator();
		final SADomain domain = (SADomain) mcDomainGenerator.generateDomain();

		MinecraftRLEnvironment mcRlEnvironment = new MinecraftRLEnvironment(minecraftEnv, mineConfiguration);

		File sessionDir = new File(outputDir);
		if (!sessionDir.exists() && !sessionDir.mkdirs()) {
			throw new RuntimeException("Unable to create output directory: " + sessionDir);
		}
		System.out.println("Writing results to: " + sessionDir);

		// Get number of episodes from burlap_minecraft.config
		int numEpisodes = (int)burlapConfiguration.getParameterValue("burlap.num_of_episodes");
		System.out.println("Episodes: " + numEpisodes);

		// The reward type is always CuriosityDriven (not implemented Sparse)
		double epsilonval = (double)burlapConfiguration.getParameterValue("burlap.qlearning.epsilonval");
		double calculatedDecayVal = (double)(epsilonval/numEpisodes);
		calculatedDecayVal=calculatedDecayVal/2;
		burlapConfiguration.setParameterValue("burlap.qlearning.decayedepsilonstep", Double.toString(calculatedDecayVal));  // set calculated decayed value according to number of episodes
		System.out.println("epsilon val ="+epsilonval+ "  decay = "+calculatedDecayVal);

		QLearningRL agent = new QLearningRL(domain,
				(double)burlapConfiguration.getParameterValue("burlap.qlearning.gamma"),
				new SimpleHashableStateFactory(),
				(double)burlapConfiguration.getParameterValue("burlap.qlearning.qinit"),
				(double)burlapConfiguration.getParameterValue("burlap.qlearning.lr"),
				epsilonval,
				(double)burlapConfiguration.getParameterValue("burlap.qlearning.decayedepsilonstep"),
				numEpisodes,
				new NoStateSimilarity());

		List<Episode> episodes = new ArrayList<Episode>(numEpisodes);
		List<Long> episodeTime = new ArrayList<Long>(numEpisodes);

		// the three already-formatted coverage cells of each episode, see below
		List<String> episodeCoverage = new ArrayList<String>(numEpisodes);
		int maxActionsPerEpisode = mcRlEnvironment.maxActionsPerEpisode();

		/*------------Training - start running episodes------------------------*/
		try (EpisodeLogger log = new EpisodeLogger(sessionDir)) {
			mcRlEnvironment.setLogger(log);

			mcRlEnvironment.startAgentEnvironment();
			for (int i = 0; i < numEpisodes; i++) {
				System.out.println("---------------- Episode " + (i + 1) + "/" + numEpisodes + " ----------------");
				long startTime = System.currentTimeMillis();
				episodes.add(agent.runLearningEpisode(mcRlEnvironment, maxActionsPerEpisode));
				long elapsed = System.currentTimeMillis() - startTime;
				episodeTime.add(elapsed);
				System.out.println("Time for this episode: " + elapsed + " ms");

				/* Read the episode coverage here and not after the loop: the
				 * reset below starts the next episode, which clears it. */
				episodeCoverage.add(mcRlEnvironment.episodeOwnHpCoverage() + ","
						+ mcRlEnvironment.episodeMobHpCoverage() + ","
						+ mcRlEnvironment.episodeActionsCoverage());

				// Rebuilds the arena through the testbench and re-equips the sword.
				long resetStart = System.currentTimeMillis();
				mcRlEnvironment.resetEnvironment();
				System.out.println("Time for the reset: " + (System.currentTimeMillis() - resetStart) + " ms");
			}
		}

		saveSession(agent, episodes, episodeTime, episodeCoverage, mcRlEnvironment, sessionDir);
		return episodes;
	}


	/** Write everything the session produced next to the CSVs
	 *  the environment has already been filling in. */
	private static void saveSession(QLearningRL agent, List<Episode> episodes, List<Long> episodeTime,
			List<String> episodeCoverage, MinecraftRLEnvironment env, File sessionDir)
			throws FileNotFoundException {

		agent.printFinalQtable(System.out);

		// .ser to reload it (testing mode, or resuming), .txt to read it
		String qtableFile = sessionDir + File.separator + "qtable.ser";
		agent.serializeQTable(qtableFile);
		agent.printFinalQtable(new PrintStream(qtableFile + ".txt"));

		SerializationUtil.serializeEpisodes(episodes, sessionDir + File.separator + "episode");

		writeEpisodeSummary(episodes, episodeTime, episodeCoverage,
				new File(sessionDir, "episodeSummary.txt"));
		writeSummaryTxt(env, episodes.size(), new File(sessionDir, "summary.txt"));

		System.out.println("Session written to: " + sessionDir);
	}


	/** Per-episode figures: actions taken, total reward, wall-clock time and the
	 * three simple coverage metrics */
	private static void writeEpisodeSummary(List<Episode> episodes, List<Long> episodeTime,
			List<String> episodeCoverage, File outFile) throws FileNotFoundException {
		try (PrintStream ps = new PrintStream(outFile)) {
			ps.println("episode,actions,total_reward,time_ms,own_hp_cov,mob_hp_cov,actions_cov");
			for (int i = 0; i < episodes.size(); i++) {
				Episode e = episodes.get(i);
				double totalReward = 0;
				for (Double r : e.rewardSequence)
					totalReward += r;
				ps.println((i + 1) + "," + e.actionSequence.size() + "," + totalReward + ","
						+ episodeTime.get(i) + "," + episodeCoverage.get(i));
			}
		}
	}

	/** The combat summary, in the same shape as MineAgentBaseline's summary.txt so
	 * the two runs can be compared field by field */
	private static void writeSummaryTxt(MinecraftRLEnvironment env, int numEpisodes, File outFile)
			throws FileNotFoundException {
		try (PrintStream ps = new PrintStream(outFile)) {
			ps.print(env.summary());
			// the count comes from here, not from the environment: see the note on
			// MinecraftRLEnvironment.summary()
			ps.println("Episodes: " + numEpisodes);
		}
		System.out.println("Successfully wrote summary to: " + outFile);
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

		Map<String, Vec3> tags = env.buildLevel(levelCsv, 0, 65, 0);
		System.out.println("Arena built. Tags: " + tags);
	}

	private static String normalizeMode(String mode) {
		return mode.endsWith("Mode") ? mode.substring(0, mode.length() - "Mode".length()) : mode;
	}

	/**
	 * Connect to a running MineflayerTestbench server and build the arena level.
	 * @param args [0] = testbench URL (default localhost:3000),
	 *             [1] = level csv path (default the arena example),
	 *             [2] = mode (training, testing, random; the "...Mode" spelling
	 *                   used by RlbtLauncher is accepted too),
	 *             [3] = BURLAP config file (optional; without it the in-code
	 *                   defaults of BurlapConfiguration apply)
	 *             [4] = Minecraft SUT config file, i.e. mineAgent.config
	 *                   (optional; without it the in-code defaults of
	 *                   MinecraftConfiguration apply)
	 */
	public static void main(String[] args) throws FileNotFoundException, InterruptedException {
		MineAgent main = new MineAgent();

		// Get the testbench URL and level CSV path from command line arguments or use defaults
		String testbenchUrl = args.length > 0 ? args[0] : defaultTestbenchUrl;
		String levelCsv = args.length > 1 ? args[1] : defaultLevelCsv;
		String mode = normalizeMode(args.length > 2 ? args[2] : defaultGameMode);

		// outputDir = rlbt-files/minecraft-results/<level>/rlbt/<systemtime>
		String levelName = new File(levelCsv).getName().replaceFirst("\\.csv$", "");
		outputDir = outputDir + File.separator + levelName + File.separator + "rlbt"
				+ File.separator + systemtime;

		// Load the BURLAP parameters from file
		if (args.length > 3 && args[3] != null) {
			System.out.println("Loading BURLAP configuration: " + args[3]);
			if (!burlapConfiguration.updateParameters(args[3]))
				throw new RuntimeException("Cannot load BURLAP configuration " + args[3]);
		}

		// Load the Minecraft SUT parameters (episode budgets) from file
		if (args.length > 4 && args[4] != null) {
			System.out.println("Loading Minecraft configuration: " + args[4]);
			if (!mineConfiguration.updateParameters(args[4]))
				throw new RuntimeException("Cannot load Minecraft configuration " + args[4]);
		}

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
