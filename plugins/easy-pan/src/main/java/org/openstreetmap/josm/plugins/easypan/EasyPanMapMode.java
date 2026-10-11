// SPDX-License-Identifier: GPL-2.0-or-later

package org.openstreetmap.josm.plugins.easypan;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.geom.Point2D;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.util.KeyPressReleaseListener;
import org.openstreetmap.josm.gui.util.ModifierExListener;
import org.openstreetmap.josm.tools.Shortcut;

/** Map mode that continuously pans the map from pointer displacement. */
public final class EasyPanMapMode extends MapMode
        implements KeyPressReleaseListener, ModifierExListener,
        FocusListener, WindowFocusListener {

    private static final int TIMER_DELAY_MS = 16;
    private static final double MAX_FRAME_SECONDS = 0.1;

    private final Timer motionTimer;
    private Point anchor;
    private boolean active;
    private boolean activationBlocked;
    private long lastMotionNanos;
    private Point2D.Double throwVelocity;
    private java.awt.Window observedWindow;

    public EasyPanMapMode() {
        super(tr("Easy Pan"), "easypan",
                tr("Pan the map from pointer displacement while holding the activation key"),
                Shortcut.registerShortcut("mapmode:easypan",
                        tr("Mode: {0}", tr("Easy Pan")), KeyEvent.VK_P, Shortcut.DIRECT),
                Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        motionTimer = new Timer(TIMER_DELAY_MS, e -> updateMotion());
        motionTimer.setCoalesce(true);
    }

    @Override
    public void enterMode() {
        super.enterMode();
        MapFrame map = MainApplication.getMap();
        if (map == null) {
            return;
        }
        MapView mapView = map.mapView;
        mapView.addMouseListener(this);
        mapView.addMouseMotionListener(this);
        mapView.addFocusListener(this);
        map.keyDetector.addKeyListener(this);
        map.keyDetector.addModifierExListener(this);
        observedWindow = SwingUtilities.getWindowAncestor(mapView);
        if (observedWindow != null) {
            observedWindow.addWindowFocusListener(this);
        }
    }

    @Override
    public void exitMode() {
        cancelGesture();
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.removeMouseListener(this);
            map.mapView.removeMouseMotionListener(this);
            map.mapView.removeFocusListener(this);
            map.keyDetector.removeKeyListener(this);
            map.keyDetector.removeModifierExListener(this);
        }
        if (observedWindow != null) {
            observedWindow.removeWindowFocusListener(this);
            observedWindow = null;
        }
        super.exitMode();
    }

    @Override
    public boolean layerIsSupported(Layer layer) {
        return true;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (active) {
            cancelGesture();
        }
    }

    @Override
    public void doKeyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            if (active) {
                cancelGesture();
                e.consume();
            }
            return;
        }
        if (e.getKeyCode() != EasyPanPreferences.getActivationKey()
                || active || activationBlocked) {
            return;
        }
        if (startGesture()) {
            // Do not consume the configured key unless a valid map gesture
            // started; this preserves normal use in panels and text fields.
            e.consume();
        }
    }

    @Override
    public void doKeyReleased(KeyEvent e) {
        if (e.getKeyCode() != EasyPanPreferences.getActivationKey()) {
            return;
        }
        if (active) {
            releaseGesture();
            e.consume();
        }
        activationBlocked = false;
    }

    @Override
    public void modifiersExChanged(int modifiers) {
        // The activation key is intentionally independent of modifier state.
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (active) {
            updateMotion();
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (active) {
            updateMotion();
        }
    }

    private boolean startGesture() {
        MapFrame map = MainApplication.getMap();
        if (map == null || !isMapFocus(map.mapView)) {
            return false;
        }
        Point pointer = map.mapView.getMousePosition();
        if (pointer == null || !map.mapView.contains(pointer)) {
            return false;
        }
        anchor = new Point(pointer);
        throwVelocity = null;
        map.mapView.requestFocusInWindow();
        active = true;
        activationBlocked = false;
        lastMotionNanos = System.nanoTime();
        map.mapView.setNewCursor(Cursor.MOVE_CURSOR, this);
        motionTimer.start();
        return true;
    }

    private boolean isMapFocus(MapView mapView) {
        java.awt.Component focusOwner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, mapView);
    }

    private void updateMotion() {
        MapFrame map = MainApplication.getMap();
        if (map == null) {
            cancelGesture();
            return;
        }
        MapView mapView = map.mapView;

        long now = System.nanoTime();
        double elapsedSeconds = Math.min(MAX_FRAME_SECONDS,
                Math.max(0.0, (now - lastMotionNanos) / 1_000_000_000.0));
        lastMotionNanos = now;

        if (active && anchor != null) {
            Point pointer = mapView.getMousePosition();
            if (pointer == null) {
                cancelGesture();
                return;
            }

            double dx = pointer.x - anchor.x;
            double dy = pointer.y - anchor.y;
            if (EasyPanPreferences.getInvert()) {
                dx = -dx;
                dy = -dy;
            }
            double distance = Math.hypot(dx, dy);
            double speed = EasyPanMotion.speedPixelsPerSecond(distance,
                    EasyPanPreferences.getGain(), EasyPanPreferences.getExponent(),
                    EasyPanPreferences.getMaxSpeed(), EasyPanPreferences.getDeadZone());
            Point2D.Double viewportPixels = EasyPanMotion.viewportDeltaPixels(
                    dx, dy, speed, elapsedSeconds);
            if (viewportPixels.x != 0.0 || viewportPixels.y != 0.0) {
                throwVelocity = EasyPanMotion.velocityPixelsPerSecond(
                        viewportPixels, elapsedSeconds);
                double scale = mapView.getScale();
                mapView.zoomTo(mapView.getCenter().add(
                        new org.openstreetmap.josm.data.coor.EastNorth(
                                viewportPixels.x * scale,
                                -viewportPixels.y * scale)));
            }
            return;
        }

        if (!EasyPanPreferences.getThrowEnabled() || throwVelocity == null) {
            stopMotion();
            return;
        }

        Point2D.Double decayedVelocity = EasyPanMotion.decayVelocityPixelsPerSecond(
                throwVelocity,
                EasyPanPreferences.getThrowFalloffMultiplier(),
                EasyPanPreferences.getThrowFalloffExponent(),
                elapsedSeconds,
                EasyPanPreferences.getThrowMinimumSpeed());
        if (decayedVelocity.x == 0.0 && decayedVelocity.y == 0.0) {
            throwVelocity = null;
            stopMotion();
            return;
        }

        throwVelocity = decayedVelocity;
        double scale = mapView.getScale();
        mapView.zoomTo(mapView.getCenter().add(
                new org.openstreetmap.josm.data.coor.EastNorth(
                        decayedVelocity.x * elapsedSeconds * scale,
                        -decayedVelocity.y * elapsedSeconds * scale)));
    }

    private void releaseGesture() {
        if (!active) {
            return;
        }
        active = false;
        activationBlocked = true;
        anchor = null;
        if (EasyPanPreferences.getThrowEnabled() && throwVelocity != null
                && (throwVelocity.x != 0.0 || throwVelocity.y != 0.0)) {
            motionTimer.start();
        } else {
            throwVelocity = null;
            motionTimer.stop();
        }
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.resetCursor(this);
            map.mapView.repaint();
        }
    }

    private void stopMotion() {
        throwVelocity = null;
        motionTimer.stop();
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.resetCursor(this);
            map.mapView.repaint();
        }
    }

    private void cancelGesture() {
        if (!active && throwVelocity == null) {
            return;
        }
        active = false;
        activationBlocked = true;
        anchor = null;
        throwVelocity = null;
        motionTimer.stop();
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.resetCursor(this);
            map.mapView.repaint();
        }
    }

    @Override
    public void focusLost(FocusEvent e) {
        cancelGesture();
    }

    @Override
    public void focusGained(FocusEvent e) {
        // No action.
    }

    @Override
    public void windowLostFocus(WindowEvent e) {
        cancelGesture();
    }

    @Override
    public void windowGainedFocus(WindowEvent e) {
        // No action.
    }
}
