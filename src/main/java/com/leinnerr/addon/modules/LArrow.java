package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// NO instakilea a los enemigos por ahora xd 
// por ahora puede funcionar para combinarlo con el ArrowKiller real

public class LArrow extends Module {
    public enum AimPoint { Head, Body, Feet }

    private static final double GRAVITY = 0.05;
    private static final double DRAG = 0.99;
    private static final int MAX_FLIGHT_TICKS = 150;
    private static final int ROTATION_PRIORITY = 70;
    private static final double CROSSBOW_SPEED = 3.15;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Objetivo");
    private final SettingGroup sgAim = settings.createGroup("Puntería");
    private final SettingGroup sgShoot = settings.createGroup("Disparo");
    private final SettingGroup sgRender = settings.createGroup("Renderizado");


    private final Setting<Boolean> autoShoot = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-shoot").description("Carga y dispara un arco o ballesta")
        .defaultValue(true).build());

    private final Setting<Boolean> aimbot = sgGeneral.add(new BoolSetting.Builder()
        .name("aimbot").description("Apunta en silencio al objetivo")
        .defaultValue(true).build());

    private final Setting<Boolean> autoSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-swap").description("Cambia al arco o a la ballesta de tu hotbar")
        .defaultValue(true).build());


    private final Setting<Set<EntityType<?>>> entities = sgTarget.add(new EntityTypeListSetting.Builder()
        .name("entities").description("Entidades a las que apuntar")
        .defaultValue(EntityType.PLAYER).onlyAttackable().build());

    private final Setting<Double> range = sgTarget.add(new DoubleSetting.Builder()
        .name("range").description("Distancia maxima a un objetivo valido")
        .defaultValue(40.0).min(1).sliderMax(100).build());

    private final Setting<SortPriority> priority = sgTarget.add(new EnumSetting.Builder<SortPriority>()
        .name("priority").description("Como ordenar los objetivos")
        .defaultValue(SortPriority.LowestDistance).build());

    private final Setting<Boolean> ignoreCreative = sgTarget.add(new BoolSetting.Builder()
        .name("ignore-creative").description("Ignora a jugadores en creativo")
        .defaultValue(true).build());

    private final Setting<Boolean> nametagged = sgTarget.add(new BoolSetting.Builder()
        .name("nametagged").description("Permite apuntar a mobs con nombre")
        .defaultValue(false).build());

    private final Setting<Boolean> trajectoryCheck = sgTarget.add(new BoolSetting.Builder()
        .name("trajectory-check").description("Solo elige objetivos a los que la flecha simulada llega sin chocar con bloques")
        .defaultValue(true).build());


    private final Setting<AimPoint> aimPoint = sgAim.add(new EnumSetting.Builder<AimPoint>()
        .name("aim-point").description("A qe parte del objetivo apuntas")
        .defaultValue(AimPoint.Body).build());

    private final Setting<Boolean> prediction = sgAim.add(new BoolSetting.Builder()
        .name("prediction").description("Adelanta el disparo segun el movimiento del objetivo")
        .defaultValue(true).build());

    private final Setting<Double> predictionScale = sgAim.add(new DoubleSetting.Builder()
        .name("prediction-scale").description("Multiplicador de la prediccion")
        .defaultValue(1.0).min(0).sliderMax(3).visible(prediction::get).build());

    private final Setting<Boolean> predictVertical = sgAim.add(new BoolSetting.Builder()
        .name("predict-vertical").description("Tambien predice el movimiento vertical")
        .defaultValue(false).visible(prediction::get).build());

    private final Setting<Boolean> compensateSelf = sgAim.add(new BoolSetting.Builder()
        .name("compensate-self-motion").description("La flecha hereda tu velocidad")
        .defaultValue(true).build());

    private final Setting<Integer> releaseCharge = sgShoot.add(new IntSetting.Builder()
        .name("release-charge").description("Ticks de carga del arco antes de soltar.")
        .defaultValue(20).range(4, 20).sliderRange(4, 20).build());

    private final Setting<Integer> shootDelay = sgShoot.add(new IntSetting.Builder()
        .name("shoot-delay").description("Ticks de espera entre un disparo y el siguient")
        .defaultValue(4).min(0).sliderMax(40).build());

    private final Setting<Boolean> requireHit = sgShoot.add(new BoolSetting.Builder()
        .name("require-hit").description("Solo dispara si la flecha simulada impacta al objetivo")
        .defaultValue(true).build());


    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Dibuja la trayectoria de la flecha")
        .defaultValue(true).build());

    private final Setting<SettingColor> pathColor = sgRender.add(new ColorSetting.Builder()
        .name("path-color").description("Color de la trayectoria")
        .defaultValue(new SettingColor(120, 60, 200, 200)).visible(render::get).build());

    private final Setting<SettingColor> hitColor = sgRender.add(new ColorSetting.Builder()
        .name("hit-color").description("Color del punto de impacto cuando la flecha llega al objetivo")
        .defaultValue(new SettingColor(60, 220, 120, 120)).visible(render::get).build());


    private record Aim(double yaw, double pitch, double ticks) {}
    private record PathResult(List<Vec3d> points, boolean hit) {}
    private record Solution(float yaw, float pitch, List<Vec3d> path, boolean hits) {}

    private Entity target;
    private Solution solution;
    private int cooldown;
    private boolean keyHeld;

    public LArrow() {
        super(LeinnerrAddon.CATEGORY, "l-arrow", "Aimbot de las flechas");
    }

    @Override
    public void onDeactivate() {
        if (keyHeld) mc.options.useKey.setPressed(false);
        keyHeld = false;
        target = null;
        solution = null;
        cooldown = 0;
    }

    @Override
    public String getInfoString() {
        return target != null ? EntityUtils.getName(target) : null;
    }


    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (cooldown > 0) cooldown--;
        target = null;
        solution = null;

        if (mc.player.isUsingItem() && !isRanged(mc.player.getActiveItem())) return;

        ItemStack main = mc.player.getMainHandStack();

        if (!isRanged(main)) {
            if (autoShoot.get() && autoSwap.get() && !mc.player.isUsingItem() && hasAmmo()
                && TargetUtils.get(this::isValidTarget, priority.get()) != null) {
                FindItemResult weapon = InvUtils.findInHotbar(Items.BOW, Items.CROSSBOW);
                if (weapon.found()) InvUtils.swap(weapon.slot(), false);
            }
            return;
        }

        boolean bow = main.isOf(Items.BOW);
        boolean using = mc.player.isUsingItem();
        boolean charged = !bow && CrossbowItem.isCharged(main);

        if (!mc.player.getAbilities().creativeMode && !charged && !hasAmmo()) {
            releaseKey();
            return;
        }

        double speed;
        if (bow) {
            speed = (using ? BowItem.getPullProgress(mc.player.getItemUseTime()) : 1.0f) * 3.0;
        } else {
            speed = CROSSBOW_SPEED;
        }

        if (bow && using && speed < 0.45) {
            if (autoShoot.get()) holdUse();
            return;
        }

        target = pickTarget(speed);
        if (target == null || solution == null) return;

        if (bow) tickBow(using);
        else tickCrossbow(main, using, charged);
    }

    private void tickBow(boolean using) {
        if (!using) {
            if (autoShoot.get() && cooldown == 0) holdUse();
            return;
        }

        if (autoShoot.get()) holdUse();
        if (!autoShoot.get() && !aimbot.get()) return;

        boolean ready = autoShoot.get()
            && mc.player.getItemUseTime() >= releaseCharge.get()
            && (!requireHit.get() || solution.hits());

        Rotations.rotate(solution.yaw(), solution.pitch(), ROTATION_PRIORITY, ready ? this::releaseBow : null);
    }

    private void tickCrossbow(ItemStack stack, boolean using, boolean charged) {
        if (charged) {
            if (!autoShoot.get() && !aimbot.get()) return;

            boolean ready = autoShoot.get() && cooldown == 0 && (!requireHit.get() || solution.hits());
            Rotations.rotate(solution.yaw(), solution.pitch(), ROTATION_PRIORITY, ready ? this::fireCrossbow : null);
            return;
        }

        if (!autoShoot.get()) return;

        if (!using) {
            if (cooldown == 0) holdUse();
            return;
        }

        holdUse();
        if (mc.player.getItemUseTime() >= CrossbowItem.getPullTime(stack, mc.player)) {
            mc.interactionManager.stopUsingItem(mc.player);
            releaseKey();
        }
    }

    private void releaseBow() {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.stopUsingItem(mc.player);
        releaseKey();
        cooldown = shootDelay.get();
    }

    private void fireCrossbow() {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        cooldown = shootDelay.get();
    }

    private void holdUse() {
        mc.options.useKey.setPressed(true);
        keyHeld = true;
    }

    private void releaseKey() {
        if (keyHeld) mc.options.useKey.setPressed(false);
        keyHeld = false;
    }

    private boolean isRanged(ItemStack stack) {
        return stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW);
    }

    private boolean hasAmmo() {
        return mc.player.getAbilities().creativeMode || InvUtils.find(s -> s.getItem() instanceof ArrowItem).found();
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == mc.player || entity == mc.cameraEntity) return false;
        if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isDead() || living.getHealth() <= 0) return false;
        if (!PlayerUtils.isWithin(entity, range.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;

        if (entity instanceof PlayerEntity player) {
            if (ignoreCreative.get() && (player.isCreative() || player.isSpectator())) return false;
            return Friends.get().shouldAttack(player);
        }

        return nametagged.get() || !entity.hasCustomName();
    }

    private Entity pickTarget(double speed) {
        List<Entity> candidates = new ArrayList<>();
        TargetUtils.getList(candidates, this::isValidTarget, priority.get(), 8);

        for (Entity candidate : candidates) {
            Solution s = solve(candidate, speed);
            if (s == null) continue;
            if (trajectoryCheck.get() && !s.hits()) continue;

            solution = s;
            return candidate;
        }
        return null;
    }


    private Solution solve(Entity entity, double speed) {
        Vec3d start = new Vec3d(mc.player.getX(), mc.player.getEyeY() - 0.1, mc.player.getZ());

        Vec3d self = Vec3d.ZERO;
        if (compensateSelf.get()) {
            Vec3d v = mc.player.getVelocity();
            self = new Vec3d(v.x, mc.player.isOnGround() ? 0 : v.y, v.z);
        }

        Vec3d motion = Vec3d.ZERO;
        if (prediction.get()) {
            motion = new Vec3d(
                entity.getX() - entity.lastX,
                predictVertical.get() ? entity.getY() - entity.lastY : 0,
                entity.getZ() - entity.lastZ
            ).multiply(predictionScale.get());
        }

        double yOffset = switch (aimPoint.get()) {
            case Head -> entity.getEyeHeight(entity.getPose());
            case Body -> entity.getHeight() * 0.5;
            case Feet -> entity.getHeight() * 0.2;
        };
        Vec3d base = entity.getPos().add(0, yOffset, 0);

        double flightTicks = 0;
        Aim aim = null;
        for (int i = 0; i < 4; i++) {
            Vec3d predicted = base.add(motion.multiply(flightTicks));
            aim = aimAt(start, predicted, speed, self);
            if (aim == null) return null;
            flightTicks = aim.ticks();
        }

        PathResult path = simulatePath(start, aim.yaw(), aim.pitch(), speed, self, entity, motion);
        return new Solution(
            (float) MathHelper.wrapDegrees(aim.yaw()),
            (float) MathHelper.clamp(aim.pitch(), -90, 90),
            path.points(), path.hit()
        );
    }

    private Aim aimAt(Vec3d start, Vec3d p, double speed, Vec3d self) {
        double dx = p.x - start.x;
        double dz = p.z - start.z;
        double dy = p.y - start.y;
        double d = Math.hypot(dx, dz);
        if (d < 0.5) return null;

        double ux = dx / d;
        double uz = dz / d;

        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(dy, d));
        double ticks = 0;
        double lastError = Double.MAX_VALUE;

        for (int iter = 0; iter < 14; iter++) {
            Vec3d vel = direction(yaw, pitch).multiply(speed).add(self);
            Vec3d pos = start;
            double prevS = 0;
            Vec3d cross = null;

            for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
                Vec3d next = pos.add(vel);
                double s = (next.x - start.x) * ux + (next.z - start.z) * uz;

                if (s >= d) {
                    double f = (s - prevS) > 1e-9 ? (d - prevS) / (s - prevS) : 0;
                    cross = pos.lerp(next, f);
                    ticks = tick - 1 + f;
                    break;
                }

                if (tick > 5 && s <= prevS + 1e-4) break;

                prevS = s;
                pos = next;
                vel = new Vec3d(vel.x * DRAG, vel.y * DRAG - GRAVITY, vel.z * DRAG);
                if (pos.y < mc.world.getBottomY() - 64) break;
            }

            if (cross == null) return null;

            double errY = p.y - cross.y;
            double offX = cross.x - p.x;
            double offZ = cross.z - p.z;
            double yawRad = Math.toRadians(yaw);
            double lateral = offX * Math.cos(yawRad) + offZ * Math.sin(yawRad);

            lastError = Math.hypot(errY, lateral);
            if (lastError < 0.05) break;

            pitch = MathHelper.clamp(pitch - Math.toDegrees(Math.atan2(errY, d)), -90, 90);
            yaw += Math.toDegrees(lateral / d);
        }

        return lastError <= 0.3 ? new Aim(yaw, pitch, ticks) : null;
    }

    private PathResult simulatePath(Vec3d start, double yaw, double pitch, double speed, Vec3d self,
                                    Entity entity, Vec3d motion) {
        List<Vec3d> points = new ArrayList<>();
        points.add(start);

        Vec3d vel = direction(yaw, pitch).multiply(speed).add(self);
        Vec3d pos = start;
        Box baseBox = entity.getBoundingBox().expand(0.3);
        boolean hit = false;

        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            Vec3d next = pos.add(vel);

            Box box = baseBox.offset(motion.multiply(tick));
            Optional<Vec3d> entityHit = box.contains(pos) ? Optional.of(pos) : box.raycast(pos, next);

            BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            boolean blocked = blockHit.getType() != HitResult.Type.MISS;

            if (entityHit.isPresent()
                && (!blocked || pos.squaredDistanceTo(entityHit.get()) <= pos.squaredDistanceTo(blockHit.getPos()))) {
                points.add(entityHit.get());
                hit = true;
                break;
            }

            if (blocked) {
                points.add(blockHit.getPos());
                break;
            }

            points.add(next);
            pos = next;
            vel = new Vec3d(vel.x * DRAG, vel.y * DRAG - GRAVITY, vel.z * DRAG);
            if (pos.y < mc.world.getBottomY() - 64) break;
        }

        return new PathResult(points, hit);
    }

    private static Vec3d direction(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new Vec3d(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }


    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || solution == null || target == null) return;

        List<Vec3d> pts = solution.path();
        for (int i = 1; i < pts.size(); i++) {
            Vec3d a = pts.get(i - 1);
            Vec3d b = pts.get(i);
            event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, pathColor.get());
        }

        if (pts.size() > 1) {
            Vec3d end = pts.getLast();
            Box impact = new Box(end.x - 0.15, end.y - 0.15, end.z - 0.15, end.x + 0.15, end.y + 0.15, end.z + 0.15);
            SettingColor color = solution.hits() ? hitColor.get() : pathColor.get();
            event.renderer.box(impact, color, color, ShapeMode.Both, 0);
        }
    }
}
