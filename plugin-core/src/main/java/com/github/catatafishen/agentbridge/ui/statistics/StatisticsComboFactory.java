package com.github.catatafishen.agentbridge.ui.statistics;

import com.intellij.openapi.ui.ComboBox;

import javax.swing.*;
import java.awt.*;
import java.util.function.Function;

/**
 * Factory for creating labelled combo boxes used across statistics panels.
 * Eliminates duplicated renderer boilerplate.
 */
final class StatisticsComboFactory {

    private StatisticsComboFactory() {
    }

    /**
     * Creates a combo box that renders enum items via a label function.
     *
     * @param values   enum constants to populate the combo
     * @param selected initial selection
     * @param labeler  extracts display text from each enum value
     */
    static <E extends Enum<E>> ComboBox<E> createLabeledCombo(E[] values, E selected,
                                                               Function<E, String> labeler) {
        ComboBox<E> combo = new ComboBox<>(values);
        combo.setSelectedItem(selected);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            @SuppressWarnings("unchecked") // safe: combo only contains E values
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value != null) {
                    setText(labeler.apply((E) value));
                }
                return this;
            }
        });
        return combo;
    }
}
