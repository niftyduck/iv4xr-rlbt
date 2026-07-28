package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.Serializable;

import burlap.mdp.core.action.Action;

/**
 * An action of the Minecraft combat scenario. An action is a bare symbol: the
 * command alone, with no target and no parameter.
 *
 * This is the agent-centric counterpart of the feature state: since the state
 * holds no entity (see {@link MinecraftBurlapState}), there is nothing to
 * enumerate targets from, and the action set is the same in every state. Who the
 * enemy is, how close APPROACH gets and how long ATTACK waits are all decided by
 * the environment, which owns the target tag and the timing constants.
 *
 * Keeping the target out of the action is not cosmetic: the action name is the
 * column key of the Q-table. An identifier of the mob inside it would change at
 * every episode, because the mob is re-summoned with a fresh UUID, and the agent
 * would start from an empty row every time.
 */
public class MinecraftAction implements Action, Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * The fixed, agent-centric action set. The semantics below is what the
	 * environment is expected to implement; this class only names it.
	 *
	 * APPROACH  move towards the current enemy, up to melee range
	 * ATTACK    hit the current enemy, then wait out the weapon cooldown
	 *           (as the baseline does, see MineAgentBaseline)
	 * RETREAT   move away from the current enemy, along the line joining them
	 */
	public enum Command {
		APPROACH, ATTACK, RETREAT
	}

	private final Command command;

	public MinecraftAction(Command command) {
		this.command = command;
	}

	/**
	 * @return the command
	 */
	public Command getCommand() {
		return command;
	}

	@Override
	public String actionName() {
		return command.name();
	}

	@Override
	public Action copy() {
		return new MinecraftAction(command);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof MinecraftAction)) {
			return false;
		}
		return command == ((MinecraftAction) obj).command;
	}

	@Override
	public int hashCode() {
		return command.hashCode();
	}

	@Override
	public String toString() {
		return actionName();
	}
}
