package eu.fbk.iv4xr.rlbt.minecraft;

import eu.fbk.iv4xr.rlbt.minecraft.MinecraftBurlapState.DistanceBucket;
import eu.fbk.iv4xr.rlbt.minecraft.MinecraftBurlapState.HPBucket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** State-action coverage over the tuple (distance, own HP, mob HP, action) */
public class StateActionCoverage {

    private static final List<String> FEATURES =
            Arrays.asList("distance", "own HP", "mob HP", "action");

    /** Feasible values, feature by feature. Only DEAD is left out */
    private static final List<List<Object>> DOMAIN = Arrays.asList(
            Arrays.asList(DistanceBucket.values()),
            Arrays.asList(HPBucket.LOW, HPBucket.MEDIUM, HPBucket.HIGH),
            Arrays.asList(HPBucket.LOW, HPBucket.MEDIUM, HPBucket.HIGH),
            Arrays.asList(MinecraftAction.Command.values()));

    private final Set<List<Object>> observed = new LinkedHashSet<>();

    private int outsideDomain;

    public void record(DistanceBucket distance, HPBucket ownHp, HPBucket mobHp,
                       MinecraftAction.Command action) {
        List<Object> tuple = Arrays.asList((Object) distance, ownHp, mobHp, action);
        if (feasible(tuple))
            observed.add(tuple);
        else
            outsideDomain++;
    }

    private static boolean feasible(List<Object> tuple) {
        for (int i = 0; i < DOMAIN.size(); i++)
            if (!DOMAIN.get(i).contains(tuple.get(i)))
                return false;
        return true;
    }

    /** Combinations covered out of every feasible one. */
    public int covered() {
        return observed.size();
    }

    public int feasible() {
        int total = 1;
        for (List<Object> values : DOMAIN)
            total *= values.size();
        return total;
    }

    public String ratio() {
        return covered() + "/" + feasible();
    }

    private static List<List<Object>> allCombinations() {
        List<List<Object>> combinations = new ArrayList<>();
        combinations.add(new ArrayList<>());
        for (List<Object> values : DOMAIN) {
            List<List<Object>> grown = new ArrayList<>();
            for (List<Object> prefix : combinations)
                for (Object value : values) {
                    List<Object> tuple = new ArrayList<>(prefix);
                    tuple.add(value);
                    grown.add(tuple);
                }
            combinations = grown;
        }
        return combinations;
    }

    private static String key(List<Object> tuple) {
        StringBuilder sb = new StringBuilder();
        for (Object value : tuple) {
            if (sb.length() > 0) sb.append('|');
            sb.append(value);
        }
        return sb.toString();
    }

    /** The coverage block of summary.txt: the combinations exercised and the ones left. */
    public String summary(String nl) {
        List<String> done = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (List<Object> tuple : allCombinations())
            (observed.contains(tuple) ? done : missing).add(key(tuple));
        
        StringBuilder sb = new StringBuilder();
        sb.append("STATE-ACTION COVERAGE (").append(String.join(" x ", FEATURES)).append(')').append(nl);
        sb.append("    Feasible combinations: ").append(dimensions())
          .append(" = ").append(feasible())
          .append(" (terminal states excluded)").append(nl);
        sb.append("    Covered: ").append(ratio())
          .append(" (").append(Math.round(100f * covered() / feasible())).append("%)").append(nl);
        sb.append("    Done: [").append(String.join(", ", done)).append(']').append(nl);
        sb.append("    Missing: [").append(String.join(", ", missing)).append(']').append(nl);

        if (outsideDomain > 0)
            sb.append("    WARNING: ").append(outsideDomain)
              .append(" observations fell outside the feasible domain").append(nl);
        return sb.toString();
    }

    private static String dimensions() {
        StringBuilder sb = new StringBuilder();
        for (List<Object> values : DOMAIN) {
            if (sb.length() > 0) sb.append('*');
            sb.append(values.size());
        }
        return sb.toString();
    }
}
