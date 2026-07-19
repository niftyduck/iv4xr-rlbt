package eu.fbk.iv4xr.rlbt.minecraft;

import java.io.Serializable;

import burlap.mdp.core.action.Action;
import eu.iv4xr.framework.mainConcepts.WorldEntity;

/**
 * This class represents an action in the context of Minecraft. An action is a
 * command (verb) applied to a target entity or block of the WorldModel, e.g.
 * MOVE_TO block:16_65_3 or ATTACK entity:zombie:...
 */
public class MinecraftAction implements Action, Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * The commands correspond to the action primitives offered by MinecraftEnv
	 * (moveTo, mine, attack, ...)
	 */
	public enum Command {
		MOVE_TO, MINE, ATTACK
	}

	private Command command;

	private String targetId;

	private WorldEntity targetEntity;

	public MinecraftAction(Command command, String targetId) {
		this.command = command;
		this.targetId = targetId;
	}

	/**
	 * @return the command
	 */
	public Command getCommand() {
		return command;
	}

	/**
	 * @return the targetId
	 */
	public String getTargetId() {
		return targetId;
	}

	/**
	 * @return the targetEntity
	 */
	public WorldEntity getTargetEntity() {
		return targetEntity;
	}

	/**
	 * @param targetEntity the targetEntity to set
	 */
	public void setTargetEntity(WorldEntity targetEntity) {
		this.targetEntity = targetEntity;
	}

	@Override
	public String actionName() {
		return command.name() + ":" + targetId;
	}

	@Override
	public Action copy() {
		MinecraftAction copy = new MinecraftAction(command, targetId);
		copy.setTargetEntity(targetEntity);
		return copy;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof MinecraftAction)) {
			return false;
		}
		MinecraftAction other = (MinecraftAction) obj;
		return command == other.command && targetId.equals(other.targetId);
	}

	@Override
	public int hashCode() {
		return actionName().hashCode();
	}

	@Override
	public String toString() {
		return actionName();
	}
}
