package eu.fbk.iv4xr.rlbt.minecraft;

import burlap.mdp.core.action.Action;
import burlap.mdp.core.state.State;
import burlap.mdp.singleagent.environment.Environment;
import burlap.mdp.singleagent.environment.EnvironmentOutcome;
import eu.fbk.iv4xr.minecraftlib.MinecraftEnv;
import eu.fbk.iv4xr.minecraftlib.MinecraftGoalLib;
import eu.fbk.iv4xr.minecraftlib.MinecraftState;
import eu.iv4xr.framework.mainConcepts.TestAgent;
import eu.iv4xr.framework.mainConcepts.TestDataCollector;
import eu.iv4xr.framework.mainConcepts.WorldEntity;
import eu.iv4xr.framework.spatial.Vec3;
import eu.fbk.iv4xr.rlbt.minecraft.MinecraftBurlapState.HPBucket;
import nl.uu.cs.aplib.mainConcepts.GoalStructure;

import static nl.uu.cs.aplib.AplibEDSL.SEQ;

/**
 * The BURLAP environment of the Minecraft combat scenario
 *
 * It is the only place where the layers meet: the raw observation coming from
 * the SUT is turned into the discretised {@link MinecraftBurlapState} here, and
 * the symbolic actions of {@link MinecraftAction} are turned into aplib goals
 * here. Everything else stays SUT-agnostic.
 */
public class MinecraftRLEnvironment implements Environment {

    private MinecraftEnv minecraftEnv;      // HTTP client, only one instance
    private MinecraftState state;           // belief-state iv4xr
    private TestAgent testAgent;            // minecraft agent
    private MinecraftGoalLib goalLib;
    private MinecraftBurlapState currentState;

    private double lastReward;
    private int updateCycles;   // budget of episode actions

    private final String AGENT_ID = "Bot";
    private final String MOB_TAG = "mob1";

    /*
     * Tuning constants. They live here, and not inside the actions, on purpose:
     * the action name is the Q-table column key, so changing any of these does
     * not invalidate a saved Q-table (see PROJECT.md §2.6). They move to
     * MinecraftConfiguration at step 9.
     */

    /**
     * How close APPROACH tries to get, in blocks. Deliberately inside MELEE
     * (2-4) rather than CONTACT (<2): the measured player reach is ~4 and the
     * zombie's is ~2, so this is the band where the agent hits without being
     * hit. Stopping at 2.0, as the baseline does, would make APPROACH always
     * land in CONTACT and the CONTACT/MELEE distinction unusable by the policy.
     */
    private static final double APPROACH_DISTANCE = 3.0;

    /**
     * The distance from the enemy RETREAT aims for, in blocks -- a target
     * distance, not a displacement. A fixed displacement would land in a bucket
     * that depends on where the agent started (retreating 6 blocks from 2 ends
     * up at exactly 8.0, the MEDIUM/FAR boundary), whereas the point of the
     * action is to reach a known band. 6.0 sits in the middle of MEDIUM (5-8),
     * far enough from both edges to survive the pathfinder's imprecision.
     *
     * Staying clear of FAR matters: FAR is also the bucket of "enemy not
     * observed", so landing there would blur retreating with losing sight.
     */
    private static final double RETREAT_DISTANCE = 6.0;

    /** Tolerance on the retreat destination: the pathfinder rarely lands exactly. */
    private static final double RETREAT_TOLERANCE = 1.5;

    /** Ticks waited after a hit, i.e. the weapon cooldown. Same as the baseline. */
    private static final int ATTACK_COOLDOWN_TICKS = 20;

    /** Tick budget of a single action, as MineAgentBaseline.MAX_TICKS. */
    private static final int MAX_TICKS_PER_ACTION = 120;

    /** Action budget of one episode. */
    private static final int MAX_ACTIONS_PER_EPISODE = 30;

    /**
     * The weapon the agent fights with. It comes from the inventory section of
     * the level CSV (arena.csv:1) and has to be selected again after every
     * reset, because rebuilding the level runs /clear on the bot.
     */
    private static final String WEAPON = "iron_sword";

    /**
     * @param env the single {@link MinecraftEnv} of the run, <b>already used to
     *            build the level</b>. It must not be created here: the instance
     *            carries the client-side tag cache filled by {@code buildLevel},
     *            and a fresh one would not be able to resolve MOB_TAG.
     */
    public MinecraftRLEnvironment(MinecraftEnv env) {
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

    /**
     * Observe the SUT and turn the observation into the abstract RL state: read
     * the raw combat quantities and hand them to
     * {@link MinecraftBurlapState#updateAbstraction}, which does the bucketing.
     *
     * Every quantity is nullable and that is expected, not exceptional: the mob
     * is not reported once it dies or leaves the testbench scan radius. The
     * conventions of the state (unknown distance = FAR, missing HP = DEAD) cover
     * those cases, so nothing has to be guarded here.
     */
    @Override
    public State currentObservation() {
        state.updateState(AGENT_ID);

        WorldEntity mob = mobEntity(MOB_TAG);

        Float ownHp = state.getHealth();
        Float mobHp = minecraftEnv.getMobHealth(MOB_TAG);
        Vec3 ownPos = state.getAgentPosition();
        Vec3 mobPos = (mob == null) ? null : mob.position;
        Double dist = distance(ownPos, mobPos);

        // WorldEntity.type holds the entity name ("zombie", ...), see
        // StatusToWorldModel: new WorldEntity(uuid, name, true)
        Float mobMaxHp = (mob == null) ? null : MinecraftBurlapState.maxHpForMob(mob.type);

        currentState.updateAbstraction(ownHp, mobHp, mobMaxHp, dist);

        // a copy, so that the state handed to the learner is not altered by the
        // next observation (BURLAP keeps past states inside the episodes)
        return currentState.copy();
    }

    /**
     * Live position and type of a tagged mob, read from the agent's belief-state.
     *
     * The tag map alone is not enough: {@code MinecraftEnv.tagPosition} (and thus
     * {@code MinecraftState.distanceToTag}) returns the position the entity was
     * <b>built</b> at, which for a mob that walks around goes stale immediately.
     *
     * @return the entity, or null if the tag is unknown or the mob is not
     *         currently observed (dead, or outside the scan radius)
     */
    private WorldEntity mobEntity(String tag) {
        String uuid = minecraftEnv.tagUuids.get(tag);
        if (uuid == null || state.worldmodel == null)
            return null;
        return state.worldmodel.getElement(uuid);
    }

    /**
     * Euclidean distance between two positions, or null if either is unknown.
     */
    private static Double distance(Vec3 a, Vec3 b) {
        return (a == null || b == null) ? null : (double) Vec3.dist(a, b);
    }

    @Override
    public double lastReward() {
        return lastReward;
    }

    /**
     * Run one action against the SUT and report what happened: observe before,
     * translate the action into an aplib goal and pursue it, observe again,
     * score the outcome.
     *
     * The two observations are what BURLAP learns from, so they must bracket the
     * execution: the reward of an action is the difference it made.
     */
    @Override
    public EnvironmentOutcome executeAction(Action a) {
        MinecraftAction action = (MinecraftAction) a;

        State oldState = currentObservation();
        // raw readings, taken together with the observation above: the buckets
        // of the state are too coarse to measure the damage of a single hit
        Float mobHpBefore = minecraftEnv.getMobHealth(MOB_TAG);
        Float ownHpBefore = state.getHealth();

        GoalStructure goal = toGoal(action);
        if (goal != null) {
            runGoal(goal, MAX_TICKS_PER_ACTION);
        }

        State newState = currentObservation();
        lastReward = combatReward(mobHpBefore, ownHpBefore);
        updateCycles++;   // each action consumes budget

        System.out.println("Action " + action.actionName()
                + " | " + oldState + " -> " + newState
                + " | goal " + (goal == null ? "NOT APPLICABLE" : goal.getStatus())
                + " | reward " + lastReward);

        return new EnvironmentOutcome(oldState, action, newState, lastReward, isInTerminalState());
    }

    /**
     * Translate a symbolic action into the aplib goal that carries it out. This
     * is the only place that knows how an action is actually performed: the
     * action itself is a bare symbol, and the target is resolved here.
     *
     * @return the goal, or null if the action makes no sense right now (RETREAT
     *         with no visible enemy: there is no direction to move away from)
     */
    private GoalStructure toGoal(MinecraftAction action) {
        switch (action.getCommand()) {
            case APPROACH:
                return goalLib.tagReachedWithinDistance(MOB_TAG, APPROACH_DISTANCE);

            case ATTACK:
                // hit, then sit out the weapon cooldown, exactly as the baseline
                // does: hitting faster than the cooldown deals reduced damage
                return SEQ(goalLib.attacked(MOB_TAG), goalLib.waited(ATTACK_COOLDOWN_TICKS));

            case RETREAT:
                Vec3 target = retreatPosition();
                return (target == null) ? null : goalLib.reached(target, RETREAT_TOLERANCE);

            default:
                throw new RuntimeException("Unknown command: " + action.getCommand());
        }
    }

    /**
     * Where RETREAT should go: the point on the agent's side of the enemy that
     * sits RETREAT_DISTANCE blocks away from it, at the agent's own height.
     *
     * No yaw is involved: the direction comes from the two positions, so this
     * works even though the agent's facing is not observable yet (PROJECT.md §3).
     *
     * @return the destination, or null when the action does not apply: enemy not
     *         observed, agent and enemy on the same spot (no direction can be
     *         derived), or agent already farther away than RETREAT_DISTANCE --
     *         retreating must never walk the agent back towards the enemy
     */
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

    /**
     * Pursue a goal by ticking the agent until it settles or the budget runs out.
     * Same engine as MineAgentBaseline.runGoal, without the per-tick logging.
     *
     * TODO the two copies should become one, see PROJECT.md §4 step 6 point 7.
     */
    private void runGoal(GoalStructure goal, int tickBudget) {
        testAgent.setGoal(goal);
        int ticks = 0;
        while (goal.getStatus().inProgress() && ticks < tickBudget) {
            testAgent.update();   // every update() performs an action, blocking server-side
            ticks++;
        }
    }

    /**
     * Provisional reward: damage dealt minus damage taken, in HP. Enough to give
     * the learner a gradient, deliberately not the final one -- the real reward
     * (curiosity/coverage driven, see PROJECT.md §4 step 7) is a separate step.
     *
     * Measured on raw HP and not on the state buckets, which are far too coarse
     * to notice a single hit.
     */
    private double combatReward(Float mobHpBefore, Float ownHpBefore) {
        double dealt = 0;
        if (mobHpBefore != null) {
            Float after = minecraftEnv.getMobHealth(MOB_TAG);
            float mobHpAfter = (after == null) ? 0f : after;   // gone means dead, i.e. 0 HP
            dealt = Math.max(0f, mobHpBefore - mobHpAfter);
        }

        double taken = 0;
        Float ownHpAfter = state.getHealth();
        if (ownHpBefore != null && ownHpAfter != null) {
            taken = Math.max(0f, ownHpBefore - ownHpAfter);
        }

        return dealt - taken;
    }

    /**
     * The episode is over when one of the fighters is down, or the action budget
     * is spent.
     *
     * Reads the buckets of the last observation, so it is only meaningful after
     * currentObservation() has run -- which executeAction always does.
     */
    @Override
    public boolean isInTerminalState() {
        return currentState.getMobHpBucket() == HPBucket.DEAD
                || currentState.getOwnHpBucket() == HPBucket.DEAD
                || updateCycles >= MAX_ACTIONS_PER_EPISODE;
    }

    /**
     * Get the agent ready to fight and take the first observation of an episode.
     *
     * Selecting the weapon is not optional: rebuilding the level runs /clear on
     * the bot, so the sword has to be put back in hand every time. Fighting
     * bare-handed would still "work" -- it deals about 1 HP instead of the ~6 of
     * the iron sword -- and would quietly make every number incomparable with
     * the baseline, so a failure here is raised rather than logged.
     *
     * Public because the first episode has no reset before it: the training loop
     * (step 8) calls this once, exactly as RlbtMain does with
     * LabRecruitsRLEnvironment.startAgentEnvironment.
     */
    public void startAgentEnvironment() {
        GoalStructure equip = goalLib.selected(WEAPON);
        runGoal(equip, MAX_TICKS_PER_ACTION);
        if (!equip.getStatus().success()) {
            throw new RuntimeException("cannot select " + WEAPON + ", the agent would fight "
                    + "bare-handed and the run would not be comparable: " + equip.getStatus());
        }

        updateCycles = 0;
        lastReward = 0;
        currentObservation();   // so isInTerminalState() has fresh buckets to read
    }

    /**
     * Start a new episode by rebuilding the arena through the testbench.
     *
     * Unlike LabRecruits, the game cannot be restarted from here: the Minecraft
     * server and the testbench are external processes. What /reset does is
     * rebuild the last level in place (level-builder.ts), which is enough: it
     * kills the old mob and whatever it dropped, restores the blocks, clears and
     * refills the bot's inventory, heals it and teleports it back to the origin.
     *
     * The mob is re-summoned, so it gets a <b>new UUID</b>, and the tag map is
     * rebuilt with it. Nothing has to be done about that here: the actions carry
     * no target (PROJECT.md §2.6) and mobEntity() resolves the tag on every
     * call, so the new UUID is picked up by itself. Had the UUID been part of the
     * action name, the Q-table would have started from scratch at every episode.
     */
    @Override
    public void resetEnvironment() {
        minecraftEnv.resetWorker();

        // The loud failures are already covered: MinecraftEnv.postJson raises
        // Iv4xrError on any status >= 300, so a refused connection, a 409 (bot
        // busy) or a 500 all propagate from the call above.
        //
        // What is left is the quiet one: a 2xx whose body carries no tags. There
        // cacheTags empties the maps and returns without a word, the tag would
        // resolve to nothing, the mob would read as DEAD, and every episode would
        // end on its first action -- a training that runs to completion and
        // learns nothing. Cheap to rule out, so rule it out.
        if (minecraftEnv.tagUuids.get(MOB_TAG) == null) {
            throw new RuntimeException("reset succeeded but tag " + MOB_TAG
                    + " is missing from the tag map: the level cannot be played");
        }

        startAgentEnvironment();
    }
}
