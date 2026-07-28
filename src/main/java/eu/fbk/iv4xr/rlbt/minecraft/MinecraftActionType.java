package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import burlap.mdp.core.action.Action;
import burlap.mdp.core.action.ActionType;
import burlap.mdp.core.state.State;
import eu.fbk.iv4xr.rlbt.minecraft.MinecraftAction.Command;

/**
 * The action set of the Minecraft combat scenario: a fixed, agent-centric list,
 * the same in every state.
 *
 * The previous version enumerated the actions from the entities of the state,
 * one MOVE_TO plus MINE/ATTACK per object. That is no longer possible, nor
 * wanted: the state is now a single feature object and holds no entity (see
 * {@link MinecraftBurlapState}), so there is nothing to enumerate from. It would
 * also not scale, since every decorative block of the arena would add two
 * actions to the list.
 *
 * The state is therefore ignored by {@link #allApplicableActions(State)}: which
 * enemy an action applies to is resolved by the environment, not encoded here.
 */
public class MinecraftActionType implements ActionType, Serializable {

	private static final long serialVersionUID = 1L;

	private String typeName = "minecraftAction";

	/** One action per command, built once: they are immutable and stateless. */
	private static final List<Action> FIXED_ACTIONS;
	static {
		List<Action> actions = new ArrayList<Action>();
		for (Command command : Command.values()) {
			actions.add(new MinecraftAction(command));
		}
		FIXED_ACTIONS = actions;
	}

	@Override
	public String typeName() {
		return typeName;
	}

	/**
	 * Rebuilds an action from its name, i.e. from the command alone (see
	 * {@link MinecraftAction#actionName()}). Needed to read back the serialised
	 * Q-table and episodes.
	 */
	@Override
	public Action associatedAction(String strRep) {
		return new MinecraftAction(Command.valueOf(strRep));
	}

	/**
	 * @param s ignored: every action is applicable in every state
	 */
	@Override
	public List<Action> allApplicableActions(State s) {
		// a fresh list, so that a caller cannot alter the shared action set
		return new ArrayList<Action>(FIXED_ACTIONS);
	}
}
