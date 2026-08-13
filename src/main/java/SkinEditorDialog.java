import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

final class SkinEditorDialog extends JDialog {
    private static final int SKIN_WIDTH = 64;
    private static final int SKIN_HEIGHT = 64;
    private static final Color BG_TOP = new Color(22, 27, 34);
    private static final Color BG_BOTTOM = new Color(7, 8, 10);
    private static final Color PANEL_BG = new Color(14, 16, 20, 176);
    private static final Color PANEL_BORDER = new Color(230, 235, 242, 105);
    private static final Color ACCENT = new Color(79, 163, 88);
    private static final Color ACCENT_BLUE = new Color(119, 166, 222);
    private static final Color TEXT = new Color(244, 247, 250);
    private static final Color MUTED = new Color(198, 207, 218);
    private static final Color GRID = new Color(255, 255, 255, 70);
    private static final int MAX_UNDO_STEPS = 40;
    private static final List<Uv> BASE_RECTS = baseRects();
    private static final List<Uv> OVERLAY_RECTS = overlayRects();
    private static final Color[] SKIN_PALETTE = {
            new Color(255, 224, 189), new Color(241, 194, 125), new Color(224, 172, 105),
            new Color(198, 134, 66), new Color(141, 85, 36), new Color(94, 54, 32),
            new Color(230, 178, 132), new Color(164, 101, 64), new Color(112, 73, 52)
    };
    private static final Color[] HAIR_PALETTE = {
            new Color(34, 25, 20), new Color(74, 46, 31), new Color(105, 72, 45),
            new Color(154, 105, 52), new Color(202, 158, 86), new Color(225, 205, 156),
            new Color(90, 90, 96), new Color(170, 174, 181), new Color(232, 232, 225)
    };
    private static final Color[] CLOTHES_PALETTE = {
            new Color(35, 86, 161), new Color(36, 132, 195), new Color(27, 170, 211),
            new Color(34, 181, 121), new Color(79, 163, 88), new Color(246, 189, 96),
            new Color(230, 57, 70), new Color(138, 82, 166), new Color(40, 45, 58)
    };
    private static final Color[] DETAIL_PALETTE = {
            new Color(255, 255, 255), new Color(220, 226, 235), new Color(143, 154, 168),
            new Color(35, 32, 46), new Color(20, 22, 28), new Color(246, 225, 74),
            new Color(247, 167, 54), new Color(134, 88, 55), new Color(0, 0, 0, 0)
    };

    private final EditorCanvas canvas = new EditorCanvas();
    private final SkinMiniPreview miniPreview = new SkinMiniPreview();
    private final SkinTexturePreview texturePreview = new SkinTexturePreview();
    private final JTextField nameField = new JTextField(18);
    private final JComboBox<String> modelCombo = new JComboBox<>(new String[] {"CLASSIC", "SLIM"});
    private final JComboBox<Tool> toolCombo = new JComboBox<>(Tool.values());
    private final JComboBox<SkinLayer> layerCombo = new JComboBox<>(SkinLayer.values());
    private final JSpinner brushSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 8, 1));
    private final JCheckBox gridCheck = new JCheckBox("Сетка", true);
    private final JCheckBox mirrorCheck = new JCheckBox("Зеркало", false);
    private final JCheckBox shadeCheck = new JCheckBox("Варьировать оттенок", false);
    private final JCheckBox baseVisibleCheck = new JCheckBox("Показывать первый слой", true);
    private final JCheckBox overlayVisibleCheck = new JCheckBox("Показывать второй слой", true);
    private final JCheckBox headVisibleCheck = new JCheckBox("Голова", true);
    private final JCheckBox bodyVisibleCheck = new JCheckBox("Тело", true);
    private final JCheckBox leftArmVisibleCheck = new JCheckBox("Левая рука", true);
    private final JCheckBox rightArmVisibleCheck = new JCheckBox("Правая рука", true);
    private final JCheckBox leftLegVisibleCheck = new JCheckBox("Левая нога", true);
    private final JCheckBox rightLegVisibleCheck = new JCheckBox("Правая нога", true);
    private final JLabel colorSwatch = new JLabel();
    private final JLabel statusLabel = new JLabel("Левая кнопка рисует, правая вращает 3D-модель.");
    private final ButtonGroup modeGroup = new ButtonGroup();
    private final JToggleButton mode3dButton = new JToggleButton("3D режим");
    private final JToggleButton mode2dButton = new JToggleButton("2D режим покраски");
    private final JButton undoButton = new JButton("Отменить");
    private final DefaultListModel<SkinProjectEntry> skinProjectModel = new DefaultListModel<>();
    private final JList<SkinProjectEntry> skinProjectList = new JList<>(skinProjectModel);
    private final ArrayDeque<BufferedImage> undoStack = new ArrayDeque<>();
    private final List<Color> customPalette = new ArrayList<>();

    private BufferedImage skin;
    private Color paintColor = new Color(231, 53, 68);
    private PaintMode paintMode = PaintMode.MODE_3D;
    private SkinLayer activeLayer = SkinLayer.BASE;
    private Tool activeTool = Tool.PENCIL;
    private BufferedImage paintSnapshot;
    private boolean paintSnapshotDirty;
    private boolean switchingProjectEntry;
    private int currentProjectIndex;
    private Result result;

    SkinEditorDialog(Frame owner, BufferedImage initialSkin, boolean slim, ThemeMode theme, String initialName) {
        super(owner, "Редактор скина", true);
        this.skin = normalizeSkin(initialSkin);
        this.nameField.setText(initialName == null || initialName.isBlank() ? "edited_skin" : initialName + "_edit");
        this.modelCombo.setSelectedItem(slim ? "SLIM" : "CLASSIC");
        skinProjectModel.addElement(new SkinProjectEntry(this.nameField.getText(), deepCopy(this.skin), slim));
        buildUi(theme == ThemeMode.DARK);
        installShortcuts();
        skinProjectList.setSelectedIndex(0);
        refreshState();
        setMinimumSize(new Dimension(1080, 720));
        setSize(1260, 780);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    Result result() {
        return result;
    }

    private void buildUi(boolean darkTheme) {
        JPanel root = new GradientPanel();
        root.setLayout(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setContentPane(root);

        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(buildLeftPanel(), BorderLayout.WEST);
        root.add(canvas, BorderLayout.CENTER);
        root.add(buildRightPanel(), BorderLayout.EAST);
        root.add(buildBottomTools(), BorderLayout.SOUTH);

        if (!darkTheme) {
            statusLabel.setForeground(new Color(210, 220, 232));
        }
    }

    private void installShortcuts() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undoPaint");
        getRootPane().getActionMap().put("undoPaint", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undoLastAction();
            }
        });
    }

    private JPanel buildTopBar() {
        JPanel panel = darkPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 44)),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)
        ));

        mode3dButton.setSelected(true);
        modeGroup.add(mode3dButton);
        modeGroup.add(mode2dButton);
        styleToggle(mode3dButton);
        styleToggle(mode2dButton);
        mode3dButton.addActionListener(e -> setPaintMode(PaintMode.MODE_3D));
        mode2dButton.addActionListener(e -> setPaintMode(PaintMode.MODE_2D));
        panel.add(mode3dButton);
        panel.add(mode2dButton);
        panel.add(separator());

        JButton rotateLeft = toolbarButton("←");
        rotateLeft.setToolTipText("Повернуть модель влево");
        rotateLeft.addActionListener(e -> canvas.rotateView(-18, 0));
        JButton rotateRight = toolbarButton("→");
        rotateRight.setToolTipText("Повернуть модель вправо");
        rotateRight.addActionListener(e -> canvas.rotateView(18, 0));
        JButton resetView = toolbarButton("Сброс вида");
        resetView.addActionListener(e -> canvas.resetView());
        stylePlainButton(undoButton);
        undoButton.setEnabled(false);
        undoButton.setToolTipText("Отменить последнее рисование (Ctrl+Z)");
        undoButton.addActionListener(e -> undoLastAction());
        panel.add(rotateLeft);
        panel.add(rotateRight);
        panel.add(resetView);
        panel.add(undoButton);
        panel.add(separator());

        styleCheckBox(gridCheck);
        gridCheck.addActionListener(e -> canvas.repaint());
        styleCheckBox(mirrorCheck);
        styleCheckBox(shadeCheck);
        panel.add(gridCheck);
        panel.add(mirrorCheck);
        panel.add(shadeCheck);
        panel.add(separator());

        JButton loadButton = toolbarButton("Загрузить PNG");
        loadButton.addActionListener(e -> loadSkin());
        JButton newButton = toolbarButton("Новый");
        newButton.addActionListener(e -> createNewSkin());
        JButton savePngButton = toolbarButton("Сохранить PNG");
        savePngButton.addActionListener(e -> savePng());
        JButton applyButton = accentButton("В библиотеку");
        applyButton.addActionListener(e -> applyToLibrary());
        panel.add(loadButton);
        panel.add(newButton);
        panel.add(savePngButton);
        panel.add(applyButton);

        return panel;
    }

    private JPanel buildLeftPanel() {
        JPanel panel = darkPanel(new BorderLayout(0, 10));
        panel.setPreferredSize(new Dimension(276, 10));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PANEL_BORDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));

        JLabel title = titleLabel("Скины проекта");
        panel.add(title, BorderLayout.NORTH);

        skinProjectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skinProjectList.setVisibleRowCount(8);
        skinProjectList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value == null ? "" : value.name());
            label.setOpaque(true);
            label.setForeground(TEXT);
            label.setBackground(isSelected ? new Color(70, 86, 108) : new Color(25, 31, 42));
            label.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
            return label;
        });
        skinProjectList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                switchProjectEntry(skinProjectList.getSelectedIndex());
            }
        });
        JScrollPane listScroll = new JScrollPane(skinProjectList);
        listScroll.setOpaque(false);
        listScroll.getViewport().setOpaque(false);
        listScroll.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 50)));

        JPanel buttons = new JPanel(new GridLayout(0, 2, 8, 8));
        buttons.setOpaque(false);
        JButton duplicateButton = toolbarButton("Дубликат");
        duplicateButton.addActionListener(e -> duplicateCurrentSkin());
        JButton templateButton = toolbarButton("Шаблон");
        templateButton.addActionListener(e -> addTemplateSkin());
        JButton importButton = toolbarButton("Импорт");
        importButton.addActionListener(e -> loadSkinAsNewEntry());
        JButton removeButton = toolbarButton("Удалить");
        removeButton.addActionListener(e -> removeCurrentSkin());
        JButton mixButton = accentButton("Смешать");
        mixButton.setToolTipText("Смешать текущий скин с выбранным в списке");
        mixButton.addActionListener(e -> mixCurrentSkinWithSelected());
        JButton syncButton = toolbarButton("Запомнить");
        syncButton.setToolTipText("Сохранить текущие пиксели и имя в выбранный слот");
        syncButton.addActionListener(e -> syncCurrentEntry());
        buttons.add(duplicateButton);
        buttons.add(templateButton);
        buttons.add(importButton);
        buttons.add(removeButton);
        buttons.add(mixButton);
        buttons.add(syncButton);

        JPanel projectPanel = new JPanel(new BorderLayout(0, 10));
        projectPanel.setOpaque(false);
        projectPanel.add(listScroll, BorderLayout.CENTER);
        projectPanel.add(buttons, BorderLayout.SOUTH);
        panel.add(projectPanel, BorderLayout.CENTER);

        JLabel hint = new JLabel("<html><body style='width:230px'>Добавляйте несколько скинов, переключайтесь между ними и смешивайте текущий с выбранным. Рисование работает по 3D модели или PNG-развертке.</body></html>");
        hint.setForeground(MUTED);
        hint.setFont(hint.getFont().deriveFont(12f));
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildRightPanel() {
        JPanel panel = darkPanel(new BorderLayout(0, 10));
        panel.setPreferredSize(new Dimension(268, 10));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PANEL_BORDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 0);
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        form.add(titleLabel("Скин"), c);
        c.gridy++;
        form.add(fieldLabel("Название"), c);
        c.gridy++;
        styleTextField(nameField);
        form.add(nameField, c);
        c.gridy++;
        form.add(fieldLabel("Модель"), c);
        c.gridy++;
        styleCombo(modelCombo);
        modelCombo.addActionListener(e -> {
            refreshState();
            canvas.repaint();
        });
        form.add(modelCombo, c);
        c.gridy++;
        form.add(fieldLabel("Слой"), c);
        c.gridy++;
        layerCombo.setModel(new DefaultComboBoxModel<>(SkinLayer.values()));
        styleCombo(layerCombo);
        layerCombo.addActionListener(e -> {
            Object selected = layerCombo.getSelectedItem();
            if (selected instanceof SkinLayer layer) {
                activeLayer = layer;
                statusLabel.setText(layer == SkinLayer.BASE
                        ? "Редактируется основной слой тела."
                        : "Редактируется второй слой: одежда, волосы, аксессуары.");
                canvas.repaint();
            }
        });
        form.add(layerCombo, c);
        c.gridy++;

        styleCheckBox(baseVisibleCheck);
        baseVisibleCheck.addActionListener(e -> refreshState());
        form.add(baseVisibleCheck, c);
        c.gridy++;

        styleCheckBox(overlayVisibleCheck);
        overlayVisibleCheck.addActionListener(e -> refreshState());
        form.add(overlayVisibleCheck, c);
        c.gridy++;

        JLabel previewLabel = fieldLabel("Предпросмотр");
        form.add(previewLabel, c);
        c.gridy++;
        miniPreview.setPreferredSize(new Dimension(210, 150));
        form.add(miniPreview, c);
        c.gridy++;

        form.add(fieldLabel("Вся PNG-развертка"), c);
        c.gridy++;
        texturePreview.setPreferredSize(new Dimension(210, 210));
        form.add(texturePreview, c);
        c.gridy++;

        form.add(fieldLabel("Видимость частей"), c);
        c.gridy++;
        JPanel parts = new JPanel(new GridLayout(0, 2, 4, 2));
        parts.setOpaque(false);
        JCheckBox[] bodyChecks = {
                headVisibleCheck, bodyVisibleCheck, leftArmVisibleCheck,
                rightArmVisibleCheck, leftLegVisibleCheck, rightLegVisibleCheck
        };
        for (JCheckBox check : bodyChecks) {
            styleCheckBox(check);
            check.addActionListener(e -> refreshState());
            parts.add(check);
        }
        form.add(parts, c);
        c.gridy++;

        JLabel help = new JLabel("<html><body style='width:220px'>Первый слой меняет тело. Второй слой меняет одежду, волосы и аксессуары. В 2D режиме активный слой подсвечивается, остальные области затемняются.</body></html>");
        help.setForeground(MUTED);
        help.setFont(help.getFont().deriveFont(12f));
        form.add(help, c);

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setOpaque(false);
        formScroll.getViewport().setOpaque(false);
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(formScroll, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        statusLabel.setForeground(MUTED);
        return panel;
    }

    private JPanel buildBottomTools() {
        JPanel panel = darkPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        panel.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 42)));

        styleCombo(toolCombo);
        toolCombo.addActionListener(e -> {
            Object selected = toolCombo.getSelectedItem();
            if (selected instanceof Tool tool) {
                activeTool = tool;
                canvas.repaint();
            }
        });
        panel.add(fieldLabel("Инструмент"));
        panel.add(toolCombo);

        styleSpinner(brushSpinner);
        brushSpinner.addChangeListener(e -> canvas.repaint());
        panel.add(fieldLabel("Кисть"));
        panel.add(brushSpinner);

        panel.add(fieldLabel("Цвет"));
        colorSwatch.setPreferredSize(new Dimension(38, 28));
        colorSwatch.setOpaque(true);
        colorSwatch.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 150)));
        updateColorSwatch();
        panel.add(colorSwatch);

        panel.add(paletteButton("Кожа", SKIN_PALETTE));
        panel.add(paletteButton("Волосы", HAIR_PALETTE));
        panel.add(paletteButton("Одежда", CLOTHES_PALETTE));
        panel.add(paletteButton("Детали", DETAIL_PALETTE));
        JButton customPaletteButton = toolbarButton("Моя палитра");
        customPaletteButton.addActionListener(e -> showCustomPalette(customPaletteButton));
        panel.add(customPaletteButton);
        JButton addColorButton = toolbarButton("+ цвет");
        addColorButton.setToolTipText("Добавить текущий цвет в свою палитру");
        addColorButton.addActionListener(e -> addCurrentColorToPalette());
        panel.add(addColorButton);

        JButton chooseColor = toolbarButton("Свой цвет");
        chooseColor.addActionListener(e -> chooseColor());
        panel.add(chooseColor);
        return panel;
    }

    private void setPaintMode(PaintMode mode) {
        paintMode = mode;
        mode3dButton.setSelected(mode == PaintMode.MODE_3D);
        mode2dButton.setSelected(mode == PaintMode.MODE_2D);
        statusLabel.setText(mode == PaintMode.MODE_3D
                ? "3D режим: левая кнопка рисует, правая вращает модель."
                : "2D режим: рисуйте по развертке PNG.");
        canvas.repaint();
    }

    private void refreshState() {
        miniPreview.repaint();
        texturePreview.repaint();
        canvas.repaint();
        updateColorSwatch();
    }

    private boolean slim() {
        return "SLIM".equals(modelCombo.getSelectedItem());
    }

    private void loadSkin() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Minecraft skin PNG", "png"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            BufferedImage loaded = ImageIO.read(file);
            if (loaded == null) {
                throw new IOException("PNG не прочитан.");
            }
            if (loaded.getWidth() < 64 || loaded.getHeight() < 32) {
                throw new IOException("Нужен скин 64x64 или 64x32.");
            }
            skin = normalizeSkin(loaded);
            String fileName = file.getName();
            int dot = fileName.lastIndexOf('.');
            nameField.setText(dot > 0 ? fileName.substring(0, dot) : fileName);
            clearUndoHistory();
            syncCurrentEntry();
            refreshState();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Загрузка скина", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createNewSkin() {
        if (!confirmDiscard()) {
            return;
        }
        skin = createTemplateSkin();
        nameField.setText("new_skin");
        clearUndoHistory();
        syncCurrentEntry();
        refreshState();
    }

    private boolean confirmDiscard() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Заменить текущий скин в редакторе?",
                "Новый скин",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private void savePng() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("PNG", "png"));
        chooser.setSelectedFile(new File(safeName() + ".png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".png")) {
            file = new File(file.getParentFile(), file.getName() + ".png");
        }
        try {
            ImageIO.write(skin, "png", file);
            statusLabel.setText("PNG сохранен: " + file.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Сохранение PNG", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyToLibrary() {
        syncCurrentEntry();
        result = new Result(safeName(), deepCopy(skin), slim());
        dispose();
    }

    private void beginPaintSession() {
        if (paintSnapshot == null) {
            paintSnapshot = deepCopy(skin);
            paintSnapshotDirty = false;
        }
    }

    private void finishPaintSession() {
        if (paintSnapshot == null) {
            return;
        }
        if (paintSnapshotDirty && !sameImage(paintSnapshot, skin)) {
            undoStack.addLast(paintSnapshot);
            while (undoStack.size() > MAX_UNDO_STEPS) {
                undoStack.removeFirst();
            }
            statusLabel.setText("Действие сохранено. Ctrl+Z отменяет последнее рисование.");
        }
        paintSnapshot = null;
        paintSnapshotDirty = false;
        updateUndoButton();
    }

    private void undoLastAction() {
        if (paintSnapshot != null) {
            finishPaintSession();
        }
        if (undoStack.isEmpty()) {
            statusLabel.setText("Нет действий для отмены.");
            return;
        }
        skin = undoStack.removeLast();
        paintSnapshot = null;
        paintSnapshotDirty = false;
        refreshState();
        updateUndoButton();
        statusLabel.setText("Последнее рисование отменено.");
    }

    private void clearUndoHistory() {
        undoStack.clear();
        paintSnapshot = null;
        paintSnapshotDirty = false;
        updateUndoButton();
    }

    private void syncCurrentEntry() {
        int index = currentProjectIndex;
        if (index < 0 || index >= skinProjectModel.size()) {
            return;
        }
        skinProjectModel.set(index, new SkinProjectEntry(safeName(), deepCopy(skin), slim()));
        skinProjectList.setSelectedIndex(index);
    }

    private void switchProjectEntry(int index) {
        if (switchingProjectEntry || index < 0 || index >= skinProjectModel.size()) {
            return;
        }
        switchingProjectEntry = true;
        try {
            int oldIndex = currentProjectIndex;
            if (oldIndex >= 0 && oldIndex < skinProjectModel.size()) {
                skinProjectModel.set(oldIndex, new SkinProjectEntry(safeName(), deepCopy(skin), slim()));
            }
            SkinProjectEntry entry = skinProjectModel.get(index);
            skin = deepCopy(entry.image());
            nameField.setText(entry.name());
            modelCombo.setSelectedItem(entry.slim() ? "SLIM" : "CLASSIC");
            currentProjectIndex = index;
            clearUndoHistory();
            refreshState();
        } finally {
            switchingProjectEntry = false;
        }
    }

    private void duplicateCurrentSkin() {
        syncCurrentEntry();
        SkinProjectEntry copy = new SkinProjectEntry(safeName() + "_copy", deepCopy(skin), slim());
        skinProjectModel.addElement(copy);
        skinProjectList.setSelectedIndex(skinProjectModel.size() - 1);
        statusLabel.setText("Добавлен дубликат текущего скина.");
    }

    private void addTemplateSkin() {
        syncCurrentEntry();
        SkinProjectEntry entry = new SkinProjectEntry("template_" + (skinProjectModel.size() + 1), createTemplateSkin(), slim());
        skinProjectModel.addElement(entry);
        skinProjectList.setSelectedIndex(skinProjectModel.size() - 1);
        statusLabel.setText("Добавлен новый шаблон скина.");
    }

    private void loadSkinAsNewEntry() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Minecraft skin PNG", "png"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            BufferedImage loaded = ImageIO.read(file);
            if (loaded == null) {
                throw new IOException("PNG не прочитан.");
            }
            if (loaded.getWidth() < 64 || loaded.getHeight() < 32) {
                throw new IOException("Нужен скин 64x64 или 64x32.");
            }
            syncCurrentEntry();
            String fileName = file.getName();
            int dot = fileName.lastIndexOf('.');
            SkinProjectEntry entry = new SkinProjectEntry(dot > 0 ? fileName.substring(0, dot) : fileName,
                    normalizeSkin(loaded), slim());
            skinProjectModel.addElement(entry);
            skinProjectList.setSelectedIndex(skinProjectModel.size() - 1);
            statusLabel.setText("Скин импортирован в проект.");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Импорт скина", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeCurrentSkin() {
        int index = skinProjectList.getSelectedIndex();
        if (skinProjectModel.size() <= 1 || index < 0) {
            statusLabel.setText("В проекте должен остаться хотя бы один скин.");
            return;
        }
        switchingProjectEntry = true;
        skinProjectModel.remove(index);
        int next = Math.min(index, skinProjectModel.size() - 1);
        SkinProjectEntry entry = skinProjectModel.get(next);
        skin = deepCopy(entry.image());
        nameField.setText(entry.name());
        modelCombo.setSelectedItem(entry.slim() ? "SLIM" : "CLASSIC");
        currentProjectIndex = next;
        skinProjectList.setSelectedIndex(next);
        switchingProjectEntry = false;
        clearUndoHistory();
        refreshState();
        statusLabel.setText("Скин удален из проекта.");
    }

    private void mixCurrentSkinWithSelected() {
        int index = skinProjectList.getSelectedIndex();
        if (skinProjectModel.size() < 2 || index < 0) {
            statusLabel.setText("Для смешивания добавьте минимум два скина.");
            return;
        }
        Object[] choices = new Object[skinProjectModel.size() - 1];
        int p = 0;
        for (int i = 0; i < skinProjectModel.size(); i++) {
            if (i != index) {
                choices[p++] = skinProjectModel.get(i);
            }
        }
        Object selected = JOptionPane.showInputDialog(this,
                "Выберите второй скин для смешивания с текущим.",
                "Смешивание скинов",
                JOptionPane.PLAIN_MESSAGE,
                null,
                choices,
                choices[0]);
        if (!(selected instanceof SkinProjectEntry other)) {
            return;
        }
        beginPaintSession();
        skin = mixSkins(skin, other.image());
        paintSnapshotDirty = true;
        finishPaintSession();
        nameField.setText(safeName() + "_mix");
        syncCurrentEntry();
        refreshState();
        statusLabel.setText("Скины смешаны: непрозрачные пиксели усреднены, прозрачность сохранена.");
    }

    private void updateUndoButton() {
        undoButton.setEnabled(!undoStack.isEmpty());
    }

    private boolean sameImage(BufferedImage first, BufferedImage second) {
        if (first == null || second == null || first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) {
            return false;
        }
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private String safeName() {
        String text = nameField.getText() == null ? "" : nameField.getText().trim();
        return text.isBlank() ? "edited_skin" : text;
    }

    private void chooseColor() {
        Color selected = JColorChooser.showDialog(this, "Цвет кисти", paintColor);
        if (selected != null) {
            setPaintColor(selected);
        }
    }

    private void updateColorSwatch() {
        colorSwatch.setBackground(paintColor.getAlpha() == 0 ? new Color(40, 45, 58) : paintColor);
        colorSwatch.setToolTipText("Текущий цвет: " + colorHex(paintColor));
    }

    private JButton paletteButton(String label, Color[] colors) {
        JButton button = toolbarButton(label);
        button.addActionListener(e -> showColorPalette(button, colors));
        return button;
    }

    private void showColorPalette(Component anchor, Color[] colors) {
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(PANEL_BORDER));
        JPanel grid = new GlassPanel(new GridLayout(0, 3, 6, 6));
        grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (Color color : colors) {
            JButton swatch = colorButton(color, 34);
            swatch.addActionListener(e -> {
                setPaintColor(color);
                popup.setVisible(false);
            });
            grid.add(swatch);
        }
        popup.add(grid);
        popup.show(anchor, 0, anchor.getHeight() + 3);
    }

    private void showCustomPalette(Component anchor) {
        if (customPalette.isEmpty()) {
            statusLabel.setText("Своя палитра пуста. Выберите цвет и нажмите '+ цвет'.");
            return;
        }
        showColorPalette(anchor, customPalette.toArray(new Color[0]));
    }

    private void addCurrentColorToPalette() {
        for (Color color : customPalette) {
            if (color.getRGB() == paintColor.getRGB()) {
                statusLabel.setText("Этот цвет уже есть в своей палитре.");
                return;
            }
        }
        customPalette.add(paintColor);
        statusLabel.setText("Цвет добавлен в свою палитру: " + colorHex(paintColor));
    }

    private JButton colorButton(Color color, int size) {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(size, size));
        button.setToolTipText((color.getAlpha() == 0) ? "Прозрачный" : colorHex(color));
        button.setOpaque(true);
        button.setBackground(color.getAlpha() == 0 ? new Color(40, 45, 58) : color);
        button.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 145)));
        button.setFocusPainted(false);
        return button;
    }

    private void setPaintColor(Color color) {
        paintColor = color;
        activeTool = color.getAlpha() == 0 ? Tool.ERASER : Tool.PENCIL;
        toolCombo.setSelectedItem(activeTool);
        updateColorSwatch();
        statusLabel.setText(color.getAlpha() == 0
                ? "Выбран прозрачный цвет: рисование работает как ластик."
                : "Цвет кисти: " + colorHex(color));
    }

    private String colorHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private boolean paintAtCanvasPoint(int x, int y) {
        Point pixel = paintMode == PaintMode.MODE_2D ? canvas.texturePointAt(x, y) : canvas.pickTexturePoint(x, y);
        if (pixel == null || !canEdit(pixel.x, pixel.y)) {
            return false;
        }

        if (activeTool == Tool.PICKER) {
            int argb = skin.getRGB(pixel.x, pixel.y);
            if (((argb >>> 24) & 0xff) > 0) {
                setPaintColor(new Color(argb, true));
                statusLabel.setText("Цвет взят с пикселя " + pixel.x + ":" + pixel.y);
            }
            return false;
        }

        boolean changed;
        if (activeTool == Tool.FILL) {
            changed = floodFill(pixel.x, pixel.y);
            if (mirrorCheck.isSelected()) {
                int mirrorX = SKIN_WIDTH - 1 - pixel.x;
                if (canEdit(mirrorX, pixel.y)) {
                    changed |= floodFill(mirrorX, pixel.y);
                }
            }
        } else {
            changed = paintBrush(pixel.x, pixel.y);
            if (mirrorCheck.isSelected()) {
                int mirrorX = SKIN_WIDTH - 1 - pixel.x;
                if (canEdit(mirrorX, pixel.y)) {
                    changed |= paintBrush(mirrorX, pixel.y);
                }
            }
        }
        if (changed) {
            paintSnapshotDirty = true;
            refreshState();
        }
        return changed;
    }

    private boolean canEdit(int x, int y) {
        if (x < 0 || y < 0 || x >= SKIN_WIDTH || y >= SKIN_HEIGHT) {
            return false;
        }
        List<Uv> rects = activeLayer == SkinLayer.BASE ? BASE_RECTS : OVERLAY_RECTS;
        return containsAny(rects, x, y);
    }

    private boolean containsAny(List<Uv> rects, int x, int y) {
        for (Uv rect : rects) {
            if (rect.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private boolean paintBrush(int centerX, int centerY) {
        int size = ((Number) brushSpinner.getValue()).intValue();
        int start = -((size - 1) / 2);
        boolean changed = false;
        Random random = shadeCheck.isSelected() ? new Random(centerX * 31L + centerY * 17L + System.nanoTime()) : null;
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                int x = centerX + start + dx;
                int y = centerY + start + dy;
                if (!canEdit(x, y)) {
                    continue;
                }
                int nextArgb;
                if (activeTool == Tool.ERASER) {
                    nextArgb = 0x00000000;
                } else {
                    Color color = random == null ? paintColor : variedColor(paintColor, random);
                    nextArgb = color.getRGB();
                }
                if (skin.getRGB(x, y) != nextArgb) {
                    skin.setRGB(x, y, nextArgb);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private Color variedColor(Color base, Random random) {
        int delta = random.nextInt(25) - 12;
        return new Color(
                clamp(base.getRed() + delta, 0, 255),
                clamp(base.getGreen() + delta, 0, 255),
                clamp(base.getBlue() + delta, 0, 255),
                base.getAlpha()
        );
    }

    private boolean floodFill(int sx, int sy) {
        if (!canEdit(sx, sy)) {
            return false;
        }
        int target = skin.getRGB(sx, sy);
        int replacement = activeTool == Tool.ERASER ? 0x00000000 : paintColor.getRGB();
        if (target == replacement) {
            return false;
        }
        boolean changed = false;
        ArrayDeque<Point> queue = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        queue.add(new Point(sx, sy));
        while (!queue.isEmpty()) {
            Point p = queue.removeFirst();
            int key = p.y * SKIN_WIDTH + p.x;
            if (!visited.add(key) || !canEdit(p.x, p.y) || skin.getRGB(p.x, p.y) != target) {
                continue;
            }
            skin.setRGB(p.x, p.y, replacement);
            changed = true;
            if (p.x > 0) queue.add(new Point(p.x - 1, p.y));
            if (p.x < SKIN_WIDTH - 1) queue.add(new Point(p.x + 1, p.y));
            if (p.y > 0) queue.add(new Point(p.x, p.y - 1));
            if (p.y < SKIN_HEIGHT - 1) queue.add(new Point(p.x, p.y + 1));
        }
        return changed;
    }

    private BufferedImage normalizeSkin(BufferedImage source) {
        BufferedImage out = new BufferedImage(SKIN_WIDTH, SKIN_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, SKIN_WIDTH, SKIN_HEIGHT);
        g.setComposite(AlphaComposite.SrcOver);
        if (source != null) {
            g.drawImage(source, 0, 0, Math.min(SKIN_WIDTH, source.getWidth()), Math.min(SKIN_HEIGHT, source.getHeight()),
                    0, 0, Math.min(SKIN_WIDTH, source.getWidth()), Math.min(SKIN_HEIGHT, source.getHeight()), null);
        } else {
            g.drawImage(createTemplateSkin(), 0, 0, null);
        }
        g.dispose();
        if (source != null && source.getHeight() <= 32) {
            copyLegacyLimbs(out);
        }
        return out;
    }

    private void copyLegacyLimbs(BufferedImage image) {
        copyRect(image, new Uv(44, 20, 4, 12), 36, 52, true);
        copyRect(image, new Uv(52, 20, 4, 12), 44, 52, true);
        copyRect(image, new Uv(48, 20, 4, 12), 40, 52, true);
        copyRect(image, new Uv(40, 20, 4, 12), 32, 52, true);
        copyRect(image, new Uv(44, 16, 4, 4), 36, 48, true);
        copyRect(image, new Uv(48, 16, 4, 4), 40, 48, true);
        copyRect(image, new Uv(4, 20, 4, 12), 20, 52, true);
        copyRect(image, new Uv(12, 20, 4, 12), 28, 52, true);
        copyRect(image, new Uv(8, 20, 4, 12), 24, 52, true);
        copyRect(image, new Uv(0, 20, 4, 12), 16, 52, true);
        copyRect(image, new Uv(4, 16, 4, 4), 20, 48, true);
        copyRect(image, new Uv(8, 16, 4, 4), 24, 48, true);
    }

    private void copyRect(BufferedImage image, Uv source, int targetX, int targetY, boolean mirror) {
        for (int y = 0; y < source.h(); y++) {
            for (int x = 0; x < source.w(); x++) {
                int sx = mirror ? source.x() + source.w() - 1 - x : source.x() + x;
                image.setRGB(targetX + x, targetY + y, image.getRGB(sx, source.y() + y));
            }
        }
    }

    private BufferedImage createTemplateSkin() {
        BufferedImage image = new BufferedImage(SKIN_WIDTH, SKIN_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, SKIN_WIDTH, SKIN_HEIGHT);
        g.setComposite(AlphaComposite.SrcOver);

        Color skinColor = new Color(230, 178, 132);
        Color hair = new Color(74, 46, 31);
        Color shirt = new Color(36, 132, 195);
        Color pants = new Color(72, 82, 108);
        Color shoe = new Color(36, 36, 42);

        fillRects(g, skinColor, List.of(
                new Uv(8, 8, 8, 8), new Uv(24, 8, 8, 8), new Uv(16, 8, 8, 8),
                new Uv(0, 8, 8, 8), new Uv(8, 0, 8, 8), new Uv(16, 0, 8, 8),
                new Uv(44, 20, 4, 12), new Uv(52, 20, 4, 12), new Uv(48, 20, 4, 12), new Uv(40, 20, 4, 12),
                new Uv(36, 52, 4, 12), new Uv(44, 52, 4, 12), new Uv(40, 52, 4, 12), new Uv(32, 52, 4, 12)
        ));
        fillRects(g, hair, List.of(
                new Uv(8, 8, 8, 3), new Uv(40, 8, 8, 3), new Uv(40, 0, 8, 8), new Uv(48, 0, 8, 8)
        ));
        fillRects(g, shirt, List.of(
                new Uv(20, 20, 8, 12), new Uv(32, 20, 8, 12), new Uv(28, 20, 4, 12), new Uv(16, 20, 4, 12),
                new Uv(20, 16, 8, 4), new Uv(28, 16, 8, 4)
        ));
        fillRects(g, pants, List.of(
                new Uv(4, 20, 4, 10), new Uv(12, 20, 4, 10), new Uv(8, 20, 4, 10), new Uv(0, 20, 4, 10),
                new Uv(20, 52, 4, 10), new Uv(28, 52, 4, 10), new Uv(24, 52, 4, 10), new Uv(16, 52, 4, 10)
        ));
        fillRects(g, shoe, List.of(
                new Uv(4, 30, 4, 2), new Uv(12, 30, 4, 2), new Uv(20, 62, 4, 2), new Uv(28, 62, 4, 2)
        ));
        g.dispose();
        return image;
    }

    private void fillRects(Graphics2D g, Color color, List<Uv> rects) {
        g.setColor(color);
        for (Uv rect : rects) {
            g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
        }
    }

    private BufferedImage deepCopy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(SKIN_WIDTH, SKIN_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private BufferedImage previewSkinImage() {
        BufferedImage copy = deepCopy(skin);
        if (!baseVisibleCheck.isSelected()) {
            clearRects(copy, BASE_RECTS);
        }
        if (!overlayVisibleCheck.isSelected()) {
            clearRects(copy, OVERLAY_RECTS);
        }
        for (BodyPart part : BodyPart.values()) {
            if (!isBodyPartVisible(part)) {
                clearRects(copy, rectsFor(part));
            }
        }
        return copy;
    }

    private BufferedImage mixSkins(BufferedImage first, BufferedImage second) {
        BufferedImage out = new BufferedImage(SKIN_WIDTH, SKIN_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        BufferedImage a = normalizeSkin(first);
        BufferedImage b = normalizeSkin(second);
        for (int y = 0; y < SKIN_HEIGHT; y++) {
            for (int x = 0; x < SKIN_WIDTH; x++) {
                int ca = a.getRGB(x, y);
                int cb = b.getRGB(x, y);
                int aa = (ca >>> 24) & 0xff;
                int ab = (cb >>> 24) & 0xff;
                if (aa == 0 && ab == 0) {
                    out.setRGB(x, y, 0x00000000);
                } else if (aa == 0) {
                    out.setRGB(x, y, cb);
                } else if (ab == 0) {
                    out.setRGB(x, y, ca);
                } else {
                    int alpha = Math.max(aa, ab);
                    int red = (((ca >> 16) & 0xff) + ((cb >> 16) & 0xff)) / 2;
                    int green = (((ca >> 8) & 0xff) + ((cb >> 8) & 0xff)) / 2;
                    int blue = ((ca & 0xff) + (cb & 0xff)) / 2;
                    out.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                }
            }
        }
        return out;
    }

    private boolean isBodyPartVisible(BodyPart part) {
        return switch (part) {
            case HEAD -> headVisibleCheck.isSelected();
            case BODY -> bodyVisibleCheck.isSelected();
            case LEFT_ARM -> leftArmVisibleCheck.isSelected();
            case RIGHT_ARM -> rightArmVisibleCheck.isSelected();
            case LEFT_LEG -> leftLegVisibleCheck.isSelected();
            case RIGHT_LEG -> rightLegVisibleCheck.isSelected();
        };
    }

    private List<Uv> rectsFor(BodyPart part) {
        List<Uv> rects = new ArrayList<>();
        switch (part) {
            case HEAD -> {
                addRectSet(rects, headBase());
                addRectSet(rects, headOverlay());
            }
            case BODY -> {
                addRectSet(rects, bodyBase());
                addRectSet(rects, bodyOverlay());
            }
            case LEFT_ARM -> {
                addRectSet(rects, oldArmBase());
                addRectSet(rects, oldArmOverlay());
            }
            case RIGHT_ARM -> {
                addRectSet(rects, newArmBase());
                addRectSet(rects, newArmOverlay());
            }
            case LEFT_LEG -> {
                addRectSet(rects, oldLegBase());
                addRectSet(rects, oldLegOverlay());
            }
            case RIGHT_LEG -> {
                addRectSet(rects, newLegBase());
                addRectSet(rects, newLegOverlay());
            }
        }
        return rects;
    }

    private BodyPart bodyPartAt(int x, int y) {
        for (BodyPart part : BodyPart.values()) {
            if (containsAny(rectsFor(part), x, y)) {
                return part;
            }
        }
        return null;
    }

    private void clearRects(BufferedImage image, List<Uv> rects) {
        for (Uv rect : rects) {
            for (int y = rect.y(); y < rect.y() + rect.h(); y++) {
                for (int x = rect.x(); x < rect.x() + rect.w(); x++) {
                    image.setRGB(x, y, 0x00000000);
                }
            }
        }
    }

    private JPanel darkPanel(java.awt.LayoutManager layout) {
        return new GlassPanel(layout);
    }

    private JLabel titleLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        return label;
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        return label;
    }

    private JButton toolbarButton(String text) {
        JButton button = new JButton(text);
        stylePlainButton(button);
        return button;
    }

    private JButton accentButton(String text) {
        JButton button = new JButton(text);
        stylePlainButton(button);
        button.setBackground(ACCENT);
        button.setForeground(Color.WHITE);
        return button;
    }

    private void stylePlainButton(JButton button) {
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(55, 65, 84));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 70)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
    }

    private void styleToggle(JToggleButton button) {
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(55, 65, 84));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 70)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
    }

    private void styleCheckBox(JCheckBox checkBox) {
        checkBox.setOpaque(false);
        checkBox.setForeground(TEXT);
        checkBox.setFocusPainted(false);
    }

    private JLabel separator() {
        JLabel separator = new JLabel(" ");
        separator.setPreferredSize(new Dimension(10, 1));
        return separator;
    }

    private void styleTextField(JTextField field) {
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(new Color(18, 24, 34));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 65)),
                BorderFactory.createEmptyBorder(5, 7, 5, 7)
        ));
    }

    private void styleCombo(JComboBox<?> combo) {
        combo.setForeground(TEXT);
        combo.setBackground(new Color(39, 49, 65));
        combo.setFocusable(false);
        combo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setForeground(TEXT);
                label.setBackground(isSelected ? new Color(70, 86, 108) : new Color(30, 36, 46));
                label.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
                return label;
            }
        });
    }

    private void styleSpinner(JSpinner spinner) {
        spinner.setPreferredSize(new Dimension(58, 28));
        spinner.setOpaque(false);
        spinner.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 65)));
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            styleTextField(editor.getTextField());
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static List<Uv> baseRects() {
        List<Uv> rects = new ArrayList<>();
        addRectSet(rects, headBase());
        addRectSet(rects, bodyBase());
        addRectSet(rects, oldArmBase());
        addRectSet(rects, newArmBase());
        addRectSet(rects, oldLegBase());
        addRectSet(rects, newLegBase());
        return List.copyOf(rects);
    }

    private static List<Uv> overlayRects() {
        List<Uv> rects = new ArrayList<>();
        addRectSet(rects, headOverlay());
        addRectSet(rects, bodyOverlay());
        addRectSet(rects, oldArmOverlay());
        addRectSet(rects, newArmOverlay());
        addRectSet(rects, oldLegOverlay());
        addRectSet(rects, newLegOverlay());
        return List.copyOf(rects);
    }

    private static void addRectSet(List<Uv> out, CuboidUv set) {
        out.add(set.front());
        out.add(set.back());
        out.add(set.left());
        out.add(set.right());
        out.add(set.top());
        out.add(set.bottom());
    }

    private static CuboidUv headBase() {
        return new CuboidUv(
                new Uv(8, 8, 8, 8), new Uv(24, 8, 8, 8),
                new Uv(16, 8, 8, 8), new Uv(0, 8, 8, 8),
                new Uv(8, 0, 8, 8), new Uv(16, 0, 8, 8), SkinLayer.BASE);
    }

    private static CuboidUv headOverlay() {
        return new CuboidUv(
                new Uv(40, 8, 8, 8), new Uv(56, 8, 8, 8),
                new Uv(48, 8, 8, 8), new Uv(32, 8, 8, 8),
                new Uv(40, 0, 8, 8), new Uv(48, 0, 8, 8), SkinLayer.OVERLAY);
    }

    private static CuboidUv bodyBase() {
        return new CuboidUv(
                new Uv(20, 20, 8, 12), new Uv(32, 20, 8, 12),
                new Uv(28, 20, 4, 12), new Uv(16, 20, 4, 12),
                new Uv(20, 16, 8, 4), new Uv(28, 16, 8, 4), SkinLayer.BASE);
    }

    private static CuboidUv bodyOverlay() {
        return new CuboidUv(
                new Uv(20, 36, 8, 12), new Uv(32, 36, 8, 12),
                new Uv(28, 36, 4, 12), new Uv(16, 36, 4, 12),
                new Uv(20, 32, 8, 4), new Uv(28, 32, 8, 4), SkinLayer.OVERLAY);
    }

    private static CuboidUv oldArmBase() {
        return new CuboidUv(
                new Uv(44, 20, 4, 12), new Uv(52, 20, 4, 12),
                new Uv(48, 20, 4, 12), new Uv(40, 20, 4, 12),
                new Uv(44, 16, 4, 4), new Uv(48, 16, 4, 4), SkinLayer.BASE);
    }

    private static CuboidUv oldArmOverlay() {
        return new CuboidUv(
                new Uv(44, 36, 4, 12), new Uv(52, 36, 4, 12),
                new Uv(48, 36, 4, 12), new Uv(40, 36, 4, 12),
                new Uv(44, 32, 4, 4), new Uv(48, 32, 4, 4), SkinLayer.OVERLAY);
    }

    private static CuboidUv newArmBase() {
        return new CuboidUv(
                new Uv(36, 52, 4, 12), new Uv(44, 52, 4, 12),
                new Uv(40, 52, 4, 12), new Uv(32, 52, 4, 12),
                new Uv(36, 48, 4, 4), new Uv(40, 48, 4, 4), SkinLayer.BASE);
    }

    private static CuboidUv newArmOverlay() {
        return new CuboidUv(
                new Uv(52, 52, 4, 12), new Uv(60, 52, 4, 12),
                new Uv(56, 52, 4, 12), new Uv(48, 52, 4, 12),
                new Uv(52, 48, 4, 4), new Uv(56, 48, 4, 4), SkinLayer.OVERLAY);
    }

    private static CuboidUv oldLegBase() {
        return new CuboidUv(
                new Uv(4, 20, 4, 12), new Uv(12, 20, 4, 12),
                new Uv(8, 20, 4, 12), new Uv(0, 20, 4, 12),
                new Uv(4, 16, 4, 4), new Uv(8, 16, 4, 4), SkinLayer.BASE);
    }

    private static CuboidUv oldLegOverlay() {
        return new CuboidUv(
                new Uv(4, 36, 4, 12), new Uv(12, 36, 4, 12),
                new Uv(8, 36, 4, 12), new Uv(0, 36, 4, 12),
                new Uv(4, 32, 4, 4), new Uv(8, 32, 4, 4), SkinLayer.OVERLAY);
    }

    private static CuboidUv newLegBase() {
        return new CuboidUv(
                new Uv(20, 52, 4, 12), new Uv(28, 52, 4, 12),
                new Uv(24, 52, 4, 12), new Uv(16, 52, 4, 12),
                new Uv(20, 48, 4, 4), new Uv(24, 48, 4, 4), SkinLayer.BASE);
    }

    private static CuboidUv newLegOverlay() {
        return new CuboidUv(
                new Uv(4, 52, 4, 12), new Uv(12, 52, 4, 12),
                new Uv(8, 52, 4, 12), new Uv(0, 52, 4, 12),
                new Uv(4, 48, 4, 4), new Uv(8, 48, 4, 4), SkinLayer.OVERLAY);
    }

    private final class SkinMiniPreview extends JPanel {
        private SkinMiniPreview() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.setColor(new Color(8, 12, 18, 180));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            BufferedImage rendered = SkinRenderer.render3d(previewSkinImage(), null, slim(), getWidth(), getHeight(),
                    Math.toRadians(-24), Math.toRadians(-7), overlayVisibleCheck.isSelected());
            g2.drawImage(rendered, 0, 0, null);
            g2.dispose();
        }
    }

    private final class SkinTexturePreview extends JPanel {
        private SkinTexturePreview() {
            setOpaque(false);
            setToolTipText("Полная PNG-развертка скина без 3D-модели");
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.setColor(new Color(8, 12, 18, 180));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);

            int pad = 10;
            int availableW = Math.max(1, getWidth() - pad * 2);
            int availableH = Math.max(1, getHeight() - pad * 2);
            int scale = Math.max(1, Math.min(availableW / SKIN_WIDTH, availableH / SKIN_HEIGHT));
            int w = scale * SKIN_WIDTH;
            int h = scale * SKIN_HEIGHT;
            int x0 = getWidth() / 2 - w / 2;
            int y0 = getHeight() / 2 - h / 2;
            Rectangle bounds = new Rectangle(x0, y0, w, h);
            canvas.paintChecker(g2, bounds, Math.max(4, scale * 2));
            g2.drawImage(previewSkinImage(), x0, y0, w, h, null);
            g2.setColor(new Color(255, 255, 255, 150));
            g2.drawRect(x0, y0, Math.max(0, w - 1), Math.max(0, h - 1));
            g2.dispose();
        }
    }

    private final class EditorCanvas extends JPanel {
        private double yaw = Math.toRadians(-24);
        private double pitch = Math.toRadians(-7);
        private double zoom = 1.0;
        private int lastX;
        private int lastY;
        private boolean rotating;
        private boolean painting;
        private Rectangle textureBounds = new Rectangle();
        private List<SkinFace> lastFaces = List.of();

        private EditorCanvas() {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    lastX = e.getX();
                    lastY = e.getY();
                    rotating = shouldRotate(e);
                    painting = !rotating && SwingUtilities.isLeftMouseButton(e);
                    if (painting) {
                        beginPaintSession();
                        paintAtCanvasPoint(e.getX(), e.getY());
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (rotating) {
                        int dx = e.getX() - lastX;
                        int dy = e.getY() - lastY;
                        yaw -= dx * 0.012;
                        pitch = Math.max(Math.toRadians(-34), Math.min(Math.toRadians(24), pitch + dy * 0.008));
                        lastX = e.getX();
                        lastY = e.getY();
                        repaint();
                    } else if (painting) {
                        paintAtCanvasPoint(e.getX(), e.getY());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (painting) {
                        finishPaintSession();
                    }
                    rotating = false;
                    painting = false;
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (paintMode == PaintMode.MODE_3D && e.getClickCount() == 2 && SwingUtilities.isRightMouseButton(e)) {
                        resetView();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    zoom = Math.max(0.65, Math.min(1.8, zoom - e.getPreciseWheelRotation() * 0.06));
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        private boolean shouldRotate(MouseEvent e) {
            return paintMode == PaintMode.MODE_3D
                    && (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e) || e.isAltDown());
        }

        private void rotateView(double yawDegrees, double pitchDegrees) {
            yaw += Math.toRadians(yawDegrees);
            pitch = Math.max(Math.toRadians(-34), Math.min(Math.toRadians(24), pitch + Math.toRadians(pitchDegrees)));
            repaint();
        }

        private void resetView() {
            yaw = Math.toRadians(-24);
            pitch = Math.toRadians(-7);
            zoom = 1.0;
            repaint();
        }

        private Point texturePointAt(int x, int y) {
            if (paintMode != PaintMode.MODE_2D || !textureBounds.contains(x, y)) {
                return null;
            }
            int px = (x - textureBounds.x) * SKIN_WIDTH / textureBounds.width;
            int py = (y - textureBounds.y) * SKIN_HEIGHT / textureBounds.height;
            return new Point(clamp(px, 0, SKIN_WIDTH - 1), clamp(py, 0, SKIN_HEIGHT - 1));
        }

        private Point pickTexturePoint(int x, int y) {
            if (paintMode != PaintMode.MODE_3D) {
                return null;
            }
            List<SkinFace> faces = buildFaces(getWidth(), getHeight(), true);
            for (int i = faces.size() - 1; i >= 0; i--) {
                SkinFace face = faces.get(i);
                if (face.layer() != activeLayer) {
                    continue;
                }
                Point point = face.texturePointAt(x, y);
                if (point != null) {
                    return point;
                }
            }
            return null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            paintAtmosphere(g2);
            if (paintMode == PaintMode.MODE_2D) {
                paint2d(g2);
            } else {
                paint3d(g2);
            }
            g2.dispose();
        }

        private void paintAtmosphere(Graphics2D g2) {
            Paint paint = new GradientPaint(0, 0, new Color(80, 111, 139), 0, getHeight(), new Color(23, 44, 36));
            g2.setPaint(paint);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.20f));
            g2.setColor(Color.WHITE);
            for (int i = 0; i < getWidth(); i += 42) {
                g2.drawLine(i, 0, i - getHeight() / 2, getHeight());
            }
            g2.setComposite(AlphaComposite.SrcOver);
        }

        private void paint3d(Graphics2D g2) {
            lastFaces = buildFaces(getWidth(), getHeight(), false);
            paintGround(g2);
            for (SkinFace face : lastFaces) {
                drawFace(g2, face);
            }
            g2.setColor(new Color(255, 255, 255, 180));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
            g2.drawString("3D покраска: левая кнопка рисует, правая/Alt вращает, колесо масштабирует", 18, 28);
        }

        private void paintGround(Graphics2D g2) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.24f));
            g2.setColor(Color.BLACK);
            int w = Math.max(110, getWidth() / 4);
            int h = Math.max(24, getHeight() / 22);
            g2.fillOval(getWidth() / 2 - w / 2, (int) (getHeight() * 0.78), w, h);
            g2.setComposite(AlphaComposite.SrcOver);
        }

        private void paint2d(Graphics2D g2) {
            int margin = 36;
            int availableW = Math.max(1, getWidth() - margin * 2);
            int availableH = Math.max(1, getHeight() - margin * 2);
            int scale = Math.max(2, Math.min(availableW / SKIN_WIDTH, availableH / SKIN_HEIGHT));
            int size = scale * SKIN_WIDTH;
            textureBounds = new Rectangle(getWidth() / 2 - size / 2, getHeight() / 2 - size / 2, size, size);

            paintChecker(g2, textureBounds, Math.max(4, scale * 2));
            paintVisibleTexture(g2, scale);

            dimInactiveLayer(g2, scale);
            drawLayerRects(g2, scale);
            if (gridCheck.isSelected() && scale >= 5) {
                g2.setColor(GRID);
                for (int i = 0; i <= SKIN_WIDTH; i++) {
                    int px = textureBounds.x + i * scale;
                    g2.drawLine(px, textureBounds.y, px, textureBounds.y + textureBounds.height);
                }
                for (int i = 0; i <= SKIN_HEIGHT; i++) {
                    int py = textureBounds.y + i * scale;
                    g2.drawLine(textureBounds.x, py, textureBounds.x + textureBounds.width, py);
                }
            }
            g2.setColor(new Color(255, 255, 255, 190));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
            g2.drawString("2D развертка PNG: активный слой " + activeLayer.label(), 18, 28);
        }

        private void paintChecker(Graphics2D g2, Rectangle bounds, int cell) {
            for (int y = bounds.y; y < bounds.y + bounds.height; y += cell) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x += cell) {
                    boolean light = ((x - bounds.x) / cell + (y - bounds.y) / cell) % 2 == 0;
                    g2.setColor(light ? new Color(216, 222, 228) : new Color(156, 166, 176));
                    g2.fillRect(x, y, Math.min(cell, bounds.x + bounds.width - x), Math.min(cell, bounds.y + bounds.height - y));
                }
            }
        }

        private void paintVisibleTexture(Graphics2D g2, int scale) {
            for (int y = 0; y < SKIN_HEIGHT; y++) {
                for (int x = 0; x < SKIN_WIDTH; x++) {
                    if (!isLayerPixelVisible(x, y)) {
                        continue;
                    }
                    int argb = skin.getRGB(x, y);
                    if (((argb >>> 24) & 0xff) == 0) {
                        continue;
                    }
                    g2.setColor(new Color(argb, true));
                    g2.fillRect(textureBounds.x + x * scale, textureBounds.y + y * scale, scale, scale);
                }
            }
        }

        private boolean isLayerPixelVisible(int x, int y) {
            BodyPart part = bodyPartAt(x, y);
            if (part != null && !isBodyPartVisible(part)) {
                return false;
            }
            boolean base = containsAny(BASE_RECTS, x, y);
            boolean overlay = containsAny(OVERLAY_RECTS, x, y);
            if (base && (baseVisibleCheck.isSelected() || activeLayer == SkinLayer.BASE)) {
                return true;
            }
            if (overlay && (overlayVisibleCheck.isSelected() || activeLayer == SkinLayer.OVERLAY)) {
                return true;
            }
            return !base && !overlay;
        }

        private void dimInactiveLayer(Graphics2D g2, int scale) {
            List<Uv> activeRects = activeLayer == SkinLayer.BASE ? BASE_RECTS : OVERLAY_RECTS;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.46f));
            g2.setColor(Color.BLACK);
            for (int y = 0; y < SKIN_HEIGHT; y++) {
                for (int x = 0; x < SKIN_WIDTH; x++) {
                    boolean active = false;
                    for (Uv rect : activeRects) {
                        if (rect.contains(x, y)) {
                            active = true;
                            break;
                        }
                    }
                    if (!active) {
                        g2.fillRect(textureBounds.x + x * scale, textureBounds.y + y * scale, scale, scale);
                    }
                }
            }
            g2.setComposite(AlphaComposite.SrcOver);
        }

        private void drawLayerRects(Graphics2D g2, int scale) {
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(activeLayer == SkinLayer.BASE ? ACCENT_BLUE : new Color(245, 210, 74));
            List<Uv> rects = activeLayer == SkinLayer.BASE ? BASE_RECTS : OVERLAY_RECTS;
            for (Uv rect : rects) {
                g2.drawRect(textureBounds.x + rect.x() * scale, textureBounds.y + rect.y() * scale,
                        rect.w() * scale, rect.h() * scale);
            }
        }

        private List<SkinFace> buildFaces(int width, int height, boolean includePickFaces) {
            List<SkinFace> faces = new ArrayList<>();
            double scale = Math.min(width / 28.0, height / 40.0) * 0.94 * zoom;
            Projector projector = new Projector(width / 2.0, height / 2.0 + scale * 2.0, scale, yaw, pitch);
            double armWidth = slim() ? 3.0 : 4.0;

            if (baseVisibleCheck.isSelected() || activeLayer == SkinLayer.BASE || includePickFaces) {
                if (isBodyPartVisible(BodyPart.HEAD)) {
                    addCuboid(faces, projector, -4, -16, -4, 4, -8, 4, headBase());
                }
                if (isBodyPartVisible(BodyPart.BODY)) {
                    addCuboid(faces, projector, -4, -8, -2, 4, 4, 2, bodyBase());
                }
                if (isBodyPartVisible(BodyPart.LEFT_ARM)) {
                    addCuboid(faces, projector, -4 - armWidth, -8, -2, -4, 4, 2, oldArmBase());
                }
                if (isBodyPartVisible(BodyPart.RIGHT_ARM)) {
                    addCuboid(faces, projector, 4, -8, -2, 4 + armWidth, 4, 2, newArmBase());
                }
                if (isBodyPartVisible(BodyPart.LEFT_LEG)) {
                    addCuboid(faces, projector, -4, 4, -2, 0, 16, 2, oldLegBase());
                }
                if (isBodyPartVisible(BodyPart.RIGHT_LEG)) {
                    addCuboid(faces, projector, 0, 4, -2, 4, 16, 2, newLegBase());
                }
            }

            if (overlayVisibleCheck.isSelected() || activeLayer == SkinLayer.OVERLAY || includePickFaces) {
                if (isBodyPartVisible(BodyPart.HEAD)) {
                    addCuboid(faces, projector, -4.35, -16.35, -4.35, 4.35, -7.65, 4.35, headOverlay());
                }
                if (isBodyPartVisible(BodyPart.BODY)) {
                    addCuboid(faces, projector, -4.25, -8.25, -2.25, 4.25, 4.25, 2.25, bodyOverlay());
                }
                if (isBodyPartVisible(BodyPart.LEFT_ARM)) {
                    addCuboid(faces, projector, -4 - armWidth - 0.25, -8.25, -2.25, -3.75, 4.25, 2.25, oldArmOverlay());
                }
                if (isBodyPartVisible(BodyPart.RIGHT_ARM)) {
                    addCuboid(faces, projector, 3.75, -8.25, -2.25, 4 + armWidth + 0.25, 4.25, 2.25, newArmOverlay());
                }
                if (isBodyPartVisible(BodyPart.LEFT_LEG)) {
                    addCuboid(faces, projector, -4.25, 3.75, -2.25, 0.15, 16.25, 2.25, oldLegOverlay());
                }
                if (isBodyPartVisible(BodyPart.RIGHT_LEG)) {
                    addCuboid(faces, projector, -0.15, 3.75, -2.25, 4.25, 16.25, 2.25, newLegOverlay());
                }
            }

            faces.sort(Comparator.comparingDouble(SkinFace::depth));
            return faces;
        }

        private void addCuboid(List<SkinFace> faces, Projector projector,
                               double x1, double y1, double z1, double x2, double y2, double z2,
                               CuboidUv uv) {
            addFace(faces, projector, uv.front(), uv.layer(), x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, 1.0);
            addFace(faces, projector, uv.back(), uv.layer(), x2, y1, z1, x1, y1, z1, x1, y2, z1, x2, y2, z1, 0.58);
            addFace(faces, projector, uv.left(), uv.layer(), x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2, 0.78);
            addFace(faces, projector, uv.right(), uv.layer(), x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, 0.72);
            addFace(faces, projector, uv.top(), uv.layer(), x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, 1.12);
            addFace(faces, projector, uv.bottom(), uv.layer(), x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, 0.62);
        }

        private void addFace(List<SkinFace> faces, Projector projector, Uv uv, SkinLayer layer,
                             double x0, double y0, double z0, double x1, double y1, double z1,
                             double x2, double y2, double z2, double x3, double y3, double z3,
                             double shade) {
            Projected p0 = projector.project(x0, y0, z0);
            Projected p1 = projector.project(x1, y1, z1);
            Projected p2 = projector.project(x2, y2, z2);
            Projected p3 = projector.project(x3, y3, z3);
            double depth = (p0.z() + p1.z() + p2.z() + p3.z()) / 4.0;
            faces.add(new SkinFace(uv, layer, p0.point(), p1.point(), p2.point(), p3.point(), depth, shade));
        }

        private void drawFace(Graphics2D g2, SkinFace face) {
            Polygon clip = face.polygon();
            BufferedImage texture = skin.getSubimage(face.uv().x(), face.uv().y(), face.uv().w(), face.uv().h());
            boolean visibleTexture = hasVisiblePixel(texture);
            boolean active = face.layer() == activeLayer;
            boolean drawLayer = face.layer() == SkinLayer.BASE
                    ? baseVisibleCheck.isSelected() || active
                    : overlayVisibleCheck.isSelected() || active;

            if (!drawLayer) {
                return;
            }

            java.awt.Shape oldClip = g2.getClip();
            g2.setClip(clip);
            if (visibleTexture) {
                double m00 = (face.p1().getX() - face.p0().getX()) / texture.getWidth();
                double m10 = (face.p1().getY() - face.p0().getY()) / texture.getWidth();
                double m01 = (face.p3().getX() - face.p0().getX()) / texture.getHeight();
                double m11 = (face.p3().getY() - face.p0().getY()) / texture.getHeight();
                AffineTransform transform = new AffineTransform(m00, m10, m01, m11, face.p0().getX(), face.p0().getY());
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(texture, transform, null);
                if (face.shade() < 0.99) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(0.42, 1.0 - face.shade())));
                    g2.setColor(Color.BLACK);
                    g2.fillPolygon(clip);
                    g2.setComposite(AlphaComposite.SrcOver);
                } else if (face.shade() > 1.01) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f));
                    g2.setColor(Color.WHITE);
                    g2.fillPolygon(clip);
                    g2.setComposite(AlphaComposite.SrcOver);
                }
            } else if (active) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f));
                g2.setColor(activeLayer == SkinLayer.BASE ? ACCENT_BLUE : new Color(245, 210, 74));
                g2.fillPolygon(clip);
                g2.setComposite(AlphaComposite.SrcOver);
            }
            g2.setClip(oldClip);

            if (gridCheck.isSelected() || active) {
                g2.setStroke(new BasicStroke(active ? 2f : 1f));
                g2.setColor(active ? (activeLayer == SkinLayer.BASE ? ACCENT_BLUE : new Color(245, 210, 74)) : GRID);
                g2.drawPolygon(clip);
            }
        }

        private boolean hasVisiblePixel(BufferedImage texture) {
            for (int y = 0; y < texture.getHeight(); y++) {
                for (int x = 0; x < texture.getWidth(); x++) {
                    if (((texture.getRGB(x, y) >>> 24) & 0xff) > 12) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static class GlassPanel extends JPanel {
        private GlassPanel(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(PANEL_BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g2.setColor(new Color(255, 255, 255, 18));
            g2.drawRoundRect(0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), 16, 16);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class GradientPanel extends JPanel {
        private GradientPanel() {
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            Paint paint = new LinearGradientPaint(0, 0, getWidth(), getHeight(),
                    new float[] {0f, 0.48f, 1f},
                    new Color[] {BG_TOP, new Color(28, 58, 68), BG_BOTTOM});
            g2.setPaint(paint);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    private enum PaintMode {
        MODE_3D,
        MODE_2D
    }

    private enum Tool {
        PENCIL("Карандаш"),
        ERASER("Ластик"),
        FILL("Заливка"),
        PICKER("Пипетка");

        private final String label;

        Tool(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum SkinLayer {
        BASE("Основа"),
        OVERLAY("Второй слой");

        private final String label;

        SkinLayer(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum BodyPart {
        HEAD,
        BODY,
        LEFT_ARM,
        RIGHT_ARM,
        LEFT_LEG,
        RIGHT_LEG
    }

    private record SkinProjectEntry(String name, BufferedImage image, boolean slim) {
        @Override
        public String toString() {
            return name;
        }
    }

    record Result(String name, BufferedImage image, boolean slim) {
    }

    private record Uv(int x, int y, int w, int h) {
        private boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

    private record CuboidUv(Uv front, Uv back, Uv left, Uv right, Uv top, Uv bottom, SkinLayer layer) {
    }

    private record SkinFace(Uv uv, SkinLayer layer, Point2D.Double p0, Point2D.Double p1, Point2D.Double p2,
                            Point2D.Double p3, double depth, double shade) {
        private Polygon polygon() {
            return new Polygon(
                    new int[] {
                            (int) Math.round(p0.getX()),
                            (int) Math.round(p1.getX()),
                            (int) Math.round(p2.getX()),
                            (int) Math.round(p3.getX())
                    },
                    new int[] {
                            (int) Math.round(p0.getY()),
                            (int) Math.round(p1.getY()),
                            (int) Math.round(p2.getY()),
                            (int) Math.round(p3.getY())
                    },
                    4
            );
        }

        private Point texturePointAt(int x, int y) {
            double ax = p1.getX() - p0.getX();
            double ay = p1.getY() - p0.getY();
            double bx = p3.getX() - p0.getX();
            double by = p3.getY() - p0.getY();
            double det = ax * by - ay * bx;
            if (Math.abs(det) < 0.00001) {
                return null;
            }
            double dx = x - p0.getX();
            double dy = y - p0.getY();
            double u = (dx * by - dy * bx) / det;
            double v = (ax * dy - ay * dx) / det;
            if (u < -0.001 || v < -0.001 || u > 1.001 || v > 1.001) {
                return null;
            }
            int tx = uv.x() + Math.min(uv.w() - 1, Math.max(0, (int) Math.floor(u * uv.w())));
            int ty = uv.y() + Math.min(uv.h() - 1, Math.max(0, (int) Math.floor(v * uv.h())));
            return new Point(tx, ty);
        }
    }

    private record Projected(Point2D.Double point, double z) {
    }

    private static final class Projector {
        private final double centerX;
        private final double centerY;
        private final double scale;
        private final double yaw;
        private final double pitch;

        private Projector(double centerX, double centerY, double scale, double yaw, double pitch) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.scale = scale;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        private Projected project(double x, double y, double z) {
            double cosYaw = Math.cos(yaw);
            double sinYaw = Math.sin(yaw);
            double rx = x * cosYaw - z * sinYaw;
            double rz = x * sinYaw + z * cosYaw;

            double cosPitch = Math.cos(pitch);
            double sinPitch = Math.sin(pitch);
            double ry = y * cosPitch - rz * sinPitch;
            double depth = y * sinPitch + rz * cosPitch;

            return new Projected(new Point2D.Double(centerX + rx * scale, centerY + ry * scale), depth);
        }
    }
}
