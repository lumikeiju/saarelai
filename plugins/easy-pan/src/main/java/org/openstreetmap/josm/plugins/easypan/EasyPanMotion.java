// SPDX-License-Identifier: GPL-2.0-or-later

package org.openstreetmap.josm.plugins.easypan;

import java.awt.geom.Point2D;

/** Pure screen-space motion calculations for Easy Pan. */
final class EasyPanMotion {

    private EasyPanMotion() {
        // Utility class.
    }

    /**
     * Calculates the scalar speed for an anchor displacement.
     *
     * @param displacementPixels Euclidean distance from the anchor
     * @param gain               speed gain
     * @param exponent           response exponent
     * @param maxSpeed           maximum speed in screen pixels per second
     * @param deadZone           stationary radius in screen pixels
     * @return speed in screen pixels per second
     */
    static double speedPixelsPerSecond(double displacementPixels, double gain,
            double exponent, double maxSpeed,
            double deadZone) {
        double outsideDeadZone = Math.max(0.0, displacementPixels - Math.max(0.0, deadZone));
        if (outsideDeadZone == 0.0) {
            return 0.0;
        }
        double speed = gain * Math.pow(outsideDeadZone, exponent);
        if (!Double.isFinite(speed) || speed < 0.0) {
            return 0.0;
        }
        return Math.min(speed, Math.max(0.0, maxSpeed));
    }

    /**
     * Returns the viewport displacement for one elapsed interval.
     * The result is in screen pixels: positive x follows the hand-drag map
     * direction (the viewport moves opposite to the pointer displacement).
     */
    static Point2D.Double viewportDeltaPixels(double dx, double dy, double speed,
            double elapsedSeconds) {
        double distance = Math.hypot(dx, dy);
        if (distance == 0.0 || speed <= 0.0 || elapsedSeconds <= 0.0) {
            return new Point2D.Double();
        }
        double distanceMoved = speed * elapsedSeconds;
        return new Point2D.Double(
                -dx / distance * distanceMoved,
                -dy / distance * distanceMoved);
    }

    /**
     * Converts a viewport delta into a velocity expressed in pixels per second.
     */
    static Point2D.Double velocityPixelsPerSecond(Point2D.Double viewportDeltaPixels,
            double elapsedSeconds) {
        if (viewportDeltaPixels == null || elapsedSeconds <= 0.0) {
            return new Point2D.Double();
        }
        return new Point2D.Double(
                viewportDeltaPixels.x / elapsedSeconds,
                viewportDeltaPixels.y / elapsedSeconds);
    }

    /**
     * Decays a velocity vector for a throw effect.
     *
     * @param velocity current velocity in screen pixels per second
     * @param falloffMultiplier multiplier applied each frame to the throw
     * @param falloffExponent exponent shaping the falloff curve
     * @param elapsedSeconds elapsed time since the previous update
     * @param minimumSpeed minimum speed below which the throw stops
     * @return decayed velocity in screen pixels per second
     */
    static Point2D.Double decayVelocityPixelsPerSecond(Point2D.Double velocity,
            double falloffMultiplier, double falloffExponent,
            double elapsedSeconds, double minimumSpeed) {
        double magnitude = Math.hypot(velocity.x, velocity.y);
        if (magnitude <= 0.0 || elapsedSeconds <= 0.0) {
            return new Point2D.Double();
        }
        double falloffFactor = Math.pow(Math.max(0.0, falloffMultiplier),
                Math.max(0.0, elapsedSeconds) * 60.0);
        double curveFactor = Math.max(0.0, falloffExponent);
        double decayedMagnitude = magnitude * falloffFactor * curveFactor;
        if (decayedMagnitude < Math.max(0.0, minimumSpeed)) {
            return new Point2D.Double();
        }
        double scale = decayedMagnitude / magnitude;
        return new Point2D.Double(velocity.x * scale, velocity.y * scale);
    }
}
