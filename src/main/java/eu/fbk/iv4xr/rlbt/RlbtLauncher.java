package eu.fbk.iv4xr.rlbt;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

public class RlbtLauncher {

	private static final String DEFAULT_GAME_CONFIG =
			System.getProperty("user.dir") + File.separator
			+ "src/test/resources/configurations/game.config";

	public static void main(String[] args) throws Exception {
		String gameConfigFile = args.length > 0 ? args[0] : DEFAULT_GAME_CONFIG;

		Properties gameConfig = new Properties();
		try (InputStream in = new FileInputStream(gameConfigFile)) {
			gameConfig.load(in);
		}

		String gameName = gameConfig.getProperty("game.name", "LabRecruits");
		String modeFlag = toModeFlag(gameConfig.getProperty("game.mode", "training"));
		String burlapConfig = gameConfig.getProperty("game.burlapConfig");

		switch (gameName) {
			case "LabRecruits":
				System.out.println("Launching LabRecruits...");
				launchLabRecruits(gameConfig, modeFlag, burlapConfig);
				break;
			case "Minecraft":
				System.out.println("Launching Minecraft...");
				launchMineAgentMain(gameConfig, burlapConfig);
				break;
			default:
				throw new IllegalArgumentException("Unknown game.name in game.config: " + gameName);
		}
	}

	private static void launchLabRecruits(Properties gameConfig, String modeFlag, String burlapConfig) throws Exception {
		boolean singleAgent = Boolean.parseBoolean(gameConfig.getProperty("game.lrSingleAgent", "true"));

		if (singleAgent) {
			String sutConfig = gameConfig.getProperty("game.lrSingleAgentSutConfig");
			RlbtMain.main(new String[] {
					"-" + modeFlag, "-burlapConfig", burlapConfig, "-sutConfig", sutConfig });
		} else {
			if (!modeFlag.equals("trainingMode")) {
				// RlbtMultiAgentMain only implements the multi-agent path for training
				// (-multiagentTrainingMode); testing/random are single-agent only there.
				throw new UnsupportedOperationException(
						"game.mode=" + modeFlag + " is not supported for multi-agent LabRecruits yet");
			}
			String sutConfig = gameConfig.getProperty("game.lrMultiAgentSutConfig");
			RlbtMultiAgentMain.main(new String[] {
					"-multiagentTrainingMode", "-burlapConfig", burlapConfig, "-sutConfig", sutConfig });
		}
	}

	private static void launchMineAgentMain(Properties gameConfig, String burlapConfig) throws Exception {
		String sutConfigPath = gameConfig.getProperty("game.mineAgentSutConfig");

		Properties mineConfig = new Properties();
		try (InputStream in = new FileInputStream(sutConfigPath)) {
			mineConfig.load(in);
		}

		String address = mineConfig.getProperty("mine.address", "127.0.0.1:25565");
		String testFile = mineConfig.getProperty("mine.test", "./arena.json");

		String npm = System.getProperty("os.name").toLowerCase().contains("win") ? "npm.cmd" : "npm";
		File workDir = new File("minecraft/mineflayer-testbench");

		ProcessBuilder pb = new ProcessBuilder(
				List.of(npm, "run", "start", "address=" + address, "test=" + testFile));
		pb.directory(workDir);
		pb.inheritIO();

		System.out.println("Starting mineflayer-testbench: address=" + address + " test=" + testFile);
		Process p = pb.start();
		int exitCode = p.waitFor();
		System.exit(exitCode);
	}

	private static String toModeFlag(String mode) {
		switch (mode) {
			case "training": return "trainingMode";
			case "testing": return "testingMode";
			case "random": return "randomMode";
			default: throw new IllegalArgumentException("Unknown game.mode in game.config: " + mode);
		}
	}

}
