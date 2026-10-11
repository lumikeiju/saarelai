// SPDX-License-Identifier: GPL-2.0-or-later

package org.openstreetmap.josm.plugins.easypan;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;

/** JOSM preferences tab for Easy Pan. */
public final class EasyPanPreferencesDialog extends DefaultTabPreferenceSetting {

	private JTextField activationKeyField;
	private int activationKeyCode;
	private JCheckBox invert;
	private JCheckBox throwEnabled;
	private JSpinner gain;
	private JSpinner exponent;
	private JSpinner maxSpeed;
	private JSpinner deadZone;
	private JSpinner throwFalloffMultiplier;
	private JSpinner throwFalloffExponent;
	private JSpinner throwMinimumSpeed;

	public EasyPanPreferencesDialog() {
		super("plugin", tr("Easy Pan"),
				tr("Settings for the Easy Pan map navigation plugin"));
	}

	@Override
	public void addGui(PreferenceTabbedPane gui) {
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 6, 4, 6);
		constraints.anchor = GridBagConstraints.WEST;

		activationKeyCode = EasyPanPreferences.getActivationKey();
		activationKeyField = new JTextField();
		activationKeyField.setEditable(false);
		activationKeyField.setFocusable(true);
		activationKeyField.setToolTipText(tr("Click here and press the key you want to use."));
		activationKeyField.addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				activationKeyField.setText(tr("Press a key"));
			}
		});
		activationKeyField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
					activationKeyCode = EasyPanPreferences.DEFAULT_ACTIVATION_KEY;
					updateActivationKeyDisplay();
				} else if (e.getKeyCode() != KeyEvent.VK_UNDEFINED) {
					activationKeyCode = e.getKeyCode();
					updateActivationKeyDisplay();
				}
				e.consume();
			}
		});
		updateActivationKeyDisplay();
		addSettingRow(panel, constraints, 0, tr("Activation key:"), activationKeyField,
				tr("Choose the key that starts panning. Default: Space."));

		invert = new JCheckBox(tr("Invert pan direction"));
		invert.setSelected(EasyPanPreferences.getInvert());
		addCheckboxRow(panel, constraints, 2, invert,
				tr("Reverses pan direction. Default: off."));

		throwEnabled = new JCheckBox(tr("Enable throw after release"));
		throwEnabled.setSelected(EasyPanPreferences.getThrowEnabled());
		addCheckboxRow(panel, constraints, 4, throwEnabled,
				tr("Keeps moving after release. Default: on."));

		gain = spinner(EasyPanPreferences.getGain(), 0.01, 100.0, 0.1);
		addSettingRow(panel, constraints, 6, tr("Speed sensitivity:"), gain,
				tr("Higher values make the map move faster. Range: 0.01–100. Default: 2.5."));

		exponent = spinner(EasyPanPreferences.getExponent(), 0.25, 3.0, 0.05);
		addSettingRow(panel, constraints, 8, tr("Response exponent:"), exponent,
				tr("Higher values make the response curve steeper. Range: 0.25–3.0. Default: 1.0."));

		maxSpeed = spinner(EasyPanPreferences.getMaxSpeed(), 1.0, 10_000.0, 50.0);
		addSettingRow(panel, constraints, 10, tr("Maximum speed (px/s):"), maxSpeed,
				tr("Higher values allow faster panning. Range: 1–10000. Default: 1600."));

		deadZone = spinner(EasyPanPreferences.getDeadZone(), 0.0, 500.0, 1.0);
		addSettingRow(panel, constraints, 12, tr("Dead zone (px):"), deadZone,
				tr("Higher values make the map stay still longer. Range: 0–500. Default: 8."));

		throwFalloffMultiplier = spinner(EasyPanPreferences.getThrowFalloffMultiplier(), 0.1, 0.99, 0.01);
		addSettingRow(panel, constraints, 14, tr("Throw falloff multiplier:"), throwFalloffMultiplier,
				tr("Higher values keep throw momentum going longer. Range: 0.1–0.99. Default: 0.86."));

		throwFalloffExponent = spinner(EasyPanPreferences.getThrowFalloffExponent(), 0.25, 3.0, 0.05);
		addSettingRow(panel, constraints, 16, tr("Throw falloff exponent:"), throwFalloffExponent,
				tr("Higher values make throw decay slower. Range: 0.25–3.0. Default: 1.0."));

		throwMinimumSpeed = spinner(EasyPanPreferences.getThrowMinimumSpeed(), 0.0, 1_000.0, 1.0);
		addSettingRow(panel, constraints, 18, tr("Throw minimum speed (px/s):"), throwMinimumSpeed,
				tr("Higher values stop throw motion later. Range: 0–1000. Default: 8."));

		JButton resetButton = new JButton(tr("Reset all to default"));
		resetButton.addActionListener(e -> resetToDefaults());
		constraints.gridx = 0;
		constraints.gridy = 20;
		constraints.gridwidth = 2;
		constraints.insets = new Insets(12, 6, 4, 6);
		panel.add(resetButton, constraints);
		constraints.gridwidth = 1;
		constraints.insets = new Insets(4, 6, 4, 6);

		gui.createPreferenceTab(this).add(panel, constraints);
	}

	private static JSpinner spinner(double value, double minimum, double maximum, double step) {
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
		spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.##"));
		configureControl(spinner);
		return spinner;
	}

	private static void addSettingRow(JPanel panel, GridBagConstraints constraints, int row,
			String label, java.awt.Component component, String helpText) {
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.gridwidth = 1;
		constraints.fill = GridBagConstraints.NONE;
		panel.add(new JLabel(label), constraints);
		constraints.gridx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1.0;
		configureControl(component);
		panel.add(component, constraints);
		constraints.gridx = 0;
		constraints.gridy = row + 1;
		constraints.gridwidth = 2;
		constraints.weightx = 0.0;
		constraints.fill = GridBagConstraints.NONE;
		panel.add(new JLabel(helpText), constraints);
		constraints.gridwidth = 1;
	}

	private static void addCheckboxRow(JPanel panel, GridBagConstraints constraints, int row,
			JCheckBox checkbox, String helpText) {
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.gridwidth = 2;
		configureControl(checkbox);
		panel.add(checkbox, constraints);
		constraints.gridx = 0;
		constraints.gridy = row + 1;
		constraints.gridwidth = 2;
		panel.add(new JLabel(helpText), constraints);
		constraints.gridwidth = 1;
	}

	private static void configureControl(java.awt.Component component) {
		component.setPreferredSize(new Dimension(220, 24));
		component.setMinimumSize(new Dimension(220, 24));
	}

	private void updateActivationKeyDisplay() {
		activationKeyField.setText(getKeyDisplayName(activationKeyCode));
	}

	private static String getKeyDisplayName(int keyCode) {
		if (keyCode == KeyEvent.VK_UNDEFINED) {
			return tr("Press a key");
		}
		return KeyEvent.getKeyText(keyCode);
	}

	private void resetToDefaults() {
		EasyPanPreferences.resetToDefaults();
		activationKeyCode = EasyPanPreferences.getActivationKey();
		updateActivationKeyDisplay();
		invert.setSelected(EasyPanPreferences.getInvert());
		throwEnabled.setSelected(EasyPanPreferences.getThrowEnabled());
		gain.setValue(EasyPanPreferences.getGain());
		exponent.setValue(EasyPanPreferences.getExponent());
		maxSpeed.setValue(EasyPanPreferences.getMaxSpeed());
		deadZone.setValue(EasyPanPreferences.getDeadZone());
		throwFalloffMultiplier.setValue(EasyPanPreferences.getThrowFalloffMultiplier());
		throwFalloffExponent.setValue(EasyPanPreferences.getThrowFalloffExponent());
		throwMinimumSpeed.setValue(EasyPanPreferences.getThrowMinimumSpeed());
	}

	@Override
	public boolean ok() {
		EasyPanPreferences.setActivationKey(activationKeyCode);
		EasyPanPreferences.setInvert(invert.isSelected());
		EasyPanPreferences.setThrowEnabled(throwEnabled.isSelected());
		EasyPanPreferences.setGain(((Number) gain.getValue()).doubleValue());
		EasyPanPreferences.setExponent(((Number) exponent.getValue()).doubleValue());
		EasyPanPreferences.setMaxSpeed(((Number) maxSpeed.getValue()).doubleValue());
		EasyPanPreferences.setDeadZone(((Number) deadZone.getValue()).doubleValue());
		EasyPanPreferences.setThrowFalloffMultiplier(((Number) throwFalloffMultiplier.getValue()).doubleValue());
		EasyPanPreferences.setThrowFalloffExponent(((Number) throwFalloffExponent.getValue()).doubleValue());
		EasyPanPreferences.setThrowMinimumSpeed(((Number) throwMinimumSpeed.getValue()).doubleValue());
		return false;
	}

}
