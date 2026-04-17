package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.review.ReviewItem;
import com.github.catatafishen.agentbridge.psi.review.ReviewSessionTopic;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.SideBorder;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel displaying review items from the current {@link AgentEditSession}.
 * Shows a table of files with status, and per-file accept/reject actions.
 * Toolbar provides a stop/play toggle to enable/disable diff review, plus bulk accept-all/reject-all actions.
 * <p>
 * Action buttons (view, accept, reject) are always visible in the right side of the file cell.
 * Click handling uses coordinate math in a {@link java.awt.event.MouseListener} since renderer
 * components are paint-only in JTable (not interactive).
 */
public final class ReviewChangesPanel extends JPanel implements Disposable {

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    private static final int BUTTON_AREA_WIDTH = 82;

    private final Project project;
    private final ReviewTableModel tableModel;
    private final JBTable table;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final JBLabel emptyLabel;

    public ReviewChangesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        tableModel = new ReviewTableModel();
        table = new JBTable(tableModel);
        configureTable();

        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        emptyLabel = new JBLabel("", SwingConstants.CENTER);
        emptyLabel.setForeground(JBColor.GRAY);
        JPanel emptyStatePanel = new JPanel(new GridBagLayout());
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(emptyLabel);
        emptyStatePanel.add(column);

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(scrollPane, CARD_TABLE);
        cardPanel.add(emptyStatePanel, CARD_EMPTY);

        ActionToolbar toolbar = createToolbar();
        toolbar.setTargetComponent(this);
        toolbar.getComponent().setBorder(JBUI.Borders.empty());

        JPanel toolbarFooter = new JPanel(new BorderLayout());
        toolbarFooter.setBorder(JBUI.Borders.compound(
            new SideBorder(JBColor.border(), SideBorder.TOP),
            JBUI.Borders.empty(2, 0)
        ));
        JComponent toolbarComponent = toolbar.getComponent();
        int footerHeight = JBUI.scale(32);
        toolbarComponent.setPreferredSize(new Dimension(0, footerHeight));
        toolbarComponent.setMinimumSize(new Dimension(0, footerHeight));
        toolbarFooter.add(toolbarComponent, BorderLayout.CENTER);

        add(cardPanel, BorderLayout.CENTER);
        add(toolbarFooter, BorderLayout.SOUTH);

        project.getMessageBus().connect(this).subscribe(
            ReviewSessionTopic.TOPIC,
            () -> ApplicationManager.getApplication().invokeLater(this::refresh)
        );

        refresh();
    }

    @Override
    public void dispose() {
        // Message-bus connection auto-disposes via the Disposer parent link above.
    }

    public void refresh() {
        AgentEditSession session = AgentEditSession.getInstance(project);
        List<ReviewItem> items = session.isActive() ? session.getReviewItems() : List.of();
        tableModel.setItems(items);

        if (!items.isEmpty()) {
            cardLayout.show(cardPanel, CARD_TABLE);
        } else {
            boolean reviewEnabled = McpServerSettings.getInstance(project).isReviewAgentEdits();
            if (reviewEnabled) {
                emptyLabel.setText("No agent edits to review");
            } else {
                emptyLabel.setText("<html><center>Diff Review is off.<br>Agent edits are applied directly without review.</center></html>");
            }
            cardLayout.show(cardPanel, CARD_EMPTY);
        }
        revalidate();
        repaint();
    }

    private void configureTable() {
        table.setRowHeight(JBUI.scale(32));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setTableHeader(null);
        table.setExpandableItemsEnabled(false);

        TableColumn statusCol = table.getColumnModel().getColumn(ReviewTableModel.COL_STATUS);
        statusCol.setPreferredWidth(JBUI.scale(28));
        statusCol.setMaxWidth(JBUI.scale(32));
        statusCol.setCellRenderer(new StatusCellRenderer());

        TableColumn fileCol = table.getColumnModel().getColumn(ReviewTableModel.COL_FILE);
        fileCol.setPreferredWidth(JBUI.scale(200));
        fileCol.setCellRenderer(new FileCellRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || row >= tableModel.getRowCount()) return;

                ReviewItem item = tableModel.getItem(row);
                if (col == ReviewTableModel.COL_FILE) {
                    Rectangle cellRect = table.getCellRect(row, ReviewTableModel.COL_FILE, true);
                    int relX = e.getX() - cellRect.x;
                    int buttonStart = cellRect.width - JBUI.scale(BUTTON_AREA_WIDTH);
                    if (relX >= buttonStart) {
                        handleFileActionClick(item, relX - buttonStart);
                    }
                }
            }
        });
    }

    private void handleFileActionClick(@NotNull ReviewItem item, int relXInButtonArea) {
        int avail = JBUI.scale(BUTTON_AREA_WIDTH) - JBUI.scale(8);
        int gap = JBUI.scale(2);
        int btnW = (avail - 2 * gap) / 3;
        if (relXInButtonArea < JBUI.scale(4) + btnW + gap) {
            navigateToFile(item);
        } else if (relXInButtonArea < JBUI.scale(4) + 2 * btnW + 2 * gap) {
            AgentEditSession.getInstance(project).acceptFile(item.path());
        } else {
            showRejectDialog(item);
        }
    }

    private void navigateToFile(@NotNull ReviewItem item) {
        if (item.status() == ReviewItem.Status.DELETED) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        }
    }

    private void showRejectDialog(@NotNull ReviewItem item) {
        String reason = Messages.showInputDialog(
            project,
            "Reason for rejecting changes to " + item.relativePath() + " (optional):",
            "Reject Agent Edit",
            Messages.getQuestionIcon(),
            null, null
        );
        if (reason == null) return;
        AgentEditSession.getInstance(project).rejectFile(item.path(), reason.isBlank() ? null : reason);
    }

    private @NotNull ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();

        group.add(new ToggleAction("Diff Review", "Toggle diff review for agent edits", AllIcons.Actions.Diff) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return McpServerSettings.getInstance(project).isReviewAgentEdits();
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                if (state) {
                    McpServerSettings.getInstance(project).setReviewAgentEdits(true);
                    project.getMessageBus().syncPublisher(ReviewSessionTopic.TOPIC).reviewStateChanged();
                    return;
                }
                AgentEditSession session = AgentEditSession.getInstance(project);
                if (session.isActive() && session.hasChanges()) {
                    int result = Messages.showOkCancelDialog(
                        project,
                        "You have " + session.getReviewItems().size()
                            + " unreviewed file(s). Disabling Diff Review will discard the review session.",
                        "Discard Review Session?",
                        "Discard",
                        "Cancel",
                        Messages.getWarningIcon()
                    );
                    if (result != Messages.OK) return;
                }
                boolean wasActive = session.isActive();
                McpServerSettings.getInstance(project).setReviewAgentEdits(false);
                session.endSession();
                if (!wasActive) {
                    project.getMessageBus().syncPublisher(ReviewSessionTopic.TOPIC).reviewStateChanged();
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                super.update(e);
                boolean enabled = McpServerSettings.getInstance(project).isReviewAgentEdits();
                if (enabled) {
                    e.getPresentation().setIcon(AllIcons.Actions.Suspend);
                    e.getPresentation().setText("Disable Diff Review");
                    e.getPresentation().setDescription("Disable diff review and stop tracking agent edits");
                } else {
                    e.getPresentation().setIcon(AllIcons.Actions.Execute);
                    e.getPresentation().setText("Enable Diff Review");
                    e.getPresentation().setDescription("Enable diff review to track and review agent edits");
                }
            }
        });

        group.addSeparator();

        group.add(new DumbAwareAction("Accept All", "Accept all agent edits", AllIcons.Actions.Checked) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                AgentEditSession.getInstance(project).acceptAll();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(
                    AgentEditSession.getInstance(project).isActive()
                        && AgentEditSession.getInstance(project).hasChanges()
                );
            }
        });

        group.add(new DumbAwareAction("Reject All", "Reject all agent edits and revert", AllIcons.Actions.Rollback) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                String reason = Messages.showInputDialog(
                    project,
                    "Reason for rejecting all changes (optional):",
                    "Reject All Agent Edits",
                    Messages.getWarningIcon(),
                    null, null
                );
                if (reason == null) return;
                AgentEditSession.getInstance(project).rejectAll(reason.isBlank() ? null : reason);
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(
                    AgentEditSession.getInstance(project).isActive()
                        && AgentEditSession.getInstance(project).hasChanges()
                );
            }
        });

        return ActionManager.getInstance().createActionToolbar("ReviewChangesToolbar", group, true);
    }

    // ── Table model ───────────────────────────────────────────────────────────

    private static final class ReviewTableModel extends AbstractTableModel {
        static final int COL_STATUS = 0;
        static final int COL_FILE = 1;
        private static final String[] COLUMN_NAMES = {"", "File"};

        private final List<ReviewItem> items = new ArrayList<>();

        void setItems(List<ReviewItem> newItems) {
            items.clear();
            items.addAll(newItems);
            fireTableDataChanged();
        }

        @NotNull ReviewItem getItem(int row) {
            return items.get(row);
        }

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_NAMES.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMN_NAMES[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ReviewItem item = items.get(rowIndex);
            return switch (columnIndex) {
                case COL_STATUS -> item.status().name();
                case COL_FILE -> item.relativePath();
                default -> null;
            };
        }
    }

    // ── Cell renderers ────────────────────────────────────────────────────────

    private static final class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof String status && c instanceof JLabel label) {
                label.setText("");
                label.setHorizontalAlignment(CENTER);
                switch (status) {
                    case "ADDED" -> {
                        label.setForeground(isSelected ? table.getSelectionForeground()
                            : new JBColor(new Color(0, 128, 0), new Color(80, 200, 80)));
                        label.setIcon(AllIcons.General.Add);
                        label.setToolTipText("Added");
                    }
                    case "MODIFIED" -> {
                        label.setForeground(isSelected ? table.getSelectionForeground()
                            : new JBColor(new Color(0, 100, 200), new Color(80, 160, 255)));
                        label.setIcon(AllIcons.Actions.Edit);
                        label.setToolTipText("Modified");
                    }
                    case "DELETED" -> {
                        label.setForeground(isSelected ? table.getSelectionForeground()
                            : new JBColor(new Color(200, 0, 0), new Color(255, 80, 80)));
                        label.setIcon(AllIcons.General.Remove);
                        label.setToolTipText("Deleted");
                    }
                    default -> {
                        label.setIcon(null);
                        label.setToolTipText(null);
                    }
                }
            }
            return c;
        }
    }

    /**
     * Renders the file name with always-visible action buttons on the right side of the cell.
     * Buttons are pre-created once to avoid allocation and layout jitter on every repaint.
     * Click dispatch is handled by the table's {@link MouseAdapter} using coordinate math.
     */
    private static final class FileCellRenderer extends DefaultTableCellRenderer {
        private final JPanel cellPanel = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        private final JLabel nameLabel = new JLabel();
        private final JPanel buttonsPanel = new JPanel(new GridLayout(1, 3, JBUI.scale(2), 0));

        FileCellRenderer() {
            cellPanel.setOpaque(true);
            nameLabel.setOpaque(false);

            JButton viewBtn = createButton(AllIcons.General.InspectionsEye, "View file");
            JButton acceptBtn = createButton(AllIcons.Actions.Checked, "Accept");
            JButton rejectBtn = createButton(AllIcons.Actions.Rollback, "Reject");
            buttonsPanel.add(viewBtn);
            buttonsPanel.add(acceptBtn);
            buttonsPanel.add(rejectBtn);
            buttonsPanel.setOpaque(false);
            buttonsPanel.setPreferredSize(new Dimension(JBUI.scale(BUTTON_AREA_WIDTH), 0));

            cellPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            cellPanel.add(nameLabel, BorderLayout.CENTER);
            cellPanel.add(buttonsPanel, BorderLayout.EAST);
        }

        private static JButton createButton(Icon icon, String tooltip) {
            JButton btn = new JButton(icon);
            btn.setToolTipText(tooltip);
            btn.putClientProperty("JButton.buttonType", "borderless");
            btn.setFocusPainted(false);
            btn.setContentAreaFilled(false);
            btn.setBorderPainted(false);
            btn.setOpaque(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return btn;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            String path = value instanceof String s ? s : "";
            java.nio.file.Path p = java.nio.file.Path.of(path);
            nameLabel.setText(p.getFileName() != null ? p.getFileName().toString() : path);
            nameLabel.setToolTipText(path);

            Color bg = isSelected ? table.getSelectionBackground() : table.getBackground();
            Color fg = isSelected ? table.getSelectionForeground() : table.getForeground();
            cellPanel.setBackground(bg);
            buttonsPanel.setBackground(bg);
            nameLabel.setForeground(fg);

            return cellPanel;
        }
    }
}
