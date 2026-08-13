import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SkinRenderer {
    private SkinRenderer() {
    }

    static BufferedImage render(BufferedImage skin, BufferedImage cape, boolean slim, int width, int height) {
        return render3d(skin, cape, slim, width, height, Math.toRadians(-24), Math.toRadians(-7), true);
    }

    static BufferedImage render3d(BufferedImage skin, BufferedImage cape, boolean slim, int width, int height,
                                  double yaw, double pitch) {
        return render3d(skin, cape, slim, width, height, yaw, pitch, true);
    }

    /**
     * Static render — same as render3d but always uses the neutral idle pose
     * with no animation (arms hanging straight, no walk cycle, no wavy cape).
     * Use this for skin cards / avatars where a stable icon is needed.
     */
    static BufferedImage render3dStatic(BufferedImage skin, BufferedImage cape, boolean slim,
                                        int width, int height, double yaw, double pitch, boolean showSecondLayer) {
        return render3dInternal(skin, cape, slim, width, height, yaw, pitch, showSecondLayer, false);
    }

    static BufferedImage render3d(BufferedImage skin, BufferedImage cape, boolean slim, int width, int height,
                                  double yaw, double pitch, boolean showSecondLayer) {
        return render3dInternal(skin, cape, slim, width, height, yaw, pitch, showSecondLayer, true);
    }

    private static BufferedImage render3dInternal(BufferedImage skin, BufferedImage cape, boolean slim, int width, int height,
                                                  double yaw, double pitch, boolean showSecondLayer, boolean animate) {
        BufferedImage safeSkin = skin == null ? transparentImage(64, 64) : skin;
        // Supersampling keeps rotated pixel-art edges stable during animation.
        int ss = Math.max(width, height) < 700 ? 2 : 1;
        int bigW = Math.max(1, width * ss);
        int bigH = Math.max(1, height * ss);

        BufferedImage big = new BufferedImage(bigW, bigH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        // Use nearest neighbor when drawing textures at higher resolution to preserve pixel art character,
        // then downscale with bilinear to smooth edges without blurring details too much.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        List<Face> faces = new ArrayList<>();
        double armWidth = slim ? 3 : 4;
        // Model spans y ∈ [-16, 16] (32 units tall) and is roughly 20-24 units wide
        // including arm spread. Tighter divisors here mean the figure fills more of
        // the preview panel — the previous values (28 / 40 * 1.10) left a lot of
        // empty space top/bottom and shrank the model unnecessarily.
        double scale = Math.min(bigW / 23.0, bigH / 33.0);
        // Center on the body's vertical midpoint (y=0); only a small upward nudge
        // to leave room for the nametag pill above the head. The old "+ scale*1.5"
        // offset pushed the whole model down, clipping the feet at the bottom edge.
        Projector projector = new Projector(bigW / 2.0, bigH / 2.0 - scale * 0.6, scale, yaw, pitch);

        double time = System.nanoTime() / 1e9;

        double leftArmAngle;
        double rightArmAngle;
        double leftLegAngle;
        double rightLegAngle;
        double headPitch;
        double headYaw;
        double armCrossOffset;
        double bodyLeanZ;
        double bodyLiftY;
        double leftLegSplay;
        double rightLegSplay;

        if (animate) {
            // Idle "breathing" animation — gentle arm float, subtle body sway, no walking.
            double breathHz  = 0.28;
            double swayHz    = 0.17;
            double breathPhase = time * 2.0 * Math.PI * breathHz;
            double swayPhase   = time * 2.0 * Math.PI * swayHz;
            double armIdleDrift = Math.sin(breathPhase) * Math.toRadians(4.0);
            double armMicroTwitch = Math.sin(breathPhase * 2.3 + 0.8) * Math.toRadians(1.5);
            leftArmAngle  = armIdleDrift + armMicroTwitch;
            rightArmAngle = -(armIdleDrift - armMicroTwitch * 0.6);
            double legSway = Math.sin(swayPhase) * Math.toRadians(0.5);
            leftLegAngle  = legSway;
            rightLegAngle = -legSway;

            // How strongly the dedicated lean-back/cross-arms animation is in control
            // right now — used to fade out the generic idle touches below so they
            // don't fight with that animation's own deliberate pose.
            double leanBlend = AnimSequencer.getLeanBlend(time);

            // New resting pose isn't perfectly static: the torso (and the arms riding
            // along with it) eases upward on a breath, then settles back down slowly —
            // and the legs drift gently side to side, like a small weight shift.
            double bobHz = 0.22;
            double bobCycle = bobHz * time - Math.floor(bobHz * time);
            double bobRiseFrac = 0.30;
            double bobValue = bobCycle < bobRiseFrac
                    ? smoothstep(clamp01(bobCycle / bobRiseFrac))
                    : 1.0 - smoothstep(clamp01((bobCycle - bobRiseFrac) / (1.0 - bobRiseFrac)));
            bodyLiftY = -bobValue * 0.55 * (1.0 - leanBlend);

            double splayHz = 0.085;
            double legSplayIdle = Math.sin(time * 2.0 * Math.PI * splayHz) * 0.55 * (1.0 - leanBlend);
            leftLegSplay  = -legSplayIdle;
            rightLegSplay =  legSplayIdle;

            // Occasionally glance left or right, independent of the main pose timeline.
            headYaw = HeadLookSequencer.getYaw(time) * (1.0 - leanBlend);

            Pose pose = AnimSequencer.getPose(time);
            double walkPhase = AnimSequencer.getWalkPhase(time);
            if (walkPhase > 0) {
                double swing = Math.sin(time * 2 * Math.PI * 1.8) * Math.toRadians(38) * walkPhase;
                leftLegAngle  += swing;
                rightLegAngle -= swing;
                leftArmAngle  -= swing * 0.6;
                rightArmAngle += swing * 0.6;
            }

            // The lean-back animation also rocks the torso and legs forward and back
            // together while it's held (two full cycles), easing back to neutral after.
            double rockOsc = AnimSequencer.getRockOscillation(time);
            leftArmAngle  += pose.leftArm();
            rightArmAngle += pose.rightArm();
            leftLegAngle  += pose.leftLeg()  + rockOsc * Math.toRadians(7.0);
            rightLegAngle += pose.rightLeg() + rockOsc * Math.toRadians(7.0);
            headPitch = pose.headPitch();
            armCrossOffset = pose.armCross();
            bodyLeanZ = pose.bodyLeanZ() + rockOsc * 1.1;
        } else {
            // Static / neutral pose — arms hang straight, no sway, no animation.
            leftArmAngle  = Math.toRadians(5.0);
            rightArmAngle = Math.toRadians(-5.0);
            leftLegAngle  = 0;
            rightLegAngle = 0;
            headPitch = 0;
            headYaw = 0;
            armCrossOffset = 0;
            bodyLeanZ = 0;
            bodyLiftY = 0;
            leftLegSplay = 0;
            rightLegSplay = 0;
        }

        if (cape != null && !isTransparent(cape)) {
            if (animate) {
                addWavyCape(faces, projector, cape, time);
            } else {
                addStaticCape(faces, projector, cape);
            }
        }

        addCuboidRotatedXY(faces, projector, safeSkin, -4, -16, -4, 4, -8, 4, new CuboidTextures(
                        crop(safeSkin, 8, 8, 8, 8), crop(safeSkin, 24, 8, 8, 8),
                        crop(safeSkin, 16, 8, 8, 8), crop(safeSkin, 0, 8, 8, 8),
                        crop(safeSkin, 8, 0, 8, 8), crop(safeSkin, 16, 0, 8, 8)),
                0.0, -8.0, 0.0, headYaw, headPitch, 0.0, bodyLiftY, bodyLeanZ);
        BufferedImage headOverlay = crop(safeSkin, 40, 8, 8, 8);
        if (showSecondLayer && !isTransparent(headOverlay)) {
            addCuboidRotatedXY(faces, projector, safeSkin, -4.35, -16.35, -4.35, 4.35, -7.65, 4.35, new CuboidTextures(
                            headOverlay, crop(safeSkin, 56, 8, 8, 8),
                            crop(safeSkin, 48, 8, 8, 8), crop(safeSkin, 32, 8, 8, 8),
                            crop(safeSkin, 40, 0, 8, 8), crop(safeSkin, 48, 0, 8, 8)),
                    0.0, -8.0, 0.0, headYaw, headPitch, 0.0, bodyLiftY, bodyLeanZ);
        }

        addCuboid(faces, projector, safeSkin, -4, -8, -2, 4, 4, 2, new CuboidTextures(
                crop(safeSkin, 20, 20, 8, 12), crop(safeSkin, 32, 20, 8, 12),
                crop(safeSkin, 28, 20, 4, 12), crop(safeSkin, 16, 20, 4, 12),
                crop(safeSkin, 20, 16, 8, 4), crop(safeSkin, 28, 16, 8, 4)), 0.0, bodyLiftY, bodyLeanZ);
        if (showSecondLayer) {
            addCuboid(faces, projector, safeSkin, -4.25, -8.25, -2.25, 4.25, 4.25, 2.25, new CuboidTextures(
                    crop(safeSkin, 20, 36, 8, 12), crop(safeSkin, 32, 36, 8, 12),
                    crop(safeSkin, 28, 36, 4, 12), crop(safeSkin, 16, 36, 4, 12),
                    crop(safeSkin, 20, 32, 8, 4), crop(safeSkin, 28, 32, 8, 4)), 0.0, bodyLiftY, bodyLeanZ);
        }

        addCuboidRotatedXY(faces, projector, safeSkin, -4 - armWidth, -8, -2, -4, 4, 2, new CuboidTextures(
                        crop(safeSkin, 44, 20, 4, 12), crop(safeSkin, 52, 20, 4, 12),
                        crop(safeSkin, 48, 20, 4, 12), crop(safeSkin, 40, 20, 4, 12),
                        crop(safeSkin, 44, 16, 4, 4), crop(safeSkin, 48, 16, 4, 4)),
                -4.0, -8.0, 0.0, armCrossOffset, leftArmAngle, 0.0, bodyLiftY, bodyLeanZ);
        if (showSecondLayer) {
            addCuboidRotatedXY(faces, projector, safeSkin, -4 - armWidth - 0.25, -8.25, -2.25, -3.75, 4.25, 2.25, new CuboidTextures(
                            crop(safeSkin, 44, 36, 4, 12), crop(safeSkin, 52, 36, 4, 12),
                            crop(safeSkin, 48, 36, 4, 12), crop(safeSkin, 40, 36, 4, 12),
                            crop(safeSkin, 44, 32, 4, 4), crop(safeSkin, 48, 32, 4, 4)),
                    -4.0, -8.0, 0.0, armCrossOffset, leftArmAngle, 0.0, bodyLiftY, bodyLeanZ);
        }
        addCuboidRotatedXY(faces, projector, safeSkin, 4, -8, -2, 4 + armWidth, 4, 2, new CuboidTextures(
                        cropOrMirror(safeSkin, 36, 52, 4, 12, 44, 20, 4, 12), cropOrMirror(safeSkin, 44, 52, 4, 12, 52, 20, 4, 12),
                        cropOrMirror(safeSkin, 40, 52, 4, 12, 48, 20, 4, 12), cropOrMirror(safeSkin, 32, 52, 4, 12, 40, 20, 4, 12),
                        cropOrMirror(safeSkin, 36, 48, 4, 4, 44, 16, 4, 4), cropOrMirror(safeSkin, 40, 48, 4, 4, 48, 16, 4, 4)),
                4.0, -8.0, 0.0, -armCrossOffset, rightArmAngle, 0.0, bodyLiftY, bodyLeanZ);
        if (showSecondLayer) {
            addCuboidRotatedXY(faces, projector, safeSkin, 3.75, -8.25, -2.25, 4 + armWidth + 0.25, 4.25, 2.25, new CuboidTextures(
                            crop(safeSkin, 52, 52, 4, 12), crop(safeSkin, 60, 52, 4, 12),
                            crop(safeSkin, 56, 52, 4, 12), crop(safeSkin, 48, 52, 4, 12),
                            crop(safeSkin, 52, 48, 4, 4), crop(safeSkin, 56, 48, 4, 4)),
                    4.0, -8.0, 0.0, -armCrossOffset, rightArmAngle, 0.0, bodyLiftY, bodyLeanZ);
        }

        addCuboidRotatedX(faces, projector, safeSkin, -4, 4, -2, 0, 16, 2, new CuboidTextures(
                        crop(safeSkin, 4, 20, 4, 12), crop(safeSkin, 12, 20, 4, 12),
                        crop(safeSkin, 8, 20, 4, 12), crop(safeSkin, 0, 20, 4, 12),
                        crop(safeSkin, 4, 16, 4, 4), crop(safeSkin, 8, 16, 4, 4)),
                -2.0, 4.0, 0.0, leftLegAngle, leftLegSplay, bodyLiftY, bodyLeanZ);
        if (showSecondLayer) {
            addCuboidRotatedX(faces, projector, safeSkin, -4.25, 3.75, -2.25, 0.15, 16.25, 2.25, new CuboidTextures(
                            crop(safeSkin, 4, 36, 4, 12), crop(safeSkin, 12, 36, 4, 12),
                            crop(safeSkin, 8, 36, 4, 12), crop(safeSkin, 0, 36, 4, 12),
                            crop(safeSkin, 4, 32, 4, 4), crop(safeSkin, 8, 32, 4, 4)),
                    -2.0, 4.0, 0.0, leftLegAngle, leftLegSplay, bodyLiftY, bodyLeanZ);
        }
        addCuboidRotatedX(faces, projector, safeSkin, 0, 4, -2, 4, 16, 2, new CuboidTextures(
                        cropOrMirror(safeSkin, 20, 52, 4, 12, 4, 20, 4, 12), cropOrMirror(safeSkin, 28, 52, 4, 12, 12, 20, 4, 12),
                        cropOrMirror(safeSkin, 24, 52, 4, 12, 8, 20, 4, 12), cropOrMirror(safeSkin, 16, 52, 4, 12, 0, 20, 4, 12),
                        cropOrMirror(safeSkin, 20, 48, 4, 4, 4, 16, 4, 4), cropOrMirror(safeSkin, 24, 48, 4, 4, 8, 16, 4, 4)),
                2.0, 4.0, 0.0, rightLegAngle, rightLegSplay, bodyLiftY, bodyLeanZ);
        if (showSecondLayer) {
            addCuboidRotatedX(faces, projector, safeSkin, -0.15, 3.75, -2.25, 4.25, 16.25, 2.25, new CuboidTextures(
                            crop(safeSkin, 4, 52, 4, 12), crop(safeSkin, 12, 52, 4, 12),
                            crop(safeSkin, 8, 52, 4, 12), crop(safeSkin, 0, 52, 4, 12),
                            crop(safeSkin, 4, 48, 4, 4), crop(safeSkin, 8, 48, 4, 4)),
                    2.0, 4.0, 0.0, rightLegAngle, rightLegSplay, bodyLiftY, bodyLeanZ);
        }

        faces.sort(Comparator.comparingDouble(Face::depth));
        for (Face face : faces) {
            drawTexturedQuad(g, face);
        }
        g.dispose();
        if (ss == 1) return big;

        BufferedImage out = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D gOut = out.createGraphics();
        gOut.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gOut.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gOut.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        gOut.drawImage(big, 0, 0, width, height, null);
        gOut.dispose();
        return out;
    }

    // ── Animation state machine ─────────────────────────────────────────────
    // Each animation plays as: IDLE (pause) → FADE_IN → HOLD → FADE_OUT → next
    // Walking animation uses walkPhase instead of a static pose during HOLD.

    private record Pose(double leftArm, double rightArm, double leftLeg, double rightLeg, double headPitch,
                        double armCross, double bodyLeanZ) {
        static final Pose NEUTRAL = new Pose(0, 0, 0, 0, 0, 0, 0);

        // Convenience constructor for poses that don't use the arm-cross / body-lean
        // channels (keeps the existing simple pose definitions below unchanged).
        Pose(double leftArm, double rightArm, double leftLeg, double rightLeg, double headPitch) {
            this(leftArm, rightArm, leftLeg, rightLeg, headPitch, 0, 0);
        }

        static Pose lerp(Pose a, Pose b, double t) {
            return new Pose(
                    a.leftArm  + (b.leftArm  - a.leftArm)  * t,
                    a.rightArm + (b.rightArm - a.rightArm) * t,
                    a.leftLeg  + (b.leftLeg  - a.leftLeg)  * t,
                    a.rightLeg + (b.rightLeg - a.rightLeg) * t,
                    a.headPitch + (b.headPitch - a.headPitch) * t,
                    a.armCross + (b.armCross - a.armCross) * t,
                    a.bodyLeanZ + (b.bodyLeanZ - a.bodyLeanZ) * t);
        }
    }

    // Animation descriptor: pose + whether it uses walk-swing or rocking-sway during HOLD
    private record Anim(Pose pose, boolean isWalk, boolean isRock,
                        double idleSecs, double fadeInSecs, double holdSecs, double fadeOutSecs) {
        double totalSecs() { return idleSecs + fadeInSecs + holdSecs + fadeOutSecs; }
    }

    // 5. Lean back, cross the arms over the chest, knees pulled back, head dipped —
    //    then the torso and legs rock forward/back together twice before settling
    //    back to neutral.
    private static final Anim LEAN_BACK_ANIM = new Anim(new Pose(
            Math.toRadians(96), Math.toRadians(96),
            Math.toRadians(-20), Math.toRadians(-20),
            Math.toRadians(18),
            Math.toRadians(75), -2.6),
            false, true, 4.5, 0.9, 3.0, 0.9);

    private static final Anim[] ANIM_SEQUENCE = {
            // 1. Arms up + head tilt — raise, hold, lower
            new Anim(new Pose(Math.toRadians(150), Math.toRadians(150),
                    Math.toRadians(8), Math.toRadians(-8), Math.toRadians(-18)),
                    false, false, 4.0, 1.2, 2.0, 1.2),
            // 2. Wave
            new Anim(new Pose(Math.toRadians(-15), Math.toRadians(160),
                    Math.toRadians(10), Math.toRadians(-10), Math.toRadians(-8)),
                    false, false, 4.0, 1.0, 2.5, 1.0),
            // 3. Bow / look down
            new Anim(new Pose(Math.toRadians(32), Math.toRadians(32),
                    0, 0, Math.toRadians(26)),
                    false, false, 3.5, 1.0, 1.8, 1.0),
    };

    static final class AnimSequencer {
        private AnimSequencer() {}

        /** Returns the blended Pose for the current wall-clock time. */
        static Pose getPose(double time) {
            AnimState s = stateAt(time);
            if (s.anim.isWalk()) return Pose.NEUTRAL; // walk uses swing, not pose
            return Pose.lerp(Pose.NEUTRAL, s.anim.pose(), s.blend);
        }

        /** Returns walk blend weight [0..1] — nonzero only during a walk anim HOLD. */
        static double getWalkPhase(double time) {
            AnimState s = stateAt(time);
            if (!s.anim.isWalk()) return 0;
            return s.blend;
        }

        /**
         * Returns the blend weight [0..1] of the lean-back / cross-arms animation.
         * Used to fade out unrelated idle behaviors (head glances, leg drift) while
         * that animation has its own deliberate pose in control.
         */
        static double getLeanBlend(double time) {
            AnimState s = stateAt(time);
            return s.anim == LEAN_BACK_ANIM ? s.blend : 0;
        }

        /**
         * Returns a -1..1 oscillation describing two full forward/back rocking
         * cycles spread evenly across the lean-back animation's HOLD phase, and
         * 0 everywhere else (including its own fade in/out, so it never pops).
         */
        static double getRockOscillation(double time) {
            AnimState s = stateAt(time);
            if (s.anim != LEAN_BACK_ANIM) return 0;
            double holdSecs = s.anim.holdSecs();
            if (holdSecs <= 0) return 0;
            double tHold = s.t - s.anim.idleSecs() - s.anim.fadeInSecs();
            if (tHold < 0 || tHold > holdSecs) return 0;
            double cycles = 2.0;
            return Math.sin(tHold * 2.0 * Math.PI * cycles / holdSecs);
        }

        private record AnimState(Anim anim, double t, double blend) {}

        private static AnimState stateAt(double time) {
            // Find which animation and phase we're in
            // Total period = sum of all anim durations
            double total = 0;
            for (Anim a : ANIM_SEQUENCE) total += a.totalSecs();

            double t = time % total;
            for (Anim a : ANIM_SEQUENCE) {
                double dur = a.totalSecs();
                if (t < dur) {
                    double blend = computeBlend(a, t);
                    return new AnimState(a, t, blend);
                }
                t -= dur;
            }
            return new AnimState(ANIM_SEQUENCE[0], 0, 0);
        }

        private static double computeBlend(Anim a, double t) {
            // Phases: [0, idleSecs) = 0 blend
            //         [idleSecs, idleSecs+fadeInSecs) = 0→1
            //         [idleSecs+fadeInSecs, ..+holdSecs) = 1
            //         [idleSecs+fadeInSecs+holdSecs, ..+fadeOutSecs) = 1→0
            if (t < a.idleSecs()) return 0;
            t -= a.idleSecs();
            if (t < a.fadeInSecs()) return smoothstep(clamp01(t / a.fadeInSecs()));
            t -= a.fadeInSecs();
            if (t < a.holdSecs()) return 1.0;
            t -= a.holdSecs();
            return 1.0 - smoothstep(clamp01(t / a.fadeOutSecs()));
        }
    }

    // ── Idle head-look sequencer ────────────────────────────────────────────
    // Independent of the main animation timeline above: the head occasionally
    // glances to one side and eases back, so the character still looks alive
    // while it's just idling between named animations.
    private record Look(double yawDeg, double idleSecs, double turnSecs, double holdSecs, double returnSecs) {
        double totalSecs() { return idleSecs + turnSecs + holdSecs + returnSecs; }
    }

    private static final Look[] LOOK_SEQUENCE = {
            new Look(24, 3.4, 0.8, 1.1, 0.9),
            new Look(-30, 5.2, 0.9, 1.2, 0.9),
            new Look(15, 6.8, 0.7, 0.9, 0.7),
    };

    private static final class HeadLookSequencer {
        private HeadLookSequencer() {}

        static double getYaw(double time) {
            double total = 0;
            for (Look l : LOOK_SEQUENCE) total += l.totalSecs();
            if (total <= 0) return 0;

            double t = time % total;
            for (Look l : LOOK_SEQUENCE) {
                double dur = l.totalSecs();
                if (t < dur) return yawAt(l, t);
                t -= dur;
            }
            return 0;
        }

        private static double yawAt(Look l, double t) {
            if (t < l.idleSecs()) return 0;
            t -= l.idleSecs();
            double target = Math.toRadians(l.yawDeg());
            if (t < l.turnSecs()) return target * smoothstep(clamp01(t / l.turnSecs()));
            t -= l.turnSecs();
            if (t < l.holdSecs()) return target;
            t -= l.holdSecs();
            return target * (1.0 - smoothstep(clamp01(t / l.returnSecs())));
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Smooth ease (3t² − 2t³) so transitions accelerate in and decelerate out. */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static void addCape(List<Face> faces, Projector projector, BufferedImage cape) {
        // Outer (visible) face of the cape at UV (1,1) — flipped vertically (Minecraft stores it upside-down)
        BufferedImage outer = flipVertical(crop(cape, 1, 1, 10, 16));
        // inner face (against the back) is at UV (12,1)
        BufferedImage inner = flipVertical(crop(cape, 12, 1, 10, 16));
        if (isTransparent(inner)) {
            inner = crop(cape, 0, 0, Math.min(22, cape.getWidth()), Math.min(17, cape.getHeight()));
        }
        double topLeftX = -3.0;
        double topRightX = 3.0;
        double bottomLeftX = -9.6;
        double bottomRightX = 8.0;
        double yTop = -7.2;
        double yBottom = 10.4;
        double zOuter = -3.6; // further from viewer = behind the body
        double zInner = -3.0; // closer to body

        // Outer face: faces away from player (toward viewer when player faces away)
        addFace(faces, projector, outer,
                topRightX, yTop, zOuter,
                topLeftX, yTop, zOuter,
                bottomLeftX, yBottom, zOuter,
                bottomRightX, yBottom, zOuter,
                1.0);
        // Inner face: faces toward player's back
        addFace(faces, projector, inner,
                topLeftX, yTop, zInner,
                topRightX, yTop, zInner,
                bottomRightX, yBottom, zInner,
                bottomLeftX, yBottom, zInner,
                0.58);
    }

    private static void addCuboid(List<Face> faces, Projector projector, BufferedImage skin,
                                  double x1, double y1, double z1, double x2, double y2, double z2,
                                  CuboidTextures textures) {
        addFace(faces, projector, textures.front(), x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, 1.0);
        addFace(faces, projector, textures.back(), x2, y1, z1, x1, y1, z1, x1, y2, z1, x2, y2, z1, 0.58);
        addFace(faces, projector, textures.left(), x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2, 0.78);
        addFace(faces, projector, textures.right(), x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, 0.72);
        addFace(faces, projector, textures.top(), x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, 1.12);
        addFace(faces, projector, textures.bottom(), x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, 0.62);
    }

    /** Same as addCuboid but rigidly translated by (dx, dy, dz) — used to let the
     *  torso ride along with the idle breathing bob / lean-back offset. */
    private static void addCuboid(List<Face> faces, Projector projector, BufferedImage skin,
                                  double x1, double y1, double z1, double x2, double y2, double z2,
                                  CuboidTextures textures, double dx, double dy, double dz) {
        addCuboid(faces, projector, skin, x1 + dx, y1 + dy, z1 + dz, x2 + dx, y2 + dy, z2 + dz, textures);
    }

    private static void addFace(List<Face> faces, Projector projector, BufferedImage texture,
                                double x0, double y0, double z0, double x1, double y1, double z1,
                                double x2, double y2, double z2, double x3, double y3, double z3,
                                double shade) {
        if (texture == null || texture.getWidth() <= 0 || texture.getHeight() <= 0 || isTransparent(texture)) {
            return;
        }
        Projected p0 = projector.project(x0, y0, z0);
        Projected p1 = projector.project(x1, y1, z1);
        Projected p2 = projector.project(x2, y2, z2);
        Projected p3 = projector.project(x3, y3, z3);
        double depth = (p0.z() + p1.z() + p2.z() + p3.z()) / 4.0;
        faces.add(new Face(texture, p0.point(), p1.point(), p2.point(), p3.point(), depth, shade));
    }

    private static void drawTexturedQuad(Graphics2D g, Face face) {
        Polygon clip = new Polygon(
                new int[] {(int) Math.round(face.p0().getX()), (int) Math.round(face.p1().getX()), (int) Math.round(face.p2().getX()), (int) Math.round(face.p3().getX())},
                new int[] {(int) Math.round(face.p0().getY()), (int) Math.round(face.p1().getY()), (int) Math.round(face.p2().getY()), (int) Math.round(face.p3().getY())},
                4
        );
        var oldClip = g.getClip();
        g.setClip(clip);
        BufferedImage texture = face.texture();
        double m00 = (face.p1().getX() - face.p0().getX()) / texture.getWidth();
        double m10 = (face.p1().getY() - face.p0().getY()) / texture.getWidth();
        double m01 = (face.p3().getX() - face.p0().getX()) / texture.getHeight();
        double m11 = (face.p3().getY() - face.p0().getY()) / texture.getHeight();
        AffineTransform transform = new AffineTransform(m00, m10, m01, m11, face.p0().getX(), face.p0().getY());
        g.drawImage(texture, transform, null);
        if (face.shade() < 0.99) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(0.45, 1.0 - face.shade())));
            g.setColor(Color.BLACK);
            g.fillPolygon(clip);
            g.setComposite(AlphaComposite.SrcOver);
        } else if (face.shade() > 1.01) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f));
            g.setColor(Color.WHITE);
            g.fillPolygon(clip);
            g.setComposite(AlphaComposite.SrcOver);
        }
        g.setClip(oldClip);
    }

    // Rotate a point around the X axis (pivot) by angle (radians)
    private static double[] rotateAroundX(double x, double y, double z, double px, double py, double pz, double angle) {
        double dy = y - py;
        double dz = z - pz;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double ny = py + cos * dy - sin * dz;
        double nz = pz + sin * dy + cos * dz;
        return new double[] { x, ny, nz };
    }

    // Add a cuboid but rotate all its vertices around X axis (useful for arm swing)
    private static void addCuboidRotatedX(List<Face> faces, Projector projector, BufferedImage skin,
                                          double x1, double y1, double z1, double x2, double y2, double z2,
                                          CuboidTextures textures, double pivotX, double pivotY, double pivotZ, double angle) {
        // front (z2)
        double[] p0 = rotateAroundX(x1, y1, z2, pivotX, pivotY, pivotZ, angle);
        double[] p1 = rotateAroundX(x2, y1, z2, pivotX, pivotY, pivotZ, angle);
        double[] p2 = rotateAroundX(x2, y2, z2, pivotX, pivotY, pivotZ, angle);
        double[] p3 = rotateAroundX(x1, y2, z2, pivotX, pivotY, pivotZ, angle);
        addFace(faces, projector, textures.front(), p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], 1.0);
        // back (z1)
        double[] q0 = rotateAroundX(x2, y1, z1, pivotX, pivotY, pivotZ, angle);
        double[] q1 = rotateAroundX(x1, y1, z1, pivotX, pivotY, pivotZ, angle);
        double[] q2 = rotateAroundX(x1, y2, z1, pivotX, pivotY, pivotZ, angle);
        double[] q3 = rotateAroundX(x2, y2, z1, pivotX, pivotY, pivotZ, angle);
        addFace(faces, projector, textures.back(), q0[0], q0[1], q0[2], q1[0], q1[1], q1[2], q2[0], q2[1], q2[2], q3[0], q3[1], q3[2], 0.58);
        // left
        double[] l0 = rotateAroundX(x2, y1, z2, pivotX, pivotY, pivotZ, angle);
        double[] l1 = rotateAroundX(x2, y1, z1, pivotX, pivotY, pivotZ, angle);
        double[] l2 = rotateAroundX(x2, y2, z1, pivotX, pivotY, pivotZ, angle);
        double[] l3 = rotateAroundX(x2, y2, z2, pivotX, pivotY, pivotZ, angle);
        addFace(faces, projector, textures.left(), l0[0], l0[1], l0[2], l1[0], l1[1], l1[2], l2[0], l2[1], l2[2], l3[0], l3[1], l3[2], 0.78);
        // right
        double[] r0 = rotateAroundX(x1, y1, z1, pivotX, pivotY, pivotZ, angle);
        double[] r1 = rotateAroundX(x1, y1, z2, pivotX, pivotY, pivotZ, angle);
        double[] r2 = rotateAroundX(x1, y2, z2, pivotX, pivotY, pivotZ, angle);
        double[] r3 = rotateAroundX(x1, y2, z1, pivotX, pivotY, pivotZ, angle);
        addFace(faces, projector, textures.right(), r0[0], r0[1], r0[2], r1[0], r1[1], r1[2], r2[0], r2[1], r2[2], r3[0], r3[1], r3[2], 0.72);
        // top
        double[] t0 = rotateAroundX(x1, y1, z1, pivotX, pivotY, pivotZ, angle);
        double[] t1 = rotateAroundX(x2, y1, z1, pivotX, pivotY, pivotZ, angle);
        double[] t2 = rotateAroundX(x2, y1, z2, pivotX, pivotY, pivotZ, angle);
        double[] t3 = rotateAroundX(x1, y1, z2, pivotX, pivotY, pivotZ, angle);
        addFace(faces, projector, textures.top(), t0[0], t0[1], t0[2], t1[0], t1[1], t1[2], t2[0], t2[1], t2[2], t3[0], t3[1], t3[2], 1.12);
        // bottom
        double[] b0 = rotateAroundX(x1, y2, z2, pivotX, pivotY, pivotZ, angle);
        double[] b1 = rotateAroundX(x2, y2, z2, pivotX, pivotY, pivotZ, angle);
        double[] b2 = rotateAroundX(x2, y2, z1, pivotX, pivotY, pivotZ, angle);
        double[] b3 = rotateAroundX(x1, y2, z1, pivotX, pivotY, pivotZ, angle);
        addFace(faces, projector, textures.bottom(), b0[0], b0[1], b0[2], b1[0], b1[1], b1[2], b2[0], b2[1], b2[2], b3[0], b3[1], b3[2], 0.62);
    }

    /**
     * Same as addCuboidRotatedX but additionally translated by (dx, dy, dz) after
     * rotating around the pivot — shifting both the cuboid and the pivot by the
     * same amount keeps the rotation identical and just rigidly relocates the
     * result, so this is how the arms/legs ride along with the torso's idle
     * bob, lean-back offset, or sideways drift.
     */
    private static void addCuboidRotatedX(List<Face> faces, Projector projector, BufferedImage skin,
                                          double x1, double y1, double z1, double x2, double y2, double z2,
                                          CuboidTextures textures, double pivotX, double pivotY, double pivotZ,
                                          double angle, double dx, double dy, double dz) {
        addCuboidRotatedX(faces, projector, skin, x1 + dx, y1 + dy, z1 + dz, x2 + dx, y2 + dy, z2 + dz,
                textures, pivotX + dx, pivotY + dy, pivotZ + dz, angle);
    }

    // Rotate a point around the Y axis (pivot) by angle (radians) — used for head turn (yaw)
    private static double[] rotateAroundY(double x, double y, double z, double px, double py, double pz, double angle) {
        double dx = x - px;
        double dz = z - pz;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double nx = px + cos * dx + sin * dz;
        double nz = pz - sin * dx + cos * dz;
        return new double[] { nx, y, nz };
    }

    // Turn (yaw, around Y) then nod (pitch, around X) around the same pivot — used for the head.
    private static double[] rotateYThenX(double x, double y, double z, double px, double py, double pz,
                                         double angleY, double angleX) {
        double[] yawed = rotateAroundY(x, y, z, px, py, pz, angleY);
        return rotateAroundX(yawed[0], yawed[1], yawed[2], px, py, pz, angleX);
    }

    // Add a cuboid rotated by both yaw (Y) and pitch (X) around the pivot — used for the head,
    // so it can nod up/down and occasionally turn left/right at the same time.
    private static void addCuboidRotatedXY(List<Face> faces, Projector projector, BufferedImage skin,
                                           double x1, double y1, double z1, double x2, double y2, double z2,
                                           CuboidTextures textures, double pivotX, double pivotY, double pivotZ,
                                           double angleY, double angleX) {
        // front (z2)
        double[] p0 = rotateYThenX(x1, y1, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] p1 = rotateYThenX(x2, y1, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] p2 = rotateYThenX(x2, y2, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] p3 = rotateYThenX(x1, y2, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        addFace(faces, projector, textures.front(), p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], 1.0);
        // back (z1)
        double[] q0 = rotateYThenX(x2, y1, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] q1 = rotateYThenX(x1, y1, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] q2 = rotateYThenX(x1, y2, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] q3 = rotateYThenX(x2, y2, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        addFace(faces, projector, textures.back(), q0[0], q0[1], q0[2], q1[0], q1[1], q1[2], q2[0], q2[1], q2[2], q3[0], q3[1], q3[2], 0.58);
        // left
        double[] l0 = rotateYThenX(x2, y1, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] l1 = rotateYThenX(x2, y1, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] l2 = rotateYThenX(x2, y2, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] l3 = rotateYThenX(x2, y2, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        addFace(faces, projector, textures.left(), l0[0], l0[1], l0[2], l1[0], l1[1], l1[2], l2[0], l2[1], l2[2], l3[0], l3[1], l3[2], 0.78);
        // right
        double[] r0 = rotateYThenX(x1, y1, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] r1 = rotateYThenX(x1, y1, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] r2 = rotateYThenX(x1, y2, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] r3 = rotateYThenX(x1, y2, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        addFace(faces, projector, textures.right(), r0[0], r0[1], r0[2], r1[0], r1[1], r1[2], r2[0], r2[1], r2[2], r3[0], r3[1], r3[2], 0.72);
        // top
        double[] t0 = rotateYThenX(x1, y1, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] t1 = rotateYThenX(x2, y1, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] t2 = rotateYThenX(x2, y1, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] t3 = rotateYThenX(x1, y1, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        addFace(faces, projector, textures.top(), t0[0], t0[1], t0[2], t1[0], t1[1], t1[2], t2[0], t2[1], t2[2], t3[0], t3[1], t3[2], 1.12);
        // bottom
        double[] b0 = rotateYThenX(x1, y2, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] b1 = rotateYThenX(x2, y2, z2, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] b2 = rotateYThenX(x2, y2, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        double[] b3 = rotateYThenX(x1, y2, z1, pivotX, pivotY, pivotZ, angleY, angleX);
        addFace(faces, projector, textures.bottom(), b0[0], b0[1], b0[2], b1[0], b1[1], b1[2], b2[0], b2[1], b2[2], b3[0], b3[1], b3[2], 0.62);
    }

    /** Same as addCuboidRotatedXY but additionally translated by (dx, dy, dz) after
     *  rotating — lets the head ride along with the torso's idle bob / lean-back offset. */
    private static void addCuboidRotatedXY(List<Face> faces, Projector projector, BufferedImage skin,
                                           double x1, double y1, double z1, double x2, double y2, double z2,
                                           CuboidTextures textures, double pivotX, double pivotY, double pivotZ,
                                           double angleY, double angleX, double dx, double dy, double dz) {
        addCuboidRotatedXY(faces, projector, skin, x1 + dx, y1 + dy, z1 + dz, x2 + dx, y2 + dy, z2 + dz,
                textures, pivotX + dx, pivotY + dy, pivotZ + dz, angleY, angleX);
    }
    private static void addStaticCape(List<Face> faces, Projector projector, BufferedImage cape) {
        BufferedImage outer = crop(cape, 12, 1, 10, 16);
        BufferedImage inner = crop(cape, 1, 1, 10, 16);
        if (isTransparent(inner)) inner = outer;

        double xLeft  = -5.0;
        double xRight =  5.0;
        double yTop   = -7.2;
        double yBottom = 10.6;
        double shoulderZ = -2.9;
        double backSwing  = 4.0;
        double thickness  = 0.46;

        // Outer face
        addFace(faces, projector, outer,
                xRight, yTop,    shoulderZ,
                xLeft,  yTop,    shoulderZ,
                xLeft,  yBottom, shoulderZ - backSwing,
                xRight, yBottom, shoulderZ - backSwing,
                0.95);
        // Inner face
        addFace(faces, projector, inner,
                xLeft,  yTop,    shoulderZ - thickness,
                xRight, yTop,    shoulderZ - thickness,
                xRight, yBottom, shoulderZ - backSwing - thickness,
                xLeft,  yBottom, shoulderZ - backSwing - thickness,
                0.60);
    }

    private static void addWavyCape(List<Face> faces, Projector projector, BufferedImage cape, double time) {
        // Cape UV layout (64x32, or 22x17 for legacy OptiFine-style capes):
        // (12,1) size 10x16 = outer/design face (visible side when worn, faces away from player's body)
        // (1,1)  size 10x16 = inner face (faces toward player's back, normally plain/blank)
        // Minecraft stores cape textures vertically flipped — flip back so it renders right-side up,
        // then rotate 180° so the design reads correctly when the cape hangs on the back.
        BufferedImage outer = crop(cape, 12, 1, 10, 16); // design side (faces viewer) - no flip needed
        BufferedImage inner = crop(cape, 1, 1, 10, 16);  // plain inner side
        if (isTransparent(inner)) {
            // Some legacy/odd-sized cape textures don't populate the inner-face UV region.
            // Fall back to the design texture so the cape isn't see-through from the inside.
            inner = outer;
        }
        BufferedImage leftEdge = crop(cape, 0, 1, 1, 16);
        BufferedImage rightEdge = crop(cape, 11, 1, 1, 16);

        double xLeft = -5.0;
        double xRight = 5.0;
        double yTop = -7.2;
        double yBottom = 10.6;
        double thickness = 0.46;
        double bottomDrift = -1.55;
        // Cape hangs BEHIND the body: body back face is at z=-2, cape outer face starts just behind that
        double shoulderZ = -2.9;
        double backSwing = 5.5;  // how far cape swings back at bottom
        int segments = 5;
        double cycle = time * 2.0 * Math.PI * 0.72;

        for (int i = 0; i < segments; i++) {
            double t0 = i / (double) segments;
            double t1 = (i + 1) / (double) segments;
            double y0 = yTop + (yBottom - yTop) * t0;
            double y1 = yTop + (yBottom - yTop) * t1;
            double wave0 = Math.sin(cycle + t0 * 4.2) * 0.34 * t0;
            double wave1 = Math.sin(cycle + t1 * 4.2) * 0.34 * t1;
            double leftDrift0 = bottomDrift * t0;
            double leftDrift1 = bottomDrift * t1;
            double rightDrift0 = bottomDrift * 0.18 * t0;
            double rightDrift1 = bottomDrift * 0.18 * t1;
            double z0 = shoulderZ - Math.pow(t0, 1.18) * backSwing + Math.cos(cycle + t0 * 5.0) * 0.14 * t0;
            double z1 = shoulderZ - Math.pow(t1, 1.18) * backSwing + Math.cos(cycle + t1 * 5.0) * 0.14 * t1;

            BufferedImage outerStrip = textureStrip(outer, i, segments);
            BufferedImage innerStrip = textureStrip(inner, i, segments);
            BufferedImage leftStrip = textureStrip(leftEdge, i, segments);
            BufferedImage rightStrip = textureStrip(rightEdge, i, segments);

            // outer face (design side) faces away from the body — swap vertex order to flip normal
            addFace(faces, projector, outerStrip,
                    xRight + wave0 + rightDrift0, y0, z0,
                    xLeft + wave0 + leftDrift0, y0, z0,
                    xLeft + wave1 + leftDrift1, y1, z1,
                    xRight + wave1 + rightDrift1, y1, z1,
                    0.95);
            // inner face faces toward the player's back
            addFace(faces, projector, innerStrip,
                    xLeft + wave0 + leftDrift0, y0, z0 - thickness,
                    xRight + wave0 + rightDrift0, y0, z0 - thickness,
                    xRight + wave1 + rightDrift1, y1, z1 - thickness,
                    xLeft + wave1 + leftDrift1, y1, z1 - thickness,
                    0.60);
            addFace(faces, projector, leftStrip,
                    xLeft + wave0 + leftDrift0, y0, z0 - thickness,
                    xLeft + wave0 + leftDrift0, y0, z0,
                    xLeft + wave1 + leftDrift1, y1, z1,
                    xLeft + wave1 + leftDrift1, y1, z1 - thickness,
                    0.50);
            addFace(faces, projector, rightStrip,
                    xRight + wave0 + rightDrift0, y0, z0,
                    xRight + wave0 + rightDrift0, y0, z0 - thickness,
                    xRight + wave1 + rightDrift1, y1, z1 - thickness,
                    xRight + wave1 + rightDrift1, y1, z1,
                    0.54);
        }
    }

    private static BufferedImage textureStrip(BufferedImage image, int index, int segments) {
        int y0 = (int) Math.round(index * image.getHeight() / (double) segments);
        int y1 = (int) Math.round((index + 1) * image.getHeight() / (double) segments);
        return crop(image, 0, y0, image.getWidth(), Math.max(1, y1 - y0));
    }

    private static void paintShadow(Graphics2D g, int width, int height, double scale) {
        int shadowW = (int) (scale * 14);
        int shadowH = Math.max(8, (int) (scale * 2.4));
        int x = width / 2 - shadowW / 2;
        int y = (int) (height / 2.0 + scale * 17.2);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.32f));
        g.setColor(Color.BLACK);
        g.fillOval(x, y, shadowW, shadowH);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static BufferedImage crop(BufferedImage img, int x, int y, int w, int h) {
        if (img == null || img.getWidth() < x + w || img.getHeight() < y + h) {
            return transparentImage(Math.max(1, w), Math.max(1, h));
        }
        return img.getSubimage(x, y, w, h);
    }

    private static BufferedImage cropOrMirror(BufferedImage img, int x, int y, int w, int h,
                                              int fallbackX, int fallbackY, int fallbackW, int fallbackH) {
        if (img != null && img.getWidth() >= x + w && img.getHeight() >= y + h) {
            return crop(img, x, y, w, h);
        }
        return mirror(crop(img, fallbackX, fallbackY, fallbackW, fallbackH));
    }

    private static BufferedImage mirror(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(source, source.getWidth(), 0, -source.getWidth(), source.getHeight(), null);
        g.dispose();
        return out;
    }

    private static BufferedImage flipVertical(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(source, 0, source.getHeight(), source.getWidth(), -source.getHeight(), null);
        g.dispose();
        return out;
    }

    private static BufferedImage rotate180(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(source, source.getWidth(), source.getHeight(), -source.getWidth(), -source.getHeight(), null);
        g.dispose();
        return out;
    }

    private static boolean isTransparent(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xff) > 12) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BufferedImage transparentImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private record CuboidTextures(BufferedImage front, BufferedImage back, BufferedImage left, BufferedImage right,
                                  BufferedImage top, BufferedImage bottom) {
    }

    private record Face(BufferedImage texture, Point2D.Double p0, Point2D.Double p1, Point2D.Double p2,
                        Point2D.Double p3, double depth, double shade) {
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