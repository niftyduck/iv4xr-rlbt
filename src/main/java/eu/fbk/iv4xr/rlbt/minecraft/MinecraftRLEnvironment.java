package eu.fbk.iv4xr.rlbt.minecraft;

import burlap.mdp.core.action.Action;
import burlap.mdp.core.state.State;
import burlap.mdp.singleagent.environment.Environment;
import burlap.mdp.singleagent.environment.EnvironmentOutcome;
import eu.fbk.iv4xr.minecraftlib.MinecraftEnv;
import eu.fbk.iv4xr.minecraftlib.MinecraftGoalLib;
import eu.fbk.iv4xr.minecraftlib.MinecraftState;
import eu.fbk.iv4xr.rlbt.configuration.MinecraftConfiguration;
import eu.iv4xr.framework.mainConcepts.TestAgent;
import eu.iv4xr.framework.mainConcepts.TestDataCollector;
import eu.iv4xr.framework.mainConcepts.WorldEntity;
import eu.iv4xr.framework.spatial.Vec3;
import eu.fbk.iv4xr.rlbt.minecraft.MinecraftBurlapState.HPBucket;
import nl.uu.cs.aplib.mainConcepts.GoalStructure;

import java.util.EnumSet;
import static nl.uu.cs.aplib.AplibEDSL.SEQ;

/** The BURLAP environment of the Minecraft combat scenario */
public class MinecraftRLEnvironment implements Environment {

    private final MinecraftEnv minecraftEnv;      // HTTP client, only one instance
    private final MinecraftState state;           // belief-state iv4xr
    private final TestAgent testAgent;            // minecraft agent
    private final MinecraftGoalLib goalLib;
    private final MinecraftBurlapState currentState;
    private double lastReward;
    private int updateCycles;   // budget of episode actions


    /** Episode Metrics */
    private Float lastMobHp;
    private Float lastOwnHp;
    private Integer deathsAtEpisodeStart;
    private boolean agentDied; // changed through the death event
    private int hitsAttempted;
    private int hitsLanded;
    private float totalDamageDealt;
    private float totalDamageTaken; // accumulated per TICK, not per action
    private int mobKills;
    private int agentDeaths;
    private boolean mobKilledThisEpisode;
    private float prevOwnHp;    // per-tick reference for totalDamageTaken


    /** Global coverage metrics to be reported in summary.txt */
    private final EnumSet<HPBucket> ownHPCoverage = EnumSet.noneOf(HPBucket.class);
    private final EnumSet<HPBucket> mobHPCoverage = EnumSet.noneOf(HPBucket.class);
    private final EnumSet<MinecraftAction.Command> actionsCoverage = EnumSet.noneOf(MinecraftAction.Command.class);

    private final EnumSet<HPBucket> episodeOwnHP = EnumSet.noneOf(HPBucket.class);
    private final EnumSet<HPBucket> episodeMobHP = EnumSet.noneOf(HPBucket.class);
    private final EnumSet<MinecraftAction.Command> episodeActions = EnumSet.noneOf(MinecraftAction.Command.class);


    /** State-Action coverage metrics to be reported in summary.txt:
        It simply keeps track of the triples (own HP, mob HP, action) */
    private final boolean[][][] stateActionCoverage =
            new boolean[HPBucket.values().length]
                       [HPBucket.values().length]
                       [MinecraftAction.Command.values().length];


    /** Per-session CSV writer (null when the run is not being logged) */
    private EpisodeLogger logger;
    private int episodeNumber;
    private int tickCounter;

    // TODO: the mob shouldn't be hard-coded like this, better if the player recognizes
    //  the nearest mob and automatically performs actions on it.
    private final String AGENT_ID = "Bot";
    private final String MOB_TAG = "mob1";

    /** Distance that APPROACH and RETREAT aim to reach */
    private static final double APPROACH_DISTANCE = 3.0;
    private static final double RETREAT_DISTANCE = 6.0;

    private static final double RETREAT_TOLERANCE = 1.5;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private final int maxTicksPerAction;
    private final int maxActionsPerEpisode;

    public int maxActionsPerEpisode() {
        return maxActionsPerEpisode;
    }

    // TODO: change hard-coded weapon
    private static final String WEAPON = "iron_sword";


    public MinecraftRLEnvironment(MinecraftEnv env, MinecraftConfiguration mineConfiguration) {
        maxTicksPerAction = (int) mineConfiguration.getParameterValue("mine.max_ticks_per_action");
        maxActionsPerEpisode = (int) mineConfiguration.getParameterValue("mine.max_actions_per_episode");

        minecraftEnv = env;
        state = new MinecraftState();
        goalLib = new MinecraftGoalLib();

        testAgent = new TestAgent(AGENT_ID, "tester");
        testAgent.setTestDataCollector(new TestDataCollector());

        // attaching the environment is what lets state.updateState() observe:
        // MinecraftState.updateState calls env().observe(agentId)
        testAgent.attachState(state).attachEnvironment(env);

        currentState = new MinecraftBurlapState();
        lastReward = 0;
        updateCycles = 0;
    }

    @Override
    public State currentObservation() {
        state.updateState(AGENT_ID);

        WorldEntity mob = mobEntity(MOB_TAG);

        Float ownHp = state.getHealth();
        Float mobHp = minecraftEnv.getMobHealth(MOB_TAG);
        Vec3 ownPos = state.getAgentPosition();
        Vec3 mobPos = (mob == null) ? null : mob.position;
        Double dist = distance(ownPos, mobPos);

        // WorldEntity.type holds the entity name ("zombie", ...)
        Float mobMaxHp = (mob == null) ? null : MinecraftBurlapState.maxHpForMob(mob.type);

        currentState.updateAbstraction(ownHp, mobHp, mobMaxHp, dist);
        markOwnHpAsCovered(currentState.getOwnHpBucket());
        markMobHpAsCovered(currentState.getMobHpBucket());

        // reward is computed on these, not on the buckets
        lastMobHp = mobHp;
        lastOwnHp = ownHp;

        Integer deaths = state.getDeathCount();
        if (deaths != null && deathsAtEpisodeStart != null && deaths > deathsAtEpisodeStart) {
            if (!agentDied)
                agentDeaths++;      // count the transition, not every observation after it
            agentDied = true;
            markOwnHpAsCovered(HPBucket.DEAD);
        }

        if (currentState.getMobHpBucket() == HPBucket.DEAD && !mobKilledThisEpisode) {
            mobKilledThisEpisode = true;
            mobKills++;
        }

        // a copy so that the state handed to the learner is not altered by the
        // next observation (BURLAP keeps past states inside the episodes)
        return currentState.copy();
    }

    private WorldEntity mobEntity(String tag) {
        String uuid = minecraftEnv.tagUuids.get(tag);
        if (uuid == null || state.worldmodel == null)
            return null;
        return state.worldmodel.getElement(uuid);
    }

    private static Double distance(Vec3 a, Vec3 b) {
        return (a == null || b == null) ? null : (double) Vec3.dist(a, b);
    }

    @Override
    public double lastReward() {
        return lastReward;
    }

    /** Run one action against the SUT and report what happened: observe before,
     * translate the action into an aplib goal and pursue it, observe again,
     * score the outcome. */
    @Override
    public EnvironmentOutcome executeAction(Action a) {
        MinecraftAction action = (MinecraftAction) a;
        State oldState = currentObservation();

        Float mobHpBefore = lastMobHp;
        Float ownHpBefore = lastOwnHp;
        HPBucket ownHPBucketBefore = currentState.getOwnHpBucket();
        HPBucket mobHPBucketBefore = currentState.getMobHpBucket();

        GoalStructure goal = toGoal(action);
        if (goal != null)
            runGoal(goal, maxTicksPerAction, action.actionName());

        State newState = currentObservation();
        lastReward = combatReward(mobHpBefore, ownHpBefore);
        updateCycles++; // the action consumes budget

        recordAction(action, goal, mobHpBefore, ownHpBefore, ownHPBucketBefore, mobHPBucketBefore);

        System.out.println("Action " + action.actionName()
                + " | " + oldState + " -> " + newState
                + " | goal " + (goal == null ? "NOT APPLICABLE" : goal.getStatus())
                + " | reward " + lastReward);

        return new EnvironmentOutcome(oldState, action, newState, lastReward, isInTerminalState());
    }

    /** Translate a symbolic action into the aplib goal that carries it out */
    private GoalStructure toGoal(MinecraftAction action) {
        switch (action.getCommand()) {
            case APPROACH:
                return goalLib.tagReachedWithinDistance(MOB_TAG, APPROACH_DISTANCE);
            case ATTACK: // hit then sit out the weapon cooldown
                return SEQ(goalLib.attacked(MOB_TAG), goalLib.waited(ATTACK_COOLDOWN_TICKS));
            case RETREAT:
                Vec3 target = retreatPosition();
                return (target == null) ? null : goalLib.reached(target, RETREAT_TOLERANCE);
            default:
                throw new RuntimeException("Unknown command: " + action.getCommand());
        }
    }

    private Vec3 retreatPosition() {
        WorldEntity mob = mobEntity(MOB_TAG);
        Vec3 me = state.getAgentPosition();
        if (mob == null || me == null)
            return null;

        Vec3 away = Vec3.sub(me, mob.position);
        away.y = 0;   // move on the ground plane, not into the air or the floor
        if (away.length() < 1e-3f || away.length() >= RETREAT_DISTANCE)
            return null;

        Vec3 destination = Vec3.add(mob.position,
                Vec3.mul(away.normalized(), (float) RETREAT_DISTANCE));
        destination.y = me.y;   // keep the agent's own height, the mob may be elsewhere
        return destination;
    }

    private void runGoal(GoalStructure goal, int tickBudget, String phase) {
        testAgent.setGoal(goal);
        int ticks = 0;
        while (goal.getStatus().inProgress() && ticks < tickBudget) {
            testAgent.update();   // every update() performs an action, blocking server-side
            ticks++;
            tickCounter++;
            accumulateDamageTaken();
            logTick(phase, goal.getStatus().toString());
        }
    }

    private void accumulateDamageTaken() {
        Float hp = state.getHealth();
        if (hp == null)
            return;

        markOwnHpAsCovered(MinecraftBurlapState.hpBucket(hp, MinecraftBurlapState.PLAYER_MAX_HP));

        if (prevOwnHp > hp)
            totalDamageTaken += prevOwnHp - hp;
        prevOwnHp = hp;
    }

    /** One row of ticks.csv: agent and mob telemetry */
    private void logTick(String phase, String goalStatus) {
        if (logger == null)
            return;
        WorldEntity mob = mobEntity(MOB_TAG);
        Vec3 ownPos = state.getAgentPosition();
        Vec3 mobPos = (mob == null) ? null : mob.position;
        logger.logTick(episodeNumber, tickCounter, phase, goalStatus,
                state.getHealth(), ownPos, null, mobPos, distance(ownPos, mobPos));
    }

    /** One row of actions.csv, plus the session counters that summary.txt reports. */
    private void recordAction(MinecraftAction action, GoalStructure goal,
                              Float mobHpBefore, Float ownHpBefore,
                              HPBucket ownBucketBefore, HPBucket mobBucketBefore) {

        markActionAsCovered(action.getCommand());

        HPBucket ownBucketAfter = agentDied ? HPBucket.DEAD : currentState.getOwnHpBucket();
        HPBucket mobBucketAfter = currentState.getMobHpBucket();
        markStateAction(ownBucketBefore, mobBucketBefore, action.getCommand());
        markStateAction(ownBucketAfter, mobBucketAfter, action.getCommand());

        float dealt = (mobHpBefore == null) ? 0f
                : Math.max(0f, mobHpBefore - (lastMobHp == null ? 0f : lastMobHp));
        totalDamageDealt += dealt;

        if (action.getCommand() == MinecraftAction.Command.ATTACK) {
            hitsAttempted++;
            if (dealt > 0f)
                hitsLanded++;
        }

        if (logger != null) {
            logger.logAction(episodeNumber, action.actionName(), MOB_TAG, "",
                    goal == null ? "NOT APPLICABLE" : goal.getStatus().toString(),
                    mobHpBefore, lastMobHp, mobBucketBefore, mobBucketAfter,
                    ownHpBefore, lastOwnHp, ownBucketBefore, ownBucketAfter);
        }
    }

    /** Mark one (ownHP, mobHP, action) combination as reached. */
    private void markStateAction(HPBucket own, HPBucket mob, MinecraftAction.Command command) {
        stateActionCoverage[own.ordinal()][mob.ordinal()][command.ordinal()] = true;
    }

    public void setLogger(EpisodeLogger logger) {
        this.logger = logger;
    }

    public String summary() {
        String nl = System.lineSeparator();
        float efficiency = hitsAttempted > 0 ? (float) hitsLanded / hitsAttempted : 0f;
        return "Hits attempted: " + hitsAttempted + " | Hits landed: " + hitsLanded
                    + " | Efficiency: " + efficiency + nl
             + "Total damage dealt: " + totalDamageDealt
                    + " | Total damage taken: " + totalDamageTaken
                    + " | Mob kills: " + mobKills + nl
             + "Agent deaths: " + agentDeaths + nl
             + "-----" + nl
             + "GLOBAL COVERAGE:" + nl
             + "    Global Own HP coverage: " + coverage(ownHPCoverage, HPBucket.values().length) + nl
             + "    Global Mob HP coverage: " + coverage(mobHPCoverage, HPBucket.values().length) + nl
             + "    Global Actions coverage: " + coverage(actionsCoverage, MinecraftAction.Command.values().length) + nl
             + stateActionSummary(nl);
    }

    private String stateActionSummary(String nl) {
        HPBucket[] buckets = HPBucket.values();
        MinecraftAction.Command[] commands = MinecraftAction.Command.values();
        int total = buckets.length * buckets.length * commands.length;

        int covered = 0;
        StringBuilder missing = new StringBuilder();
        for (HPBucket own : buckets) {
            for (HPBucket mob : buckets) {
                for (MinecraftAction.Command command : commands) {
                    if (stateActionCoverage[own.ordinal()][mob.ordinal()][command.ordinal()]) {
                        covered++;
                    } else {
                        if (missing.length() > 0)
                            missing.append(", ");
                        missing.append(own).append('|').append(mob).append('|').append(command);
                    }
                }
            }
        }

        int percent = Math.round(100f * covered / total);
        return "-----" + nl
             + "STATE-ACTION COVERAGE (ownHP x mobHP x action):" + nl
             + "    Total available combinations: " + buckets.length + "*" + buckets.length
                    + "*" + commands.length + " (" + total + ")" + nl
             + "    Combinations covered: " + covered + "/" + total + " (" + percent + "%)" + nl
             + "    Missing: [" + missing + "]" + nl;
    }

    private static String coverage(EnumSet<?> covered, int total) {
        int percent = (total == 0) ? 0 : Math.round(100f * covered.size() / total);
        return ratio(covered, total) + " (" + percent + "%) " + covered;
    }

    /** Mark a value as covered, in the session metric and in the episode one at once. */
    private void markOwnHpAsCovered(HPBucket bucket) {
        ownHPCoverage.add(bucket);
        episodeOwnHP.add(bucket);
    }

    private void markMobHpAsCovered(HPBucket bucket) {
        mobHPCoverage.add(bucket);
        episodeMobHP.add(bucket);
    }

    private void markActionAsCovered(MinecraftAction.Command command) {
        actionsCoverage.add(command);
        episodeActions.add(command);
    }

    public String episodeOwnHpCoverage() {
        return ratio(episodeOwnHP, HPBucket.values().length);
    }

    public String episodeMobHpCoverage() {
        return ratio(episodeMobHP, HPBucket.values().length);
    }

    public String episodeActionsCoverage() {
        return ratio(episodeActions, MinecraftAction.Command.values().length);
    }

    /** How many values were covered out of how many exist. */
    private static String ratio(EnumSet<?> covered, int total) {
        return covered.size() + "/" + total;
    }

    private double combatReward(Float mobHpBefore, Float ownHpBefore) {
        double dealt = 0;
        if (mobHpBefore != null) {
            float mobHpAfter = (lastMobHp == null) ? 0f : lastMobHp;   // gone means dead, i.e. 0 HP
            dealt = Math.max(0f, mobHpBefore - mobHpAfter);
        }

        double taken = 0;
        if (ownHpBefore != null && lastOwnHp != null) {
            taken = Math.max(0f, ownHpBefore - lastOwnHp);
        }

        return dealt - taken;
    }

    @Override
    public boolean isInTerminalState() {
        return currentState.getMobHpBucket() == HPBucket.DEAD
                || agentDied
                || currentState.getOwnHpBucket() == HPBucket.DEAD
                || updateCycles >= maxActionsPerEpisode;
    }

    /** Get the agent ready to fight and take the first observation of an episode */
    public void startAgentEnvironment() {
        updateCycles = 0;
        lastReward = 0;
        agentDied = false;
        mobKilledThisEpisode = false;
        episodeNumber++;
        tickCounter = 0;

        // Cleared so the equip phase below counts towards the episode it opens
        episodeOwnHP.clear();
        episodeMobHP.clear();
        episodeActions.clear();

        // Re-baseline the death counter before anything else in the episode can kill the agent
        deathsAtEpisodeStart = null;
        state.updateState(AGENT_ID);
        deathsAtEpisodeStart = state.getDeathCount();
        if (deathsAtEpisodeStart == null) {
            throw new RuntimeException("the testbench does not report the death count: "
                    + "rebuild it with patch P2, otherwise the agent's death is undetectable");
        }

        Float hp = state.getHealth();
        prevOwnHp = (hp == null) ? 0f : hp;

        GoalStructure equip = goalLib.selected(WEAPON);
        runGoal(equip, maxTicksPerAction, "equip");
        if (!equip.getStatus().success()) {
            throw new RuntimeException("cannot select " + WEAPON + ", the agent would fight "
                    + "bare-handed and the run would not be comparable: " + equip.getStatus());
        }

        currentObservation();   // so isInTerminalState() has a fresh observation to read
    }

    /** Start a new episode by rebuilding the arena through the testbench */
    @Override
    public void resetEnvironment() {
        waitUntilAlive();
        minecraftEnv.resetWorker();

        if (minecraftEnv.tagUuids.get(MOB_TAG) == null) {
            throw new RuntimeException("reset succeeded but tag " + MOB_TAG
                    + " is missing from the tag map: the level cannot be played");
        }
        startAgentEnvironment();
    }

    /** Wait for the agent to be back on its feet before rebuilding the level. */
    private void waitUntilAlive() {
        int ticks = 0;
        while (ticks < maxTicksPerAction) {
            state.updateState(AGENT_ID);
            if (state.isAgentAlive())
                return;
            minecraftEnv.waitTicks(AGENT_ID, 1);
            ticks++;
        }
    }
}
