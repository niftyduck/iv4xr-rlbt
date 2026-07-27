package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;

import eu.iv4xr.framework.spatial.Vec3;

/**
 * Writes the two per-session CSVs of a baseline run:
 * <ul>
 *   <li><b>ticks.csv</b>   — one row per tick (state telemetry: own/mob HP and position, distance)</li>
 *   <li><b>actions.csv</b> — one row per performed action (action-centric: what the agent did,
 *       on which target, and the resulting effect). Mirrors the idea of LabRecruits'
 *       {@code RLActionToTestCaseEncoder}, but incremental and combat-oriented.</li>
 * </ul>
 * Rows are flushed incrementally; call {@link #close()} at the end of the session
 * (or use try-with-resources).
 *
 * @author generated for the Minecraft baseline (PROJECT.md, step 2.6)
 */
public class EpisodeLogger implements AutoCloseable {

    private static final String TICKS_HEADER =
            "episode,tick,phase,goal_status,own_hp,own_x,own_y,own_z,mob_hp,mob_x,mob_y,mob_z,dist";

    private static final String ACTIONS_HEADER =
            "id,episode,action,target,param,goal_status,"
          + "mob_hp_before,mob_hp_after,damage_dealt,"
          + "own_hp_before,own_hp_after,damage_taken,hit_landed";

    private final BufferedWriter ticks;
    private final BufferedWriter actions;
    private int actionId = 1;

    /**
     * Open (creating/overwriting) {@code ticks.csv} and {@code actions.csv} in the given
     * session directory and write their headers.
     *
     * @param sessionDir the already-created output directory of this run
     */
    public EpisodeLogger(File sessionDir) {
        try {
            ticks   = new BufferedWriter(new FileWriter(new File(sessionDir, "ticks.csv")));
            actions = new BufferedWriter(new FileWriter(new File(sessionDir, "actions.csv")));
            ticks.write(TICKS_HEADER);     ticks.newLine();
            actions.write(ACTIONS_HEADER); actions.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open session CSV files in " + sessionDir, e);
        }
    }

    /**
     * One row of state telemetry: call it every tick.
     * Positions are split into x/y/z columns so no comma leaks into a cell; nulls become empty cells.
     */
    public void logTick(int episode, int tick, String phase, String goalStatus,
                        Float ownHp, Vec3 ownPos, Float mobHp, Vec3 mobPos, Double dist) {
        writeRow(ticks, join(
                num(episode), num(tick), csv(phase), csv(goalStatus),
                num(ownHp), x(ownPos), y(ownPos), z(ownPos),
                num(mobHp), x(mobPos), y(mobPos), z(mobPos), num(dist)));
    }

    /**
     * One row per performed action. {@code damage_dealt}, {@code damage_taken} and
     * {@code hit_landed} are derived from the before/after HP (a hit "lands" iff it dealt
     * damage — note this can differ from {@code goal_status == SUCCESS}, see PROJECT.md step 5).
     */
    public void logAction(int episode, String action, String target, String param, String goalStatus,
                          Float mobHpBefore, Float mobHpAfter, Float ownHpBefore, Float ownHpAfter) {
        Float dealt = (mobHpBefore != null && mobHpAfter != null) ? mobHpBefore - mobHpAfter : null;
        Float taken = (ownHpBefore != null && ownHpAfter != null) ? ownHpBefore - ownHpAfter : null;
        boolean hitLanded = dealt != null && dealt > 0f;
        writeRow(actions, join(
                num(actionId++), num(episode), csv(action), csv(target), csv(param), csv(goalStatus),
                num(mobHpBefore), num(mobHpAfter), num(dealt),
                num(ownHpBefore), num(ownHpAfter), num(taken), String.valueOf(hitLanded)));
    }

    @Override
    public void close() {
        closeQuietly(ticks);
        closeQuietly(actions);
    }

    /////////////////////////////////////////////////////
    ///
    /// Helpers
    ///
    /////////////////////////////////////////////////////

    private static void writeRow(BufferedWriter w, String row) {
        try {
            w.write(row);
            w.newLine();
            w.flush();   // per-row flush = crash-safe; drop it for higher throughput
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String join(String... cells) {
        return String.join(",", cells);
    }

    private static String num(Number n) {
        return n == null ? "" : n.toString();
    }

    private static String x(Vec3 v) { return v == null ? "" : Float.toString(v.x); }
    private static String y(Vec3 v) { return v == null ? "" : Float.toString(v.y); }
    private static String z(Vec3 v) { return v == null ? "" : Float.toString(v.z); }

    /** Quote a field only if it contains comma, quote or newline (RFC-4180-ish). */
    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static void closeQuietly(BufferedWriter w) {
        try {
            if (w != null) {
                w.close();
            }
        } catch (IOException ignored) {
            // best effort on close
        }
    }
}
