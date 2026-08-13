import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

final class SkinPreviewPanel extends JPanel {
    private BufferedImage skin;
    private BufferedImage cape;
    private boolean slim;
    private boolean showSecondLayer = true;
    private String playerName = "";

    // Current displayed angles
    private double yaw   = Math.toRadians(-24);
    private double pitch = Math.toRadians(-7);

    // Default "home" angles
    private static final double DEFAULT_YAW   = Math.toRadians(-24);
    private static final double DEFAULT_PITCH = Math.toRadians(-7);

    // Drag state
    private int lastX, lastY;
    private boolean dragging = false;
    private boolean dragMoved = false;
    private boolean manualPose = false;
    private double manualBaseYaw = DEFAULT_YAW;
    private double manualBasePitch = DEFAULT_PITCH;

    // Return-to-home animation state
    private double yawVel   = 0;
    private double pitchVel = 0;
    private boolean returning = false;

    // Short click/"hit" reaction. The shake is applied around the pose that
    // was visible at click time and restores that exact pose afterwards.
    private boolean hitAnimating = false;
    private long hitStartedNanos;
    private double hitBaseYaw;
    private double hitBasePitch;
    private static final double HIT_DURATION_SECONDS = 0.38;

    // Spring constants
    private static final double SPRING_STIFFNESS = 8.0;
    private static final double SPRING_DAMPING   = 4.5;
    private static final double IDLE_SNAP        = 0.0003;

    // ── Idle "look-around" animation ─────────────────────────────────────────
    // Accumulated idle time (only counts while panel is visible and not dragged)
    private double idleTime = 0.0;
    // How many seconds of stillness before idle look-around starts
    private static final double IDLE_GRACE = 0.12;
    // Max yaw and pitch drift from home during idle
    private static final double IDLE_YAW_AMP   = Math.toRadians(14.0);
    private static final double IDLE_PITCH_AMP = Math.toRadians(6.0);
    // Slow, organic frequencies (incommensurable so it never perfectly repeats)
    private static final double IDLE_YAW_HZ   = 0.11;   // ~9s cycle
    private static final double IDLE_PITCH_HZ = 0.073;  // ~14s cycle
    // Second harmonic for micro-jitter naturalness
    private static final double IDLE_YAW_HZ2   = 0.19;
    private static final double IDLE_PITCH_HZ2 = 0.15;
    // Phase offsets so yaw and pitch don't start in sync
    private static final double IDLE_YAW_PHASE   = 1.3;
    private static final double IDLE_PITCH_PHASE  = 2.7;

    // Time tracking for spring integration
    private long lastTickNanos = System.nanoTime();

    // Animation timer — paused when panel is not showing
    private final javax.swing.Timer animationTimer;
    private BufferedImage cachedRender;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private BufferedImage cachedSkin;
    private BufferedImage cachedCape;
    private boolean cachedSlim;
    private boolean cachedSecondLayer;
    private double cachedYaw = Double.NaN;
    private double cachedPitch = Double.NaN;
    private boolean renderDirty = true;

    SkinPreviewPanel() {
        setOpaque(false);
        setDoubleBuffered(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastX = e.getX();
                lastY = e.getY();
                dragging = true;
                dragMoved = false;
                returning = false;
                hitAnimating = false;
                yawVel = 0;
                pitchVel = 0;
                idleTime = 0; // reset idle so look-around restarts after user lets go
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragging) {
                    dragging = false;
                    returning = false;
                    manualPose = true;
                    manualBaseYaw = yaw;
                    manualBasePitch = pitch;
                    idleTime = 0;
                    yawVel = 0;
                    pitchVel = 0;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastX;
                int dy = e.getY() - lastY;
                if (Math.abs(dx) + Math.abs(dy) > 1) {
                    dragMoved = true;
                }
                double dragDyaw   = -dx * 0.012;
                double dragDpitch =  dy * 0.008;
                yaw   += dragDyaw;
                pitch  = clamp(pitch + dragDpitch, Math.toRadians(-28), Math.toRadians(18));
                yawVel   = yawVel   * 0.4 + dragDyaw   * 0.6;
                pitchVel = pitchVel * 0.4 + dragDpitch * 0.6;
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!dragMoved && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    startHitAnimation();
                }
            }
        };

        addMouseListener(mouse);
        addMouseMotionListener(mouse);

        // Timer is created but NOT started — it starts/stops via HierarchyListener
        animationTimer = new javax.swing.Timer(LinuxUiSupport.animationDelay(33), ev -> tick());
        animationTimer.setRepeats(true);

        // Start/stop animation based on whether this panel is part of a shown hierarchy
        addHierarchyListener(e -> {
            boolean nowShowing = isShowing();
            if (LinuxUiSupport.animationsEnabled() && nowShowing && !animationTimer.isRunning()) {
                // Reset time reference so dt doesn't include the hidden gap
                lastTickNanos = System.nanoTime();
                animationTimer.start();
            } else if (!nowShowing && animationTimer.isRunning()) {
                animationTimer.stop();
            }
        });
    }

    /** Called every ~16 ms. Advances spring physics and idle look-around, then repaints. */
    private void tick() {
        long now  = System.nanoTime();
        double dt = Math.min((now - lastTickNanos) / 1e9, 0.05); // cap at 50 ms
        lastTickNanos = now;

        if (dragging) {
            // Keep the character animation clock alive while the user rotates
            // it. The mouse controls the viewing angle, but animation must not
            // pause and then visibly restart after release.
            idleTime += dt;
            renderDirty = true;
            repaint();
            return;
        }

        if (hitAnimating) {
            double elapsed = (now - hitStartedNanos) / 1e9;
            double progress = Math.min(1.0, elapsed / HIT_DURATION_SECONDS);
            double envelope = (1.0 - progress) * (1.0 - progress);
            double oscillation = Math.sin(progress * Math.PI * 7.0);
            yaw = hitBaseYaw + Math.toRadians(11.0) * oscillation * envelope;
            pitch = clamp(hitBasePitch + Math.toRadians(4.0)
                    * Math.sin(progress * Math.PI * 9.0) * envelope,
                    Math.toRadians(-28), Math.toRadians(18));
            if (progress >= 1.0) {
                yaw = hitBaseYaw;
                pitch = hitBasePitch;
                hitAnimating = false;
                manualBaseYaw = hitBaseYaw;
                manualBasePitch = hitBasePitch;
                idleTime = 0;
            }
            renderDirty = true;
            repaint();
            return;
        }

        if (returning) {
            // Spring toward home
            double dyaw   = DEFAULT_YAW   - yaw;
            double dpitch = DEFAULT_PITCH - pitch;

            yawVel   += (SPRING_STIFFNESS * dyaw   - SPRING_DAMPING * yawVel)   * dt;
            pitchVel += (SPRING_STIFFNESS * dpitch - SPRING_DAMPING * pitchVel) * dt;

            yaw   += yawVel   * dt;
            pitch += pitchVel * dt;
            pitch = clamp(pitch, Math.toRadians(-28), Math.toRadians(18));

            // Snap to home when close enough
            if (Math.abs(DEFAULT_YAW - yaw) < IDLE_SNAP
                    && Math.abs(DEFAULT_PITCH - pitch) < IDLE_SNAP
                    && Math.abs(yawVel) < 0.001
                    && Math.abs(pitchVel) < 0.001) {
                yaw   = DEFAULT_YAW;
                pitch = DEFAULT_PITCH;
                yawVel   = 0;
                pitchVel = 0;
                returning = false;
                // idleTime keeps counting from here
            }
        } else if (manualPose) {
            // Continue the idle character motion around the angle selected by
            // the user instead of freezing or snapping back to the home pose.
            idleTime += dt;
            double t = Math.max(0.0, idleTime - IDLE_GRACE);
            double fadeIn = Math.min(1.0, t / 0.25);
            double yawDrift = Math.sin(t * 2 * Math.PI * IDLE_YAW_HZ + IDLE_YAW_PHASE)
                    * IDLE_YAW_AMP * 0.7
                    + Math.sin(t * 2 * Math.PI * IDLE_YAW_HZ2 + IDLE_YAW_PHASE + 1.1)
                    * IDLE_YAW_AMP * 0.3;
            double pitchDrift = Math.sin(t * 2 * Math.PI * IDLE_PITCH_HZ + IDLE_PITCH_PHASE)
                    * IDLE_PITCH_AMP * 0.65
                    + Math.sin(t * 2 * Math.PI * IDLE_PITCH_HZ2 + IDLE_PITCH_PHASE + 0.9)
                    * IDLE_PITCH_AMP * 0.35;
            yaw = manualBaseYaw + yawDrift * fadeIn;
            pitch = clamp(manualBasePitch + pitchDrift * fadeIn,
                    Math.toRadians(-28), Math.toRadians(18));
        } else {
            // Idle: accumulate time, then gently move head with slow sinusoids
            idleTime += dt;

            if (idleTime > IDLE_GRACE) {
                double t = idleTime - IDLE_GRACE;

                // Organic yaw drift: two incommensurable sines blended together
                double yawDrift = Math.sin(t * 2 * Math.PI * IDLE_YAW_HZ + IDLE_YAW_PHASE) * IDLE_YAW_AMP * 0.7
                        + Math.sin(t * 2 * Math.PI * IDLE_YAW_HZ2 + IDLE_YAW_PHASE + 1.1) * IDLE_YAW_AMP * 0.3;

                // Organic pitch drift
                double pitchDrift = Math.sin(t * 2 * Math.PI * IDLE_PITCH_HZ + IDLE_PITCH_PHASE) * IDLE_PITCH_AMP * 0.65
                        + Math.sin(t * 2 * Math.PI * IDLE_PITCH_HZ2 + IDLE_PITCH_PHASE + 0.9) * IDLE_PITCH_AMP * 0.35;

                // Fade in the idle animation gradually over the first 1.5s so it doesn't pop
                double fadeIn = Math.min(1.0, (idleTime - IDLE_GRACE) / 0.25);

                yaw   = DEFAULT_YAW   + yawDrift   * fadeIn;
                pitch = clamp(DEFAULT_PITCH + pitchDrift * fadeIn, Math.toRadians(-28), Math.toRadians(18));
            } else {
                return;
            }
        }

        renderDirty = true;
        repaint();
    }

    private void startHitAnimation() {
        hitBaseYaw = yaw;
        hitBasePitch = pitch;
        manualBaseYaw = hitBaseYaw;
        manualBasePitch = hitBasePitch;
        hitStartedNanos = System.nanoTime();
        hitAnimating = true;
        manualPose = true;
        returning = false;
        yawVel = 0;
        pitchVel = 0;
        if (LinuxUiSupport.animationsEnabled() && !animationTimer.isRunning()) {
            lastTickNanos = hitStartedNanos;
            animationTimer.start();
        }
        renderDirty = true;
        repaint();
    }

    void setSkin(BufferedImage skin, BufferedImage cape, boolean slim) {
        this.skin = skin;
        this.cape = cape;
        this.slim = slim;
        invalidateRenderCache();
        repaint();
    }

    void setShowSecondLayer(boolean showSecondLayer) {
        this.showSecondLayer = showSecondLayer;
        invalidateRenderCache();
        repaint();
    }

    void setPlayerName(String name) {
        this.playerName = name == null ? "" : name;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        // CardLayout visibility changes can fire the hierarchy event while the
        // new card is still considered hidden.  The first real paint is the
        // authoritative signal that the preview is on screen, so make sure the
        // idle animation is running without requiring a mouse click.
        if (LinuxUiSupport.animationsEnabled() && isShowing() && !animationTimer.isRunning()) {
            lastTickNanos = System.nanoTime();
            animationTimer.start();
        }
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        BufferedImage rendered = renderCached();
        g2.drawImage(rendered, 0, 0, null);

        // ── Minecraft-style nickname label above the head ─────────────────────
        if (playerName != null && !playerName.isBlank()) {
            java.awt.Font nameFont = java.awt.Font.decode("Monospaced").deriveFont(java.awt.Font.BOLD, 13f);
            g2.setFont(nameFont);
            java.awt.FontMetrics fm = g2.getFontMetrics(nameFont);
            int textW = fm.stringWidth(playerName);
            int textH = fm.getAscent();
            int padX = 8, padY = 4;
            int pillW = textW + padX * 2;
            int pillH = textH + padY * 2;

            int headTopY = (int) (getHeight() * 0.09);
            int labelY = headTopY - pillH - 4;
            if (labelY < 2) labelY = 2;
            int labelX = getWidth() / 2 - pillW / 2;

            g2.setColor(new Color(0, 0, 0, 160));
            g2.fillRoundRect(labelX, labelY, pillW, pillH, 6, 6);

            g2.setColor(new Color(255, 255, 255, 230));
            g2.drawString(playerName, labelX + padX, labelY + padY + textH - fm.getDescent());
        }

        g2.dispose();
    }

    private BufferedImage renderCached() {
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        boolean needsRender = renderDirty
                || cachedRender == null
                || cachedWidth != width
                || cachedHeight != height
                || cachedSkin != skin
                || cachedCape != cape
                || cachedSlim != slim
                || cachedSecondLayer != showSecondLayer
                || Math.abs(cachedYaw - yaw) > 0.00001
                || Math.abs(cachedPitch - pitch) > 0.00001;
        if (needsRender) {
            cachedRender = SkinRenderer.render3d(skin, cape, slim, width, height, yaw, pitch, showSecondLayer);
            cachedWidth = width;
            cachedHeight = height;
            cachedSkin = skin;
            cachedCape = cape;
            cachedSlim = slim;
            cachedSecondLayer = showSecondLayer;
            cachedYaw = yaw;
            cachedPitch = pitch;
            renderDirty = false;
        }
        return cachedRender;
    }

    private void invalidateRenderCache() {
        renderDirty = true;
        cachedRender = null;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
