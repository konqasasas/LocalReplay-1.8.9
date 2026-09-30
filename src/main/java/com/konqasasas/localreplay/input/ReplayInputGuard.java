package com.konqasasas.localreplay.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovementInput;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Cuts live gameplay input while a replay UI owns the client.
 *
 * <p>Minecraft 1.8.9 does not expose Forge's later InputUpdateEvent, so the
 * isolation boundary is enforced from ClientTick START: every KeyBinding is
 * released before the player tick can rebuild MovementInput, and the already
 * computed MovementInput / inherited movement-intent fields are neutralized at
 * the same time. ReplayScreen still receives raw GuiScreen keyboard/mouse
 * events, so replay transport controls continue to work.</p>
 *
 * <p>Keys/buttons physically held while replay is active are latched until the
 * user actually releases them. Closing replay therefore cannot turn a held W
 * or mouse button back into a gameplay press. A fresh press after release works
 * normally.</p>
 *
 * <p>This class changes only transient client input/control state. It never
 * writes player position, rotation, velocity/motion, and never sends packets or
 * invokes gameplay actions.</p>
 */
public final class ReplayInputGuard {

    private final Minecraft minecraft;
    private final Set<Integer> blockedUntilRelease = new HashSet<>();
    private boolean replayIsolationActive;

    public ReplayInputGuard(Minecraft minecraft) {
        if (minecraft == null) {
            throw new IllegalArgumentException("minecraft cannot be null");
        }
        this.minecraft = minecraft;
    }

    /** Called at the exact moment replay becomes active. */
    public void beginReplay() {
        replayIsolationActive = true;
        latchPhysicallyHeldBindings();
        KeyBinding.unPressAllKeys();
        neutralizePlayerControlState();
    }

    /**
     * Called from ClientTickEvent.START. In 1.8.9 MovementInputFromOptions is
     * rebuilt later in the same player tick from KeyBinding state, so clearing
     * both layers here prevents a pre-held movement key from leaking into the
     * real player behind the replay UI.
     */
    public void beforeClientTick(boolean replayActive) {
        if (replayActive) {
            replayIsolationActive = true;
            latchPhysicallyHeldBindings();
            KeyBinding.unPressAllKeys();
            neutralizePlayerControlState();
            return;
        }

        replayIsolationActive = false;
        suppressBlockedBindingsUntilReleased();
    }

    /**
     * Called again at ClientTick END as a defensive cleanup point. This does
     * not affect position/rotation/motion; it only ensures no transient input
     * intent remains latched after the world/player update.
     */
    public void afterClientTick(boolean replayActive) {
        if (replayActive) {
            replayIsolationActive = true;
            KeyBinding.unPressAllKeys();
            neutralizePlayerControlState();
        } else {
            replayIsolationActive = false;
            suppressBlockedBindingsUntilReleased();
        }
    }

    private void neutralizePlayerControlState() {
        if (!replayIsolationActive) {
            return;
        }

        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null) {
            return;
        }

        clearMovementInput(player.movementInput);
        player.moveForward = 0.0F;
        player.moveStrafing = 0.0F;
        player.setJumping(false);
    }

    private static void clearMovementInput(MovementInput input) {
        if (input == null) {
            return;
        }
        input.moveForward = 0.0F;
        input.moveStrafe = 0.0F;
        input.jump = false;
        input.sneak = false;
    }

    /** Called whenever replay ownership ends, including forced cleanup. */
    public void endReplay() {
        if (!replayIsolationActive) {
            return;
        }
        latchPhysicallyHeldBindings();
        replayIsolationActive = false;

        KeyBinding.unPressAllKeys();
        suppressBlockedBindingsUntilReleased();
    }

    private void latchPhysicallyHeldBindings() {
        if (minecraft.gameSettings == null || minecraft.gameSettings.keyBindings == null) {
            return;
        }

        for (KeyBinding binding : minecraft.gameSettings.keyBindings) {
            if (binding == null) {
                continue;
            }
            int keyCode = binding.getKeyCode();
            if (keyCode != 0 && isPhysicalInputDown(keyCode)) {
                blockedUntilRelease.add(keyCode);
            }
        }
    }

    private void suppressBlockedBindingsUntilReleased() {
        if (blockedUntilRelease.isEmpty()) {
            return;
        }

        Iterator<Integer> iterator = blockedUntilRelease.iterator();
        while (iterator.hasNext()) {
            int keyCode = iterator.next();
            if (!isPhysicalInputDown(keyCode)) {
                iterator.remove();
                continue;
            }

            KeyBinding.setKeyBindState(keyCode, false);
            drainQueuedPresses(keyCode);
        }
    }

    private void drainQueuedPresses(int keyCode) {
        if (minecraft.gameSettings == null || minecraft.gameSettings.keyBindings == null) {
            return;
        }

        for (KeyBinding binding : minecraft.gameSettings.keyBindings) {
            if (binding == null || binding.getKeyCode() != keyCode) {
                continue;
            }
            while (binding.isPressed()) {
                // Consume queued presses for a key that belongs to the replay
                // input cut. It becomes eligible again after physical release.
            }
        }
    }

    private static boolean isPhysicalInputDown(int keyCode) {
        try {
            if (keyCode < 0) {
                int mouseButton = keyCode + 100;
                return mouseButton >= 0 && Mouse.isButtonDown(mouseButton);
            }
            return keyCode > 0
                    && keyCode < Keyboard.KEYBOARD_SIZE
                    && Keyboard.isKeyDown(keyCode);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
