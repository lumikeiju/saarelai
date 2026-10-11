// SPDX-License-Identifier: GPL-2.0-or-later

package org.openstreetmap.josm.plugins.easypan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.Point2D;

import org.junit.jupiter.api.Test;

class EasyPanMotionTest {

    @Test
    void decayVelocityUsesMultiplierAndExponent() {
        Point2D.Double velocity = new Point2D.Double(100.0, 50.0);

        Point2D.Double decayed = EasyPanMotion.decayVelocityPixelsPerSecond(
                velocity, 1.0, 0.5, 1.0, 0.0);

        assertEquals(50.0, decayed.x, 1e-9);
        assertEquals(25.0, decayed.y, 1e-9);
    }

    @Test
    void decayVelocityStopsBelowMinimumSpeed() {
        Point2D.Double velocity = new Point2D.Double(4.0, 4.0);

        Point2D.Double decayed = EasyPanMotion.decayVelocityPixelsPerSecond(
                velocity, 0.1, 0.5, 1.0, 5.0);

        assertEquals(0.0, decayed.x, 1e-9);
        assertEquals(0.0, decayed.y, 1e-9);
    }

    @Test
    void velocityUsesViewportMotionDirection() {
        Point2D.Double viewportDelta = new Point2D.Double(120.0, -60.0);

        Point2D.Double velocity = EasyPanMotion.velocityPixelsPerSecond(viewportDelta, 0.5);

        assertEquals(240.0, velocity.x, 1e-9);
        assertEquals(-120.0, velocity.y, 1e-9);
    }
}
