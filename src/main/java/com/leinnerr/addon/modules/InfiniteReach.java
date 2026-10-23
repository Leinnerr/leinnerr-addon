package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.compat.Compat;
import com.leinnerr.addon.utils.LUtils;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.NoFall;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class InfiniteReach extends Module {
    public enum Mode { Vanilla, Paper }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Renderizado");
    private final SettingGroup sgPacket = settings.createGroup("Ajustes de paquetes");

    private final Setting<Boolean> noFallSetting = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-nofall-while-teleporting").description("Apaga NoFall mientras te teletransportas.")
        .defaultValue(true).build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-arm").description("Mueve el brazo en cada accion.")
        .defaultValue(true).build());

    private final Setting<Boolean> phoneHome = sgGeneral.add(new BoolSetting.Builder()
        .name("home-teleport").description("Te devuelve a tu posicion inicial despues de la accion")
        .defaultValue(true).build());

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("compatibility-mode").description("Vanilla = ~22 bloques de alcance, Paper = hasta ~99 bloques")
        .defaultValue(Mode.Vanilla).build());

    private final Setting<Boolean> goUp = sgGeneral.add(new BoolSetting.Builder()
        .name("clip-up").description("Sube primero para esquivar obstculo")
        .defaultValue(true).visible(() -> mode.get() == Mode.Paper).build());

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("spam-packets-vanilla").description("Paquetes enviados antes del movimiento")
        .defaultValue(4).min(1).sliderRange(1, 5).visible(() -> mode.get() == Mode.Vanilla).build());

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance-vanilla").description("Alcance maximo")
        .defaultValue(22.0).min(1).sliderRange(1, 22).visible(() -> mode.get() == Mode.Vanilla).build());

    private final Setting<Integer> paperPackets = sgGeneral.add(new IntSetting.Builder()
        .name("spam-packets-paper").description("Paquetes enviados antes del movimiento real")
        .defaultValue(7).min(1).sliderRange(1, 10).visible(() -> mode.get() == Mode.Paper).build());

    private final Setting<Double> paperDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance-paper").description("Alcance maximo")
        .defaultValue(59.0).min(1).sliderRange(1, 99).visible(() -> mode.get() == Mode.Paper).build());

    private final Setting<Double> offsetHorizontal = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-offset").description("Cuanto te desplaza horizontalmente al volver")
        .defaultValue(0.05).min(0.01).sliderMax(0.99).build());

    private final Setting<Double> offsetY = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-offset").description("Cuanto te desplaza verticalmente al volver")
        .defaultValue(0.01).min(0.01).sliderMax(0.99).build());

    private final Setting<Boolean> onlyMace = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-mace").description("Solo ataca a distancia con una maza en la mano y si el objetivo no se esta cubriendo")
        .defaultValue(false).build());

    private final Setting<Boolean> renderEntity = sgRender.add(new BoolSetting.Builder()
        .name("render-entity-box").description("Dibuja una caja alrededor de la entidad objetivo")
        .defaultValue(true).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("entity-side-color").description("Color")
        .defaultValue(new SettingColor(255, 0, 0, 40)).visible(renderEntity::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("entity-line-color").description("Color de las lineas")
        .defaultValue(new SettingColor(255, 0, 0, 120)).visible(renderEntity::get).build());

    private final Setting<Boolean> renderBlock = sgRender.add(new BoolSetting.Builder()
        .name("render-block-box").description("Dibuja una caja alrededor del objetivo")
        .defaultValue(true).build());

    private final Setting<SettingColor> bSideColor = sgRender.add(new ColorSetting.Builder()
        .name("block-side-color").description("Color de las caras de la caja del bloque")
        .defaultValue(new SettingColor(255, 0, 255, 40)).visible(renderBlock::get).build());

    private final Setting<SettingColor> bLineColor = sgRender.add(new ColorSetting.Builder()
        .name("block-line-color").description("Color de las lineas de la caja del bloque")
        .defaultValue(new SettingColor(255, 0, 255, 120)).visible(renderBlock::get).build());

    private final Setting<Boolean> miningPacket = sgPacket.add(new BoolSetting.Builder()
        .name("send-mining-packet").description("Permite romper bloques muy lejanos")
        .defaultValue(true).build());

    private final Setting<Integer> blockAttackDelay = sgPacket.add(new IntSetting.Builder()
        .name("mining-packet-delay").description("Ticks entre paquetes de minado.")
        .defaultValue(5).min(1).sliderMax(20).visible(miningPacket::get).build());

    private final Setting<Boolean> itemUsePacket = sgPacket.add(new BoolSetting.Builder()
        .name("send-item-use-packet").description("Permite usar objetos en bloques y entidades muy lejanos")
        .defaultValue(true).build());

    private final Setting<Integer> itemUseDelay = sgPacket.add(new IntSetting.Builder()
        .name("item-use-packet-delay").description("Ticks entre usos de objetos")
        .defaultValue(5).min(1).sliderMax(20).visible(itemUsePacket::get).build());

    private final Setting<Boolean> attackPacket = sgPacket.add(new BoolSetting.Builder()
        .name("send-attack-packet").description("Permite atacar entidades muy lejanas")
        .defaultValue(true).build());

    private final Setting<Integer> entityAttackDelay = sgPacket.add(new IntSetting.Builder()
        .name("attack-packet-delay").description("Ticks entre ataques a entidades")
        .defaultValue(5).min(1).sliderMax(20).visible(attackPacket::get).build());

    private double maxDistance;
    private boolean wasNoFallEnabled;
    private boolean noFallToggled;

    private Entity hoveredTarget;
    private BlockHitResult blockHit;

    private int blockAttackTicks;
    private boolean canBlockAttack = true;
    private int itemUseTicks;
    private boolean canItemUse = true;
    private int entityAttackTicks;
    private boolean canEntityAttack = true;

    private Vec3d startPos, finalPos, aboveSelf, aboveTarget;
    private Vec3d blockFinalPos, blockAboveSelf, blockAboveTarget;

    public InfiniteReach() {
        super(LeinnerrAddon.CATEGORY, "infinite-reach", "Permite aumentar la distancia de pegar y usar objetos");
    }

    @Override
    public void onActivate() {
        maxDistance = currentMaxDistance();
        wasNoFallEnabled = false;
        noFallToggled = false;
        hoveredTarget = null;
        blockHit = null;
        blockAttackTicks = itemUseTicks = entityAttackTicks = 0;
        canBlockAttack = canItemUse = canEntityAttack = true;
        clearPositions();
    }

    @Override
    public void onDeactivate() {
        restoreNoFall();
    }

    private double currentMaxDistance() {
        return mode.get() == Mode.Vanilla ? distance.get() : paperDistance.get();
    }

    private void clearPositions() {
        startPos = finalPos = aboveSelf = aboveTarget = null;
        blockFinalPos = blockAboveSelf = blockAboveTarget = null;
    }

    private Entity movingEntity() {
        return mc.player.hasVehicle() ? mc.player.getVehicle() : mc.player;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        tickCooldowns();
        maxDistance = currentMaxDistance();

        Vec3d cameraPos = mc.player.getCameraPosVec(1.0f);
        Vec3d look = mc.player.getRotationVec(1.0f);
        Vec3d endVec = cameraPos.add(look.multiply(maxDistance));

        EntityHitResult entityHit = ProjectileUtil.raycast(
            mc.player, cameraPos, endVec,
            mc.player.getBoundingBox().expand(maxDistance),
            e -> e.isAlive() && e.isAttackable() && !e.isInvulnerable() && e != mc.player && e != mc.player.getVehicle(),
            maxDistance * maxDistance);

        blockHit = null;
        if (entityHit == null && mc.getCameraEntity() != null) {
            HitResult raw = mc.getCameraEntity().raycast(maxDistance, 0.0f, false);
            if (raw instanceof BlockHitResult bhr && !mc.world.getBlockState(bhr.getBlockPos()).isAir()) {
                blockHit = bhr;
            }
        }

        hoveredTarget = entityHit != null ? entityHit.getEntity() : null;

        if (hoveredTarget == null && blockHit == null) {
            clearPositions();
            return;
        }

        startPos = LUtils.pos(movingEntity());

        if (blockHit != null) {
            if (!prepareBlock()) return;
        } else {
            blockFinalPos = blockAboveSelf = blockAboveTarget = null;
        }

        if (hoveredTarget != null) {
            if (!prepareEntity()) return;
        } else {
            finalPos = aboveSelf = aboveTarget = null;
        }

        handleInput(entityHit);
    }

    private boolean prepareBlock() {
        BlockPos hitPos = blockHit.getBlockPos();
        Vec3d blockCenter = Vec3d.ofCenter(hitPos);
        Box blockBox = new Box(hitPos);
        BlockPos actionPos = hitPos.offset(blockHit.getSide());

        if (mc.player.getBoundingBox().intersects(new Box(actionPos))) return false;

        Vec3d diff = startPos.subtract(blockCenter);
        double flatUp = Math.sqrt(Math.max(0, maxDistance * maxDistance - (diff.x * diff.x + diff.z * diff.z)));
        double targetUp = flatUp + diff.y;

        double y = mc.player.hasVehicle() ? blockBox.maxY + 0.3 : blockCenter.y;
        Vec3d inside = new Vec3d(blockCenter.x, y, blockCenter.z);

        blockFinalPos = findNearestPosBlock(inside, actionPos, blockHit);
        if (blockFinalPos == null) return false;

        blockAboveSelf = startPos.add(0, maxDistance, 0);
        blockAboveTarget = blockFinalPos.add(0, targetUp, 0);
        return true;
    }

    private boolean prepareEntity() {
        Vec3d targetPos = LUtils.pos(hoveredTarget);
        Vec3d diff = startPos.subtract(targetPos);
        double flatUp = Math.sqrt(Math.max(0, maxDistance * maxDistance - (diff.x * diff.x + diff.z * diff.z)));
        double targetUp = flatUp + diff.y;

        double y = mc.player.hasVehicle() ? hoveredTarget.getBoundingBox().maxY + 0.3 : targetPos.y;
        Vec3d inside = new Vec3d(targetPos.x, y, targetPos.z);

        finalPos = !invalid(inside) ? inside : findNearestPos(inside);
        if (finalPos == null) return false;

        aboveSelf = startPos.add(0, maxDistance, 0);
        aboveTarget = finalPos.add(0, targetUp, 0);
        return true;
    }

    private void handleInput(EntityHitResult entityHit) {
        boolean attackPressed = mc.options.attackKey.isPressed();
        boolean usePressed = mc.options.useKey.isPressed();
        boolean usingItem = mc.player.isUsingItem();

        if (!attackPressed && !usePressed) {
            if (noFallSetting.get() && noFallToggled) restoreNoFall();
            return;
        }

        BlockHitResult crosshairBlock = mc.crosshairTarget instanceof BlockHitResult b ? b : null;

        if (entityHit != null && attackPressed && canEntityAttack && attackPacket.get()) {
            canEntityAttack = false;
            entityAttackTicks = 0;
            if (noFallSetting.get()) disableNoFall();
            hitEntity(hoveredTarget, true);
        } else if (entityHit != null && usePressed && canItemUse && itemUsePacket.get()) {
            canItemUse = false;
            itemUseTicks = 0;
            if (noFallSetting.get()) disableNoFall();
            hitEntity(hoveredTarget, false);
        } else if (entityHit == null && attackPressed && canBlockAttack && miningPacket.get()) {
            canBlockAttack = false;
            blockAttackTicks = 0;
            if (noFallSetting.get()) disableNoFall();
            hitBlock(blockHit, true);
        } else if (entityHit == null && usePressed && canItemUse && !usingItem && itemUsePacket.get()) {
            canItemUse = false;
            itemUseTicks = 0;

            if (crosshairBlock != null && mc.world.getBlockState(crosshairBlock.getBlockPos()).isAir()) {
                if (noFallSetting.get()) disableNoFall();
                hitBlock(blockHit, false);
            }
        }
    }

    private void tickCooldowns() {
        if (!canBlockAttack && ++blockAttackTicks >= blockAttackDelay.get()) {
            canBlockAttack = true;
            blockAttackTicks = 0;
        }
        if (!canItemUse && ++itemUseTicks >= itemUseDelay.get()) {
            canItemUse = true;
            itemUseTicks = 0;
        }
        if (!canEntityAttack && ++entityAttackTicks >= entityAttackDelay.get()) {
            canEntityAttack = true;
            entityAttackTicks = 0;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        if (hoveredTarget != null && renderEntity.get()) {
            event.renderer.box(hoveredTarget.getBoundingBox(), sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
        }

        if (hoveredTarget == null && renderBlock.get() && blockHit != null) {
            event.renderer.box(blockHit.getBlockPos(), bSideColor.get(), bLineColor.get(), ShapeMode.Both, 0);
        }
    }

    private void disableNoFall() {
        if (noFallToggled) return;

        NoFall noFall = Modules.get().get(NoFall.class);
        wasNoFallEnabled = noFall.isActive();
        if (wasNoFallEnabled) {
            noFall.toggle();
            noFallToggled = true;
        }
    }

    private void restoreNoFall() {
        if (noFallToggled && wasNoFallEnabled) {
            NoFall noFall = Modules.get().get(NoFall.class);
            if (!noFall.isActive()) noFall.toggle();
        }
        noFallToggled = false;
    }

    private boolean pathBad(Vec3d above, Vec3d aboveTgt) {
        return mode.get() == Mode.Paper && goUp.get()
            && (!hasClearPath(above, aboveTgt) || invalid(above) || invalid(aboveTgt));
    }

    private void sendSpamPackets() {
        int amount = mode.get() == Mode.Vanilla ? packets.get() : paperPackets.get();
        for (int i = 0; i < amount; i++) {
            if (mc.player.hasVehicle()) {
                Compat.sendVehicleStay(mc.player.getVehicle());
            } else {
                LUtils.send(Compat.moveOnGround(false, mc.player.horizontalCollision));
            }
        }
    }

    private void hitBlock(BlockHitResult bhr, boolean attackPressed) {
        if (bhr == null || mc.player == null || mc.world == null) return;
        if (!LUtils.chunkLoaded(bhr.getBlockPos())) return;
        if (startPos == null || blockFinalPos == null || blockAboveSelf == null || blockAboveTarget == null) return;

        Entity entity = movingEntity();

        if (invalid(blockFinalPos) || pathBad(blockAboveSelf, blockAboveTarget)) {
            if (chatFeedback) {
                if (!hasClearPath(blockAboveSelf, blockAboveTarget)) error("Camino bloqueado entre las posiciones de clip");
                else error("Al menos uno de los teletransportes no es valido");
            }
            return;
        }

        sendSpamPackets();

        boolean paperUp = mode.get() == Mode.Paper && goUp.get();
        if (paperUp) {
            sendMove(entity, blockAboveSelf);
            sendMove(entity, blockAboveTarget);
        }

        sendMove(entity, blockFinalPos);
        if (!phoneHome.get()) entity.setPosition(blockFinalPos.x, blockFinalPos.y, blockFinalPos.z);

        if (attackPressed) {
            LUtils.send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, bhr.getBlockPos(), bhr.getSide()));
            LUtils.send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, bhr.getBlockPos(), bhr.getSide()));
        } else {
            LUtils.send(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, bhr, 0));
        }

        doSwing();

        if (phoneHome.get()) {
            if (paperUp) {
                sendMove(entity, blockAboveTarget.add(0, 0.01, 0));
                sendMove(entity, blockAboveSelf.add(0, 0.01, 0));
            }
            goHome(entity);
        }
    }

    private void hitEntity(Entity target, boolean attackPressed) {
        if (mc.player == null || mc.getNetworkHandler() == null || target == null) return;

        if (onlyMace.get()) {
            if (!mc.player.getMainHandStack().isOf(Items.MACE)) return;
            if (target instanceof PlayerEntity p && p.isBlocking()) return;
        }

        if (startPos == null || finalPos == null || aboveSelf == null || aboveTarget == null) return;

        Entity entity = movingEntity();

        double actualDistance = startPos.distanceTo(LUtils.pos(target));
        if (actualDistance > maxDistance - 0.5) return;

        if (invalid(finalPos) || pathBad(aboveSelf, aboveTarget)) {
            if (chatFeedback) {
                if (!hasClearPath(aboveSelf, aboveTarget)) error("Camino vertical bloqueado entre las posiciones de clip");
                else error("Al menos uno de los teletransportes no es valido");
            }
            return;
        }

        sendSpamPackets();

        boolean paperUp = mode.get() == Mode.Paper && goUp.get();
        if (paperUp) {
            sendMove(entity, aboveSelf);
            sendMove(entity, aboveTarget);
        }

        sendMove(entity, finalPos);
        if (!phoneHome.get()) entity.setPosition(finalPos.x, finalPos.y, finalPos.z);

        if (attackPressed) {
            LUtils.send(PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
        } else {
            LUtils.send(PlayerInteractEntityC2SPacket.interactAt(target, mc.player.isSneaking(), mc.player.getActiveHand(), Vec3d.ZERO));
        }

        doSwing();

        if (phoneHome.get()) {
            if (paperUp) {
                sendMove(entity, aboveTarget.add(0, 0.01, 0));
                sendMove(entity, aboveSelf.add(0, 0.01, 0));
            }
            goHome(entity);
        }
    }

    private void doSwing() {
        if (swing.get()) {
            LUtils.send(new HandSwingC2SPacket(Hand.MAIN_HAND));
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void goHome(Entity entity) {
        sendMove(entity, startPos);
        Vec3d offset = getOffset(startPos);
        sendMove(entity, offset);
        entity.setPosition(offset.x, offset.y, offset.z);
    }

    private Vec3d findNearestPos(Vec3d desired) {
        if (!invalid(desired)) return desired;

        Vec3d best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    Vec3d test = desired.add(dx, dy, dz);
                    if (invalid(test)) continue;

                    double dist = test.squaredDistanceTo(desired);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = test;
                    }
                }
            }
        }
        return best;
    }

    private Vec3d findNearestPosBlock(Vec3d preferred, BlockPos actionBlockPos, BlockHitResult hit) {
        if (!invalid(preferred) && !BlockPos.ofFloored(preferred).equals(actionBlockPos)) return preferred;

        Vec3d best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    Vec3d test = preferred.add(dx * 0.4, dy * 0.4, dz * 0.4);
                    if (BlockPos.ofFloored(test).equals(actionBlockPos) || invalid(test)) continue;

                    double dist = test.squaredDistanceTo(preferred);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = test;
                    }
                }
            }
        }

        if (best == null) {
            for (Direction dir : Direction.values()) {
                if (dir == hit.getSide()) continue;

                Vec3d fallback = preferred.add(dir.getOffsetX() * 0.5, dir.getOffsetY() * 0.5, dir.getOffsetZ() * 0.5);
                if (!invalid(fallback) && !BlockPos.ofFloored(fallback).equals(actionBlockPos)) return fallback;
            }
        }

        return best;
    }

    private void sendMove(Entity entity, Vec3d pos) {
        if (mc.getNetworkHandler() == null) return;

        if (entity == mc.player) {
            LUtils.send(Compat.movePos(pos.x, pos.y, pos.z, false));
        } else {
            Compat.sendVehicleMove(entity, pos);
        }
    }

    private Vec3d getOffset(Vec3d base) {
        double dx = offsetHorizontal.get();
        double dy = offsetY.get();

        List<Vec3d> offsets = new ArrayList<>(List.of(
            base.add(dx, dy, 0), base.add(-dx, dy, 0), base.add(0, dy, dx), base.add(0, dy, -dx),
            base.add(dx, dy, dx), base.add(-dx, dy, -dx), base.add(-dx, dy, dx), base.add(dx, dy, -dx)
        ));
        Collections.shuffle(offsets);

        for (Vec3d pos : offsets) {
            if (!invalid(pos)) return pos;
        }

        Vec3d noHorizontal = base.add(0, dy, 0);
        return !invalid(noHorizontal) ? noHorizontal : base;
    }

    private boolean invalid(Vec3d pos) {
        return LUtils.invalid(pos, movingEntity());
    }

    private boolean hasClearPath(Vec3d start, Vec3d end) {
        return LUtils.hasClearPath(start, end, movingEntity(), 10);
    }
}
