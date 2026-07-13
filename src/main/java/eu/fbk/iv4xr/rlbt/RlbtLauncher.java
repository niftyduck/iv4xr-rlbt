package eu.fbk.iv4xr.rlbt;

import eu.fbk.iv4xr.rlbt.minecraft.MineAgent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

public class RlbtLauncher {

	private static final String DEFAULT_GAME_CONFIG =
			System.getProperty("user.dir") + File.separator
			+ "src/test/resources/configurations/game.config";

	public static void main(String[] args) throws Exception {
		String gameName = "LabRecruits";
		String gameConfigFile = DEFAULT_GAME_CONFIG;

		for (int i = 0; i < args.length; i++) {
			if ("-game".equals(args[i]) && i + 1 < args.length) {
				gameName = args[++i];
			} else {
				gameConfigFile = args[i];
			}
		}

		Properties gameConfig = new Properties();
		try (InputStream in = new FileInputStream(gameConfigFile)) {
			gameConfig.load(in);
		}

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
				throw new IllegalArgumentException("Unknown game: " + gameName + ". Use -game LabRecruits or -game Minecraft");
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
		String levelPath = new File(mineConfig.getProperty("mine.level")).getAbsolutePath();
		String testbenchUrl = mineConfig.getProperty("mine.testbenchUrl", "http://localhost:3000");

		String npm = System.getProperty("os.name").toLowerCase().contains("win") ? "npm.cmd" : "npm";
		File workDir = new File("sut/minecraft/mineflayer-testbench");

		ProcessBuilder pb = new ProcessBuilder(
				List.of(npm, "run", "start", "address=" + address));
		pb.directory(workDir);
		pb.inheritIO();

		System.out.println("Starting mineflayer-testbench (server mode): address=" + address);
		Process testbench = pb.start();

		try {
			waitForTestbench(testbenchUrl, 60);
			MineAgent.main(new String[] { testbenchUrl, levelPath });
		} finally {
			// npm spawns node as a child process: kill the whole tree
			testbench.descendants().forEach(ProcessHandle::destroy);
			testbench.destroy();
		}
	}

	/**
	 * Poll the testbench /status endpoint until it answers or the timeout expires.
	 */
	private static void waitForTestbench(String url, int timeoutSeconds) throws Exception {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url + "/status"))
				.timeout(Duration.ofSeconds(2))
				.GET().build();

		long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			try {
				if (http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() < 500) {
					return;
				}
			} catch (Exception e) {
				// not up yet, retry
			}
			Thread.sleep(1000);
		}
		throw new IllegalStateException("MineflayerTestbench not reachable at " + url
				+ " after " + timeoutSeconds + "s");
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
