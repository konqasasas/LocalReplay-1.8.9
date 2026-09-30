package com.konqasasas.localreplay.replay.hud;

import com.konqasasas.localreplay.replay.playback.ReplayPlayback;

/**
 * Draws one compatibility HUD in vanilla scaled-GUI coordinates.
 *
 * Viewport fitting/scissoring is handled centrally by ReplayHudManager so
 * adapters only need to reproduce their source HUD layout.
 */
public interface ReplayHudAdapter {
    String getId();

    boolean isActive();

    /**
     * Whether this source HUD normally remains visible while vanilla F3 is open.
     * CYV/MPK use the default false. Future adapters may opt in when the source HUD does.
     */
    default boolean isVisibleInDebug() {
        return false;
    }

    void render(ReplayPlayback playback, int sourceWidth, int sourceHeight, float partialTicks);
}
