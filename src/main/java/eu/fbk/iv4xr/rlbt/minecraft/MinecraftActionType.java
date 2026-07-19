package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import burlap.mdp.core.action.Action;
import burlap.mdp.core.action.ActionType;
import burlap.mdp.core.oo.state.OOState;
import burlap.mdp.core.oo.state.ObjectInstance;
import burlap.mdp.core.state.State;
import eu.fbk.iv4xr.minecraftlib.StatusToWorldModel;
import eu.fbk.iv4xr.rlbt.minecraft.MinecraftAction.Command;
import eu.iv4xr.framework.mainConcepts.WorldEntity;

/**
 * Enumerates the actions applicable in a given Minecraft state. For every
 * entity/block observed in the WorldModel, the applicable commands depend on
 * its nature: blocks can be reached or mined, dynamic entities (mobs) can be
 * reached or attacked. The agent itself is never a target.
 */
public class MinecraftActionType implements ActionType, Serializable {

	private static final long serialVersionUID = 1L;

	private String typeName = "minecraftAction";

	@Override
	public String typeName() {
		return typeName;
	}

	@Override
	public Action associatedAction(String strRep) {
		// action names have the form COMMAND:targetId, see MinecraftAction.actionName()
		int sep = strRep.indexOf(':');
		Command command = Command.valueOf(strRep.substring(0, sep));
		return new MinecraftAction(command, strRep.substring(sep + 1));
	}

	@Override
	public List<Action> allApplicableActions(State s) {
		List<Action> actions = new ArrayList<Action>();
		for (ObjectInstance object : ((OOState) s).objects()) {
			// by convention the ObjectInstance returns the wrapped WorldEntity
			// when queried with its own name (same convention as LabRecruitsEntityObject)
			WorldEntity entity = (WorldEntity) object.get(object.name());

			// the agent itself is not a possible target
			if (StatusToWorldModel.AGENT_TYPE.equals(entity.type)) {
				continue;
			}

			if (entity.dynamic) {
				// mobs and other dynamic entities
				addAction(actions, Command.MOVE_TO, entity);
				addAction(actions, Command.ATTACK, entity);
			} else {
				// blocks
				addAction(actions, Command.MOVE_TO, entity);
				addAction(actions, Command.MINE, entity);
			}
		}
		return actions;
	}

	private void addAction(List<Action> actions, Command command, WorldEntity entity) {
		MinecraftAction action = new MinecraftAction(command, entity.id);
		action.setTargetEntity(entity);
		actions.add(action);
	}
}
