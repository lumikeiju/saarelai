// SPDX-License-Identifier: GPL-2.0-or-later

package org.openstreetmap.josm.plugins.easypan;

import org.openstreetmap.josm.spi.preferences.Config;

/** Persistent settings used by Easy Pan. */
public final class EasyPanPreferences {

	public static final String KEY_ACTIVATION_KEY = "easy_pan.activation_key";
	public static final String KEY_INVERT = "easy_pan.invert";
	public static final String KEY_GAIN = "easy_pan.gain";
	public static final String KEY_EXPONENT = "easy_pan.exponent";
	public static final String KEY_MAX_SPEED = "easy_pan.max_speed";
	public static final String KEY_DEAD_ZONE = "easy_pan.dead_zone";
	public static final String KEY_THROW_ENABLED = "easy_pan.throw_enabled";
	public static final String KEY_THROW_FALLOFF_MULTIPLIER = "easy_pan.throw_falloff_multiplier";
	public static final String KEY_THROW_FALLOFF_EXPONENT = "easy_pan.throw_falloff_exponent";
	public static final String KEY_THROW_MINIMUM_SPEED = "easy_pan.throw_minimum_speed";

	public static final int DEFAULT_ACTIVATION_KEY = java.awt.event.KeyEvent.VK_SPACE;
	public static final boolean DEFAULT_INVERT = false;
	public static final double DEFAULT_GAIN = 2.5;
	public static final double DEFAULT_EXPONENT = 1.0;
	public static final double DEFAULT_MAX_SPEED = 1600.0;
	public static final double DEFAULT_DEAD_ZONE = 8.0;
	public static final boolean DEFAULT_THROW_ENABLED = true;
	public static final double DEFAULT_THROW_FALLOFF_MULTIPLIER = 0.86;
	public static final double DEFAULT_THROW_FALLOFF_EXPONENT = 1.0;
	public static final double DEFAULT_THROW_MINIMUM_SPEED = 8.0;

	private EasyPanPreferences() {
		// Utility class.
	}

	public static int getActivationKey() {
		return Config.getPref().getInt(KEY_ACTIVATION_KEY, DEFAULT_ACTIVATION_KEY);
	}

	public static void setActivationKey(int keyCode) {
		Config.getPref().putInt(KEY_ACTIVATION_KEY, keyCode);
	}

	/** Returns whether pointer movement should pan in the opposite direction. */
	public static boolean getInvert() {
		return Config.getPref().getBoolean(KEY_INVERT, DEFAULT_INVERT);
	}

	public static void setInvert(boolean invert) {
		Config.getPref().putBoolean(KEY_INVERT, invert);
	}

	/** Returns the speed gain in screen pixels per second per pixel^exponent. */
	public static double getGain() {
		return Config.getPref().getDouble(KEY_GAIN, DEFAULT_GAIN);
	}

	public static void setGain(double gain) {
		Config.getPref().putDouble(KEY_GAIN, gain);
	}

	public static double getExponent() {
		return Config.getPref().getDouble(KEY_EXPONENT, DEFAULT_EXPONENT);
	}

	public static void setExponent(double exponent) {
		Config.getPref().putDouble(KEY_EXPONENT, exponent);
	}

	/** Returns the maximum pan speed in screen pixels per second. */
	public static double getMaxSpeed() {
		return Config.getPref().getDouble(KEY_MAX_SPEED, DEFAULT_MAX_SPEED);
	}

	public static void setMaxSpeed(double maxSpeed) {
		Config.getPref().putDouble(KEY_MAX_SPEED, maxSpeed);
	}

	/** Returns the radius, in screen pixels, inside which the map is stationary. */
	public static double getDeadZone() {
		return Config.getPref().getDouble(KEY_DEAD_ZONE, DEFAULT_DEAD_ZONE);
	}

	public static void setDeadZone(double deadZone) {
		Config.getPref().putDouble(KEY_DEAD_ZONE, deadZone);
	}

	/** Returns whether momentum should continue after the gesture is released. */
	public static boolean getThrowEnabled() {
		return Config.getPref().getBoolean(KEY_THROW_ENABLED, DEFAULT_THROW_ENABLED);
	}

	public static void setThrowEnabled(boolean enabled) {
		Config.getPref().putBoolean(KEY_THROW_ENABLED, enabled);
	}

	/** Returns the per-frame multiplier applied to throw momentum. */
	public static double getThrowFalloffMultiplier() {
		return Config.getPref().getDouble(KEY_THROW_FALLOFF_MULTIPLIER, DEFAULT_THROW_FALLOFF_MULTIPLIER);
	}

	public static void setThrowFalloffMultiplier(double multiplier) {
		Config.getPref().putDouble(KEY_THROW_FALLOFF_MULTIPLIER, multiplier);
	}

	/** Returns the exponent shaping the throw falloff curve. */
	public static double getThrowFalloffExponent() {
		return Config.getPref().getDouble(KEY_THROW_FALLOFF_EXPONENT, DEFAULT_THROW_FALLOFF_EXPONENT);
	}

	public static void setThrowFalloffExponent(double exponent) {
		Config.getPref().putDouble(KEY_THROW_FALLOFF_EXPONENT, exponent);
	}

	/** Returns the minimum speed below which throw motion stops. */
	public static double getThrowMinimumSpeed() {
		return Config.getPref().getDouble(KEY_THROW_MINIMUM_SPEED, DEFAULT_THROW_MINIMUM_SPEED);
	}

	public static void setThrowMinimumSpeed(double minimumSpeed) {
		Config.getPref().putDouble(KEY_THROW_MINIMUM_SPEED, minimumSpeed);
	}

	/** Resets all Easy Pan preferences to their defaults. */
	public static void resetToDefaults() {
		setActivationKey(DEFAULT_ACTIVATION_KEY);
		setInvert(DEFAULT_INVERT);
		setGain(DEFAULT_GAIN);
		setExponent(DEFAULT_EXPONENT);
		setMaxSpeed(DEFAULT_MAX_SPEED);
		setDeadZone(DEFAULT_DEAD_ZONE);
		setThrowEnabled(DEFAULT_THROW_ENABLED);
		setThrowFalloffMultiplier(DEFAULT_THROW_FALLOFF_MULTIPLIER);
		setThrowFalloffExponent(DEFAULT_THROW_FALLOFF_EXPONENT);
		setThrowMinimumSpeed(DEFAULT_THROW_MINIMUM_SPEED);
	}
}
