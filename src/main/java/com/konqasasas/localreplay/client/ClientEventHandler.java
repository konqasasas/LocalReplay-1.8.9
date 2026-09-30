package com.konqasasas.localreplay.client;

import com.konqasasas.localreplay.LocalReplayMod;
import com.konqasasas.localreplay.gui.screen.ReplayLibraryScreen;
import com.konqasasas.localreplay.gui.screen.ReplayScreen;
import com.konqasasas.localreplay.input.LocalReplayKeyBindings;
import com.konqasasas.localreplay.replay.ReplayController;
import com.konqasasas.localreplay.replay.data.ReplayData;
import com.konqasasas.localreplay.replay.data.ReplayTick;
import com.konqasasas.localreplay.replay.record.ReplayRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;

/**
 * Client-only event bridge. Bridges recorder/playback and opens the retained Replay screen.
 */
public final class ClientEventHandler {

    private final Minecraft minecraft;
    private final ReplayRecorder recorder;
    private final ReplayController replayController;
    private final Logger logger;
    private ReplayData latestSnapshot;

    private boolean openReplayWasDown;
    private boolean openLibraryWasDown;

    // Shift+F3's profiler pie is rendered by Minecraft after GuiScreen drawing,
    // outside Forge's normal HUD overlay path. Preserve its pre-replay state but
    // suppress it while replay is active so it cannot cover the editor. Plain
    // F3 and Alt+F3 remain available through the replay debug compositor.
    private boolean profilerChartStateCaptured;
    private boolean profilerChartStateBeforeReplay;

    public ClientEventHandler(
            Minecraft minecraft,
            ReplayRecorder recorder,
            ReplayController replayController,
            Logger logger
    ) {
        if (minecraft == null) throw new IllegalArgumentException("minecraft cannot be null");
        if (recorder == null) throw new IllegalArgumentException("recorder cannot be null");
        if (replayController == null) throw new IllegalArgumentException("replayController cannot be null");
        this.minecraft = minecraft;
        this.recorder = recorder;
        this.replayController = replayController;
        this.logger = logger;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // Minecraft 1.8.9 has no Forge InputUpdateEvent. Cut KeyBinding,
            // MovementInput, and inherited movement-intent state before the
            // player tick rebuilds/consumes them.
            replayController.updateInputIsolationBeforeClientTick();
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        // Defensive post-tick neutralization for 1.8.9. Never touches player
        // position/rotation/motion; only transient input intent.
        replayController.updateInputIsolationAfterClientTick();
        recorder.tick();
        replayController.tick();
        updateReplayDebugProfilerPolicy();

        if (!replayController.isActive() && minecraft.currentScreen instanceof ReplayScreen) {
            minecraft.displayGuiScreen(null);
        }

        boolean openReplayDown = LocalReplayKeyBindings.OPEN_LOCAL_REPLAY.isKeyDown();
        if (openReplayDown && !openReplayWasDown && !replayController.isActive()) {
            openLocalReplay();
        }
        openReplayWasDown = openReplayDown;

        boolean openLibraryDown = LocalReplayKeyBindings.OPEN_REPLAY_LIBRARY.isKeyDown();
        if (openLibraryDown && !openLibraryWasDown && !replayController.isActive()) {
            openReplayLibrary();
        }
        openLibraryWasDown = openLibraryDown;
    }



    /**
     * Replay camera lifetime is tied to explicitly registered replay UI screen
     * instances. If a vanilla/mod GUI transition escapes that ownership graph
     * (including GuiControls -> null via Esc), tear the replay session down
     * immediately. ReplayController.tick() repeats this as a safety net.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGuiOpen(GuiOpenEvent event) {
        if (!replayController.isActive()) {
            return;
        }

        GuiScreen nextScreen = event.gui;
        if (replayController.isReplayUiScreen(nextScreen)) {
            return;
        }

        logInfo("Replay UI ownership ended; cleaning up replay camera before GUI transition.");
        replayController.exitReplay();
        updateReplayDebugProfilerPolicy();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START && replayController.isActive()) {
            replayController.updateCameraForRender(event.renderTickTime);
        }
    }

    /**
     * Suppress the vanilla HUD as a whole while Local Replay is open.
     *
     * Minecraft 1.8.9 still calls the in-game HUD renderer when a GuiScreen is
     * open even if hideGUI is true, so toggling F1 cannot reliably keep the
     * hotbar out of the framebuffer. Cancelling the ALL pre-event prevents the
     * HUD from being composited at all. ReplayScreen then captures the already
     * completed world frame in drawScreen(), after this cancelled HUD stage and
     * before Local Replay draws its own UI. If F3 is enabled, ReplayScreen
     * explicitly re-composes only vanilla's debug overlay on the replay camera.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderGameOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (replayController.isActive()
                && minecraft.currentScreen instanceof ReplayScreen
                && event.type == RenderGameOverlayEvent.ElementType.ALL) {
            event.setCanceled(true);
        }
    }

    public ReplayData getLatestSnapshot() {
        return latestSnapshot;
    }

    private void openLocalReplay() {
        if (!LocalReplayMod.getConfig().isRecording()) {
            logInfo("Local Replay skipped: recording is disabled.");
            return;
        }

        GuiScreen returnScreen = minecraft.currentScreen;
        // v1 explicitly supports normal gameplay and the death screen. Other
        // Minecraft GUIs remain in control and do not open Local Replay.
        if (returnScreen != null && !(returnScreen instanceof GuiGameOver)) {
            return;
        }

        ReplayData snapshot = recorder.createSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            logInfo("Local Replay skipped: no replay data available.");
            return;
        }

        latestSnapshot = snapshot;
        logSnapshot(snapshot);

        if (replayController.openLocalReplay(snapshot, returnScreen)) {
            updateReplayDebugProfilerPolicy();
            minecraft.displayGuiScreen(new ReplayScreen(replayController));
        }
    }

    private void openReplayLibrary() {
        if (minecraft.theWorld == null || minecraft.thePlayer == null) {
            logInfo("Replay Library skipped: enter a world first.");
            return;
        }

        GuiScreen returnScreen = minecraft.currentScreen;
        // Avoid stealing ordinary GUI typing (chat, inventory, mod screens).
        // Death Screen remains supported just like the G replay shortcut.
        if (returnScreen != null && !(returnScreen instanceof GuiGameOver)) {
            return;
        }

        minecraft.displayGuiScreen(new ReplayLibraryScreen(replayController, returnScreen));
    }

    private void updateReplayDebugProfilerPolicy() {
        if (replayController.isActive()) {
            if (!profilerChartStateCaptured) {
                profilerChartStateBeforeReplay = minecraft.gameSettings.showDebugProfilerChart;
                profilerChartStateCaptured = true;
            }
            // Minecraft renders this chart after EntityRenderer/GuiScreen, so it
            // cannot be safely clipped into the replay viewport in Phase 7A.
            minecraft.gameSettings.showDebugProfilerChart = false;
            return;
        }

        if (profilerChartStateCaptured) {
            minecraft.gameSettings.showDebugProfilerChart = profilerChartStateBeforeReplay;
            profilerChartStateCaptured = false;
        }
    }

    private void logSnapshot(ReplayData snapshot) {
        ReplayTick first = snapshot.getTick(0);
        ReplayTick middle = snapshot.getTick(snapshot.size() / 2);
        ReplayTick last = snapshot.getTick(snapshot.size() - 1);

        int ticksWithInput = 0;
        for (int i = 0; i < snapshot.size(); i++) {
            if ((snapshot.getTick(i).getInputFlags() & 0xFF) != 0) {
                ticksWithInput++;
            }
        }

        logInfo(
                "Local Replay snapshot captured: " + snapshot.size()
                        + " ticks. first=(" + first.getX() + ", " + first.getY() + ", " + first.getZ() + ")"
                        + ", middle=(" + middle.getX() + ", " + middle.getY() + ", " + middle.getZ() + ")"
                        + ", last=(" + last.getX() + ", " + last.getY() + ", " + last.getZ() + ")"
                        + ", inputTicks=" + ticksWithInput
        );
    }

    private void logInfo(String message) {
        if (logger != null) {
            logger.info(message);
        }
    }
}
