package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.File;
import java.io.FileNotFoundException;
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
import eu.fbk.iv4xr.rlbt.distance.NoStateSimilarity;
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


	/**
	 * Q-learning training on Minecraft, smoke-run version.
	 *
	 * Deliberately the shortest thing that exercises the whole stack end to end,
	 * because nothing above MinecraftEnv has ever run against the real SUT: no
	 * Q-table/episode saving, no coverage, no summary. Those come back with step
	 * 8 proper, once this has produced the numbers step 7 needs -- cost of a
	 * reset, how often getMobHealth times out, whether MAX_TICKS_PER_ACTION is
	 * anywhere near right.
	 *
	 * Modelled on RlbtMain:63-134, minus everything that only matters once the
	 * run is known to work.
	 */
	private static List<Episode> executeQLearningTrainingOnMinecraft(String testbenchUrl, String levelCsv) throws InterruptedException, FileNotFoundException {
		// one single MinecraftEnv: it carries the tag cache buildLevel fills in,
		// and the environment needs it to resolve the mob (PROJECT.md §2.2)
		MinecraftEnv minecraftEnv = new MinecraftEnv(testbenchUrl);
		initializeLevel(testbenchUrl, levelCsv, minecraftEnv);
		DomainGenerator mcDomainGenerator = new MinecraftDomainGenerator();
		final SADomain domain = (SADomain) mcDomainGenerator.generateDomain();

		MinecraftRLEnvironment mcRlEnvironment = new MinecraftRLEnvironment(minecraftEnv);

		// from burlap_minecraft.config via game.mineAgentBurlapConfig; 3 while
		// this is a smoke run, raise it once the run is known to work
		int numEpisodes = (int)burlapConfiguration.getParameterValue("burlap.num_of_episodes");
		System.out.println("Episodes: " + numEpisodes);

		// The reward type is always CuriosityDriven (not implemented Sparse)
		double epsilonval = (double)burlapConfiguration.getParameterValue("burlap.qlearning.epsilonval");
		double calculatedDecayVal = (double)(epsilonval/numEpisodes);
		calculatedDecayVal=calculatedDecayVal/2;
		burlapConfiguration.setParameterValue("burlap.qlearning.decayedepsilonstep", Double.toString(calculatedDecayVal));  // set calculated decayed value according to number of episodes
		System.out.println("epsilon val ="+epsilonval+ "  decay = "+calculatedDecayVal);

		/* Same constructor as RlbtMain:84, with the two Minecraft-specific
		 * arguments (PROJECT.md §2.8, §2.9):
		 *  - SimpleHashableStateFactory, BURLAP's built-in: the feature object
		 *    holds primitive buckets, so the OO branch of the hashing works as
		 *    is. RlbtSimpleHashableState switches on LabRecruits entity types
		 *    and would make every state look different.
		 *  - NoStateSimilarity instead of JaccardDistance, which casts to
		 *    LabRecruitsState. Subsumption is not wanted here anyway: the
		 *    bucketing already generalises, merging states would collapse
		 *    bands kept apart on purpose. */
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
		int maxActionsPerEpisode = MinecraftRLEnvironment.maxActionsPerEpisode();

		/*------------Training - start running episodes------------------------*/
		// the first episode has no reset before it: this equips the sword and
		// takes the first observation, as RlbtMain does for LabRecruits
		mcRlEnvironment.startAgentEnvironment();
		for (int i = 0; i < numEpisodes; i++) {
			System.out.println("---------------- Episode " + (i + 1) + "/" + numEpisodes + " ----------------");
			long startTime = System.currentTimeMillis();
			episodes.add(agent.runLearningEpisode(mcRlEnvironment, maxActionsPerEpisode));
			System.out.println("Time for this episode: " + (System.currentTimeMillis() - startTime) + " ms");

			// rebuilds the arena through the testbench and re-equips the sword.
			// Timed separately: the cost of a reset is the one unknown left, and
			// it is what decides whether 50 episodes are affordable at all.
			long resetStart = System.currentTimeMillis();
			mcRlEnvironment.resetEnvironment();
			System.out.println("Time for the reset: " + (System.currentTimeMillis() - resetStart) + " ms");
		}

		agent.printFinalQtable(System.out);
		return episodes;
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


	/**
	 * Normalise the mode string, which reaches this class in two dialects.
	 *
	 * RlbtLauncher.toModeFlag turns game.mode into "trainingMode"/"testingMode"/
	 * "randomMode", because that is the vocabulary RlbtMain's command line wants,
	 * while this class documents and takes the bare "training"/"testing"/"random".
	 * Matching only the bare form meant every launcher-started run fell through to
	 * the default branch, i.e. the random one -- so half of them printed "testing
	 * has not been implemented" and exited. Silent, and easy to mistake for the
	 * training simply not producing output.
	 *
	 * Accepting both keeps the launcher untouched (its spelling is what
	 * LabRecruits needs) and keeps java -cp ... MineAgent training working.
	 */
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
	 */
	public static void main(String[] args) throws FileNotFoundException, InterruptedException {
		MineAgent main = new MineAgent();

		// Get the testbench URL and level CSV path from command line arguments or use defaults
		String testbenchUrl = args.length > 0 ? args[0] : defaultTestbenchUrl;
		String levelCsv = args.length > 1 ? args[1] : defaultLevelCsv;
		String mode = normalizeMode(args.length > 2 ? args[2] : defaultGameMode);

		// Load the BURLAP parameters from file, as RlbtMain does for LabRecruits.
		// Until this existed, RlbtLauncher read game.burlapConfig and dropped it on
		// the floor for Minecraft, so episodes and epsilon could only be changed by
		// editing the code.
		if (args.length > 3 && args[3] != null) {
			System.out.println("Loading BURLAP configuration: " + args[3]);
			if (!burlapConfiguration.updateParameters(args[3])) {
				throw new RuntimeException("Cannot load BURLAP configuration " + args[3]);
			}
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
