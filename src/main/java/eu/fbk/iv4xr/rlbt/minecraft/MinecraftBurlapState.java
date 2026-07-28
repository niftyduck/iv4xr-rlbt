package eu.fbk.iv4xr.rlbt.minecraft;

import burlap.mdp.core.oo.state.OOState;
import burlap.mdp.core.oo.state.generic.GenericOOState;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * BURLAP state for the Minecraft combat scenario.
 *
 * The state is a small vector of already-discretized combat
 * features: the distance to the enemy and the health of both fighters. Raw
 * float positions and HP would make the state space effectively continuous, and
 * the bucketization doubles as the definition of state coverage.
 *
 * The features live in a single {@link MinecraftFeatureObject} held by this
 * state (see that class for why they are an object and not plain fields).
 * Neither the agent nor the observed entities are part of the state: they are
 * the environment's business.
 */
public class MinecraftBurlapState extends GenericOOState implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Distance of the enemy from the agent, in blocks
     *
     * CONTACT  < 2 blocks
     * MELEE    2-4 blocks
     * NEAR     4-5 blocks
     * MEDIUM   5-8 blocks
     * FAR      >= 8 blocks, or the enemy is not observed at all (the testbench
     *          only reports entities within scan.entityRadius, 10 by default)
     */
    public enum DistanceBucket {
        CONTACT,
        MELEE,
        NEAR,
        MEDIUM,
        FAR
    }

    /**
     * Health of a fighter as a fraction of its maximum, so that the same
     * buckets apply to the agent (20 HP) and to mobs with a different pool.
     *
     * DEAD
     * LOW      < 33%
     * MEDIUM   33% - 66%
     * HIGH     > 66%
     */
    public enum HPBucket {
        DEAD,
        LOW,
        MEDIUM,
        HIGH
    }

    /**
     * Distance boundaries in blocks.
     * Provisional: to be re-derived from a baseline run on the current arena.
     */
    public static final double CONTACT_DISTANCE = 2.0;
    public static final double MELEE_DISTANCE = 4.0;
    public static final double NEAR_DISTANCE = 5.0;
    public static final double MEDIUM_DISTANCE = 8.0;

    /** Health boundaries as a fraction of maximum health.ì */
    public static final float HP_LOW_RATIO = 0.33f;
    public static final float HP_HIGH_RATIO = 0.66f;

    /** Maximum health of the agent. A Minecraft player has 10 hearts. */
    public static final float PLAYER_MAX_HP = 20f;

    /** Fallback used by {@link #maxHpForMob(String)} for unlisted mobs. */
    public static final float DEFAULT_MOB_MAX_HP = 20f;

    /** Maximum health of the mobs used in the arena levels. Extend as needed. */
    private static final Map<String, Float> MOB_MAX_HP;
    static {
        Map<String, Float> m = new HashMap<>();
        m.put("zombie", 20f);
        m.put("skeleton", 20f);
        m.put("creeper", 20f);
        m.put("spider", 16f);
        m.put("enderman", 40f);
        MOB_MAX_HP = Collections.unmodifiableMap(m);
    }

    public MinecraftBurlapState() {
        super();
        addObject(new MinecraftFeatureObject());
    }

    /** Copy constructor: takes over the objects of {@code src}, features included. */
    public MinecraftBurlapState(OOState src) {
        super(src);
    }


    /** GETTERS */
    public MinecraftFeatureObject getFeatures() {
        return (MinecraftFeatureObject) object(MinecraftFeatureObject.OBJECT_NAME);
    }

    public DistanceBucket getEnemyDistance() { return getFeatures().getEnemyDistance(); }
    public HPBucket getOwnHpBucket() { return getFeatures().getOwnHp(); }
    public HPBucket getMobHpBucket() { return getFeatures().getMobHp(); }


    /**
     * Recompute the whole abstraction from a fresh observation.
     *
     * Every argument is nullable, because that is how the underlying APIs report
     * a missing observation -- though the two sources do not share the same
     * limit, and it matters:
     *
     * - the distance is null whenever either position is unknown, and the mob's
     *   position comes from the nearbyEntities of /status, so it does go missing
     *   past the testbench scan radius (10 blocks by default);
     * - {@code MinecraftEnv.getMobHealth} does not: it goes through /tags/:uuid,
     *   which runs the vanilla /data get entity command server-side, so it is
     *   independent of how far away the agent is. It returns null when the
     *   entity no longer exists (dead) -- or when the testbench call times out,
     *   which is why DEAD is a trustworthy terminal signal but not an infallible
     *   one (see PROJECT.md §2.7).
     *
     * The feature object is replaced rather than mutated: {@code GenericOOState}
     * is a shallow-copy state, so mutating in place would also alter every state
     * previously copied from this one (the episodes BURLAP keeps around).
     *
     * @param ownHp         current health of the agent, out of {@link #PLAYER_MAX_HP}
     * @param mobHp         current health of the enemy, null if dead/unobserved
     * @param mobMaxHp      maximum health of the enemy, see {@link #maxHpForMob(String)}
     * @param enemyDistance distance agent-enemy in blocks, null if unobserved
     */
    public void updateAbstraction(Float ownHp, Float mobHp, Float mobMaxHp, Double enemyDistance) {
        addObject(new MinecraftFeatureObject(
                distanceBucket(enemyDistance),
                hpBucket(ownHp, PLAYER_MAX_HP),
                hpBucket(mobHp, mobMaxHp == null ? DEFAULT_MOB_MAX_HP : mobMaxHp)));
    }

    /**
     * Same as {@link #updateAbstraction(Float, Float, Float, Double)} for an enemy
     * whose maximum health is the standard 20 HP.
     */
    public void updateAbstraction(Float ownHp, Float mobHp, Double enemyDistance) {
        updateAbstraction(ownHp, mobHp, DEFAULT_MOB_MAX_HP, enemyDistance);
    }

    /**
     * Maximum health of a mob given its entity name.
     * Unknown mobs fall back to {@link #DEFAULT_MOB_MAX_HP}.
     */
    public static float maxHpForMob(String entityName) {
        if (entityName == null)
            return DEFAULT_MOB_MAX_HP;
        Float max = MOB_MAX_HP.get(entityName.toLowerCase());
        return max == null ? DEFAULT_MOB_MAX_HP : max;
    }

    /**
     * Discretize a health value relative to the maximum health of its owner. A
     * null or non-positive value means the entity is dead or gone.
     */
    public static HPBucket hpBucket(Float hp, float maxHp) {
        if (hp == null || hp <= 0f || maxHp <= 0f) return HPBucket.DEAD;

        float ratio = hp / maxHp;
        if (ratio < HP_LOW_RATIO) return HPBucket.LOW;
        if (ratio < HP_HIGH_RATIO) return HPBucket.MEDIUM;
        return HPBucket.HIGH;
    }

    /**
     * Discretize a distance in blocks. An unknown distance is reported as
     * {@link DistanceBucket#FAR}: the enemy being unobserved means it is outside
     * the scan radius, which is farther than any observable bucket.
     */
    public static DistanceBucket distanceBucket(Double distance) {
        if (distance == null) return DistanceBucket.FAR;
        if (distance < CONTACT_DISTANCE) return DistanceBucket.CONTACT;
        if (distance < MELEE_DISTANCE) return DistanceBucket.MELEE;
        if (distance < NEAR_DISTANCE) return DistanceBucket.NEAR;
        if (distance < MEDIUM_DISTANCE) return DistanceBucket.MEDIUM;
        return DistanceBucket.FAR;
    }

    /**
     * Compact signature of the abstraction, e.g. "MELEE|HIGH|MEDIUM". Two states
     * sharing this key are indistinguishable to the learner.
     */
    public String abstractionKey() {
        return getFeatures().toString();
    }

    /**
     * {@link GenericOOState#copy()} returns a plain GenericOOState, which would
     * drop this type, so it has to be overridden.
     */
    @Override
    public MinecraftBurlapState copy() {
        return new MinecraftBurlapState(this);
    }

    @Override
    public String toString() {
        return "[" + abstractionKey() + "]";
    }
}
