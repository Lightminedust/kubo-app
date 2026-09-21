package com.kubo.model.services;

import java.util.ArrayList;
import java.util.List;

/**
 * Moving a service from one standing to another.
 *
 * <p>A standing is not a place a service is put: it is what its two answers add up to — is it running now,
 * and will it come back after a reboot. So moving one is a short sequence of the ordinary verbs, and which
 * verbs depends entirely on where it is starting from. Dragging a card from one column to another is that
 * sequence, worked out here rather than guessed at by the screen.
 *
 * <p>Nothing can be moved into {@link Standing#FAILED}: a failure is something that happens, not something
 * that is asked for. And a move is only offered when it really lands where it says — a one-shot task that has
 * finished is idle whatever is done to it, so it is never offered a move to "enabled, not running".
 */
public final class Move {

    private Move() {
    }

    /** The standings this service can really be moved to, in the order they are offered. */
    public static List<Standing> targets(ServiceUnit unit) {
        List<Standing> reachable = new ArrayList<>();
        for (Standing standing : new Standing[] {Standing.RUNNING, Standing.WILL_NOT_RETURN,
            Standing.NOT_RUNNING, Standing.IDLE}) {
            if (standing != unit.standing() && !stepsTo(standing, unit).isEmpty() && lands(standing, unit)) {
                reachable.add(standing);
            }
        }
        return reachable;
    }

    /**
     * What has to be run, in order, to move this service there.
     *
     * <p>Empty when it is already there, or when there is no sequence of these verbs that would take it.
     */
    public static List<Action> stepsTo(Standing target, ServiceUnit unit) {
        List<Action> steps = new ArrayList<>();
        boolean up = unit.active();
        boolean returns = unit.boot().returnsAfterReboot();
        switch (target) {
            case RUNNING -> {
                if (!up) {
                    steps.add(Action.START);
                }
                if (!returns) {
                    steps.add(Action.ENABLE);
                }
            }
            case WILL_NOT_RETURN -> {
                if (!up) {
                    steps.add(Action.START);
                }
                if (unit.boot() == BootState.ENABLED) {
                    steps.add(Action.DISABLE);
                }
            }
            case NOT_RUNNING -> {
                if (up) {
                    steps.add(Action.STOP);
                }
                if (!returns) {
                    steps.add(Action.ENABLE);
                }
            }
            case IDLE -> {
                if (up) {
                    steps.add(Action.STOP);
                }
                if (unit.boot() == BootState.ENABLED) {
                    steps.add(Action.DISABLE);
                }
            }
            // A failure is something that happens to a service, never something asked of it.
            case FAILED -> steps.clear();
            default -> steps.clear();
        }
        return steps;
    }

    /** True when running those steps really would leave the service in that standing. */
    public static boolean lands(Standing target, ServiceUnit unit) {
        return after(stepsTo(target, unit), unit).standing() == target;
    }

    /** The service as it would be once those steps had run, which is the only honest way to offer a move. */
    public static ServiceUnit after(List<Action> steps, ServiceUnit unit) {
        ServiceUnit moved = unit;
        for (Action step : steps) {
            moved = switch (step) {
                case START, RESTART -> new ServiceUnit(moved.name(), moved.description(), moved.load(),
                        "active", moved.oneShot() ? "exited" : "running", moved.boot(), moved.type(),
                        moved.startup());
                case STOP -> new ServiceUnit(moved.name(), moved.description(), moved.load(), "inactive",
                        "dead", moved.boot(), moved.type(), moved.startup());
                case ENABLE -> moved.withBoot(BootState.ENABLED);
                case DISABLE -> moved.withBoot(BootState.DISABLED);
            };
        }
        return moved;
    }
}
