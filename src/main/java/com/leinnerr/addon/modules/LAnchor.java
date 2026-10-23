package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.compat.Compat;
import com.leinnerr.addon.utils.BlockPlacer;
import com.leinnerr.addon.utils.LUtils;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class LAnchor extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Objetivo");
    private final SettingGroup sgPlace = settings.createGroup("Colocación");
    private final SettingGroup sgDamage = settings.createGroup("Daño");
    private final SettingGroup sgTP = settings.createGroup("Teletransporte");
    private final SettingGroup sgRender = settings.createGroup("Renderizado");

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use").description("Se pausa mientras comes")
        .defaultValue(true).build());

    private final Setting<Boolean> pauseOnMine = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-mine").description("Se pausa mientras minas un bloque")
        .defaultValue(false).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rota en silencio hacia el bloque antes de colocar o detonar")
        .defaultValue(false).build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing").description("Mueve la mano en cada accion")
        .defaultValue(true).build());

    private final Setting<Boolean> strictDirection = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-direction").description("Solo hace click en las caras de bloque que miras")
        .defaultValue(false).build());

    private final Setting<Double> targetRange = sgTarget.add(new DoubleSetting.Builder()
        .name("target-range").description("Distancia maxima a un objetivo valido")
        .defaultValue(10.0).min(1).sliderMax(20).build());

    private final Setting<SortPriority> priority = sgTarget.add(new EnumSetting.Builder<SortPriority>()
        .name("priority").description("Como ordenar los objetivos")
        .defaultValue(SortPriority.LowestHealth).build());

    private final Setting<Boolean> ignoreFriends = sgTarget.add(new BoolSetting.Builder()
        .name("ignore-friends").description("No ataca tus enemigos")
        .defaultValue(true).build());

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range").description("Distancia maxima")
        .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Double> breakRange = sgPlace.add(new DoubleSetting.Builder()
        .name("break-range").description("Distancia maxima")
        .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Integer> searchRadius = sgPlace.add(new IntSetting.Builder()
        .name("search-radius").description("Radio alrededor del objetivo")
        .defaultValue(3).min(1).sliderMax(6).build());

    private final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay").description("Ticks entre cada colocacion de ancla")
        .defaultValue(2).min(0).sliderMax(20).build());

    private final Setting<Integer> breakDelay = sgPlace.add(new IntSetting.Builder()
        .name("break-delay").description("Ticks entre cada detonacion")
        .defaultValue(1).min(0).sliderMax(20).build());

    private final Setting<Boolean> fastBreak = sgPlace.add(new BoolSetting.Builder()
        .name("fast-break").description("Carga y detona el anchor")
        .defaultValue(false).build());

    private final Setting<Double> minDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("min-damage").description("Daño minimo al objetivo necesario para actuar")
        .defaultValue(8.0).min(0).sliderMax(36).build());

    private final Setting<Boolean> lethalOverride = sgDamage.add(new BoolSetting.Builder()
        .name("lethal-override").description("Ignora el daño minimo si la explosion puede matar al enemigo")
        .defaultValue(true).build());

    private final Setting<Double> maxSelfDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("max-self-damage").description("Daño propio maximo permitido")
        .defaultValue(8.0).min(0).sliderMax(36).build());

    private final Setting<Double> selfWeight = sgDamage.add(new DoubleSetting.Builder()
        .name("self-damage-weight").description("Cuanto pesa el daño propio al elegir la mejor posicion")
        .defaultValue(1.0).min(0).sliderMax(3).build());

    private final Setting<Boolean> antiSuicide = sgDamage.add(new BoolSetting.Builder()
        .name("anti-suicide").description("No actua si la explosion te mataria")
        .defaultValue(true).build());

    private final Setting<Double> minHealth = sgDamage.add(new DoubleSetting.Builder()
        .name("min-health").description("Se pausa segun tu vida")
        .defaultValue(4.0).min(0).sliderMax(36).build());

    private final Setting<Boolean> teleport = sgTP.add(new BoolSetting.Builder()
        .name("teleport").description("Por si no hay posicion al alcance")
        .defaultValue(false).build());

    private final Setting<Double> tpRange = sgTP.add(new DoubleSetting.Builder()
        .name("tp-range").description("Distancia maxima al objetivo cuando el teletransporte esta activo")
        .defaultValue(100.0).min(5).sliderMax(200).visible(teleport::get).build());

    private final Setting<Integer> tpDelay = sgTP.add(new IntSetting.Builder()
        .name("tp-delay").description("Ticks entre cada ataque con teletransporte")
        .defaultValue(5).min(0).sliderMax(40).visible(teleport::get).build());

    private final Setting<Boolean> tpVclip = sgTP.add(new BoolSetting.Builder()
        .name("vclip").description("Sube en vertical para saltarte las colisiones del terreno")
        .defaultValue(true).visible(teleport::get).build());

    private final Setting<Integer> tpPackets = sgTP.add(new IntSetting.Builder()
        .name("spoof-packets").description("Paquetes de posicion enviados antes de moverte")
        .defaultValue(4).min(0).sliderMax(20).visible(teleport::get).build());

    private final Setting<Integer> freezeTicks = sgTP.add(new IntSetting.Builder()
        .name("freeze-ticks").description("Ticks de congelacion de paquetes tras volver")
        .defaultValue(1).min(0).sliderMax(20).visible(teleport::get).build());

    private final Setting<Boolean> tpShield = sgTP.add(new BoolSetting.Builder()
        .name("shield").description("Pone un bloque entre tu y el ancol")
        .defaultValue(true).visible(teleport::get).build());

    private final Setting<List<Block>> shieldBlocks = sgTP.add(new BlockListSetting.Builder()
        .name("shield-blocks").description("Bloques pa escdo")
        .defaultValue(Blocks.OBSIDIAN).visible(() -> teleport.get() && tpShield.get()).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Renderiza el anchor")
        .defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Como se dibuja")
        .defaultValue(ShapeMode.Both).visible(render::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Color")
        .defaultValue(new SettingColor(120, 60, 200, 40)).visible(render::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Color de las lineas")
        .defaultValue(new SettingColor(120, 60, 200, 150)).visible(render::get).build());

    private final Setting<Integer> renderTime = sgRender.add(new IntSetting.Builder()
        .name("render-time").description("Cuanto tiempo se queda el render")
        .defaultValue(300).min(0).sliderMax(1000).visible(render::get).build());

    private record Candidate(BlockPos pos, double score, int charges) {}

    private PlayerEntity target;
    private int placeTimer;
    private int breakTimer;
    private boolean warnedOffhand;
    private BlockPos renderPos;
    private long renderStamp;
    private int tpTimer;
    private boolean freezeActive;
    private int freezeTimer;
    private Vec3d frozen = Vec3d.ZERO;

    public LAnchor() {
        super(LeinnerrAddon.CATEGORY, "l-anchor", "AnchorAura y TPanchor");
    }

    @Override
    public void onActivate() {
        target = null;
        placeTimer = 0;
        breakTimer = 0;
        warnedOffhand = false;
        renderPos = null;
        tpTimer = 0;
        freezeActive = false;
    }

    @Override
    public void onDeactivate() {
        target = null;
        renderPos = null;
        freezeActive = false;
    }

    @Override
    public String getInfoString() {
        return target != null ? target.getName().getString() : null;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!freezeActive) return;
        if (event.packet instanceof PlayerMoveC2SPacket packet) {
            if (packet.changesPosition() && !packet.changesLook()) return;
            event.cancel();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (freezeActive) {
            mc.player.setPos(frozen.x, frozen.y, frozen.z);
            mc.player.setVelocity(0, 0, 0);
            if (--freezeTimer <= 0) freezeActive = false;
        }
        if (tpTimer > 0) tpTimer--;

        if (mc.world.getDimension().respawnAnchorWorks()) {
            error("JAJJAJA Pendejo en el nether no explotan");
            toggle();
            return;
        }

        if (placeTimer > 0) placeTimer--;
        if (breakTimer > 0) breakTimer--;

        if (pauseOnUse.get() && mc.player.isUsingItem()) return;
        if (pauseOnMine.get() && mc.interactionManager.isBreakingBlock()) return;

        double selfHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (selfHealth <= minHealth.get()) return;

        if (mc.player.getOffHandStack().isOf(Items.GLOWSTONE)) {
            if (!warnedOffhand) {
                warning("Quita la piedra luminosa de la mano izquierda");
                warnedOffhand = true;
            }
            return;
        }
        warnedOffhand = false;

        target = findTarget();
        if (target == null) return;

        FindItemResult anchorItem = InvUtils.findInHotbar(Items.RESPAWN_ANCHOR);
        FindItemResult glowstone = InvUtils.findInHotbar(Items.GLOWSTONE);
        if (!glowstone.found()) return;

        boolean canBreak = breakTimer == 0;
        boolean canPlace = placeTimer == 0 && anchorItem.found();
        if (!canBreak && !canPlace) return;

        double targetHealth = target.getHealth() + target.getAbsorptionAmount();
        BlockPos center = target.getBlockPos();
        int r = searchRadius.get();

        Candidate bestBreak = null;
        Candidate bestPlace = null;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > r * r) continue;

                    BlockPos pos = center.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);

                    if (state.isOf(Blocks.RESPAWN_ANCHOR)) {
                        if (!canBreak) continue;
                        double score = evaluate(pos, breakRange.get(), targetHealth, selfHealth);
                        if (Double.isNaN(score)) continue;
                        if (bestBreak == null || score > bestBreak.score()) {
                            bestBreak = new Candidate(pos, score, state.get(RespawnAnchorBlock.CHARGES));
                        }
                    } else if (canPlace) {
                        if (pos.getY() < mc.world.getBottomY() || pos.getY() >= mc.world.getBottomY() + mc.world.getHeight()) continue;
                        if (!BlockPlacer.canPlace(pos, Blocks.RESPAWN_ANCHOR)) continue;
                        if (BlockPlacer.find(pos, strictDirection.get()) == null) continue;

                        double score = evaluate(pos, placeRange.get(), targetHealth, selfHealth);
                        if (Double.isNaN(score)) continue;
                        if (bestPlace == null || score > bestPlace.score()) {
                            bestPlace = new Candidate(pos, score, 0);
                        }
                    }
                }
            }
        }

        if (bestBreak == null && bestPlace == null) {
            if (teleport.get() && tpTimer == 0 && anchorItem.found()) teleportAttack(anchorItem, glowstone, targetHealth, selfHealth);
            return;
        }

        boolean rotated = false;
        int detonator = findDetonatorSlot();

        if (bestBreak != null && detonator != -1) {
            rotated = explode(bestBreak.pos(), bestBreak.charges(), glowstone, detonator);
            breakTimer = breakDelay.get();
        } else if (bestPlace != null) {
            InvUtils.swap(anchorItem.slot(), true);
            boolean placed = BlockPlacer.place(bestPlace.pos(), strictDirection.get(), rotate.get());
            InvUtils.swapBack();

            if (placed) {
                rotated = rotate.get();
                placeTimer = placeDelay.get();
                mark(bestPlace.pos());

                if (fastBreak.get() && detonator != -1) {
                    rotated |= explode(bestPlace.pos(), 0, glowstone, detonator);
                    breakTimer = breakDelay.get();
                }
            }
        }

        if (rotated) BlockPlacer.resync();
    }

    private double evaluate(BlockPos pos, double range, double targetHealth, double selfHealth) {
        Vec3d center = Vec3d.ofCenter(pos);
        if (mc.player.getEyePos().squaredDistanceTo(center) > range * range) return Double.NaN;

        double targetDamage = DamageUtils.anchorDamage(target, center);
        boolean lethal = targetDamage >= targetHealth;
        if (targetDamage < minDamage.get() && !(lethalOverride.get() && lethal)) return Double.NaN;

        double selfDamage = DamageUtils.anchorDamage(mc.player, center);
        if (selfDamage > maxSelfDamage.get()) return Double.NaN;
        if (antiSuicide.get() && selfHealth - selfDamage <= 0.5) return Double.NaN;

        double score = targetDamage - selfDamage * selfWeight.get();
        if (lethal) score += 1000.0;
        return score;
    }

    private boolean explode(BlockPos pos, int charges, FindItemResult glowstone, int detonatorSlot) {
        if (mc.player.isSneaking()) mc.player.setSneaking(false);

        boolean rotated = false;

        if (charges <= 0) {
            InvUtils.swap(glowstone.slot(), true);
            rotated |= click(pos);
        }

        InvUtils.swap(detonatorSlot, true);
        rotated |= click(pos);

        InvUtils.swapBack();
        mark(pos);
        return rotated;
    }

    private boolean click(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        Direction side = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        Vec3d hit = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);

        if (rotate.get()) BlockPlacer.rotateTo(hit);

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        return rotate.get();
    }


    private void teleportAttack(FindItemResult anchorItem, FindItemResult glowstone, double targetHealth, double selfHealth) {
        if (mc.player.hasVehicle()) return;

        BlockPos center = target.getBlockPos();
        int r = searchRadius.get();
        BlockPos bestPos = null;
        boolean bestExists = false;
        int bestCharges = 0;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > r * r) continue;

                    BlockPos pos = center.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    boolean exists = state.isOf(Blocks.RESPAWN_ANCHOR);

                    if (!exists) {
                        if (pos.getY() < mc.world.getBottomY() || pos.getY() >= mc.world.getBottomY() + mc.world.getHeight()) continue;
                        if (!BlockPlacer.canPlace(pos, Blocks.RESPAWN_ANCHOR)) continue;
                    }

                    double score = evaluate(pos, 1.0e9, targetHealth, selfHealth);
                    if (Double.isNaN(score)) continue;
                    if (score > bestScore) {
                        bestScore = score;
                        bestPos = pos;
                        bestExists = exists;
                        bestCharges = exists ? state.get(RespawnAnchorBlock.CHARGES) : 0;
                    }
                }
            }
        }
        if (bestPos == null) return;

        Vec3d stand = findStandSpot(bestPos);
        if (stand == null) return;

        Vec3d start = LUtils.pos(mc.player);

        Vec3d up1 = null, up2 = null;
        if (!LUtils.hasClearPath(start, stand, mc.player, 1)) {
            if (!tpVclip.get()) return;
            double base = Math.max(start.y, stand.y) + 1.0;
            for (double y = base; y < base + 50.0; y++) {
                Vec3d a = new Vec3d(start.x, y, start.z);
                Vec3d b = new Vec3d(stand.x, y, stand.z);
                if (!LUtils.invalid(a, mc.player) && !LUtils.invalid(b, mc.player) && LUtils.hasClearPath(a, b, mc.player, 1)) {
                    up1 = a;
                    up2 = b;
                    break;
                }
            }
            if (up1 == null) return;
        }

        int shieldSlot = -1;
        BlockPos shieldPos = null;
        if (tpShield.get()) {
            FindItemResult shield = InvUtils.findInHotbar(stack ->
                stack.getItem() instanceof BlockItem bi && shieldBlocks.get().contains(bi.getBlock()));
            if (!shield.found()) return;
            shieldPos = shieldPosFor(bestPos, BlockPos.ofFloored(stand));
            if (mc.world.getBlockState(shieldPos).isAir()) shieldSlot = shield.slot();
            else shieldPos = null;
        }

        for (int i = 0; i < tpPackets.get(); i++) sendMove(start);
        if (up1 != null) {
            sendMove(up1);
            sendMove(up2);
        }
        sendMove(stand);

        if (shieldPos != null && shieldSlot != -1) {
            InvUtils.swap(shieldSlot, true);
            placePacket(shieldPos);
        }

        if (!bestExists) {
            InvUtils.swap(anchorItem.slot(), true);
            placePacket(bestPos);
        }

        Direction side = freeSide(bestPos);
        if (bestCharges <= 0) {
            InvUtils.swap(glowstone.slot(), true);
            interactPacket(bestPos, side);
        }

        int detonator = findDetonatorSlot();
        if (detonator != -1) {
            InvUtils.swap(detonator, true);
            interactPacket(bestPos, side);
        }
        InvUtils.swapBack();

        if (up1 != null) {
            sendMove(up2);
            sendMove(up1);
        }
        sendMove(start);
        mc.player.setPosition(start.x, start.y, start.z);

        frozen = start;
        freezeTimer = freezeTicks.get();
        freezeActive = freezeTimer > 0;
        tpTimer = tpDelay.get();
        mark(bestPos);
    }

    private Vec3d findStandSpot(BlockPos anchor) {
        BlockPos[] tests = {
            anchor.up(2), anchor.north(2), anchor.south(2), anchor.east(2), anchor.west(2),
            anchor.up(1), anchor.north(1), anchor.south(1), anchor.east(1), anchor.west(1)
        };
        for (BlockPos p : tests) {
            Vec3d v = new Vec3d(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            if (!LUtils.invalid(v, mc.player)) return v;
        }
        return null;
    }

    private BlockPos shieldPosFor(BlockPos anchor, BlockPos player) {
        int dx = player.getX() - anchor.getX();
        int dz = player.getZ() - anchor.getZ();
        if (dx == 0 && dz == 0) return anchor.up();
        return anchor.add(Integer.compare(dx, 0), 0, Integer.compare(dz, 0));
    }

    private Direction freeSide(BlockPos p) {
        for (Direction d : Direction.values()) {
            if (mc.world.getBlockState(p.offset(d)).isAir()) return d;
        }
        return Direction.UP;
    }

    private void sendMove(Vec3d pos) {
        if (mc.getNetworkHandler() == null) return;
        LUtils.send(Compat.moveFull(pos.x, pos.y, pos.z, mc.player.getYaw(), mc.player.getPitch(), false, mc.player.horizontalCollision));
    }

    private void placePacket(BlockPos p) {
        LUtils.send(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(p), Direction.UP, p, false), 0));
        LUtils.send(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private void interactPacket(BlockPos p, Direction d) {
        LUtils.send(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(p), d, p, false), 0));
        LUtils.send(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private int findDetonatorSlot() {
        FindItemResult anchor = InvUtils.findInHotbar(Items.RESPAWN_ANCHOR);
        if (anchor.found()) return anchor.slot();

        FindItemResult other = InvUtils.findInHotbar(stack -> !stack.isOf(Items.GLOWSTONE));
        return other.found() ? other.slot() : -1;
    }

    private PlayerEntity findTarget() {
        Entity e = TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity p) || p == mc.player) return false;
            if (!p.isAlive() || p.isSpectator()) return false;
            if (p.distanceTo(mc.player) > (teleport.get() ? Math.max(targetRange.get(), tpRange.get()) : targetRange.get())) return false;
            if (ignoreFriends.get() && Friends.get().isFriend(p)) return false;
            return true;
        }, priority.get());

        return e instanceof PlayerEntity p ? p : null;
    }

    private void mark(BlockPos pos) {
        renderPos = pos;
        renderStamp = System.currentTimeMillis();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || renderPos == null) return;

        if (System.currentTimeMillis() - renderStamp > renderTime.get()) {
            renderPos = null;
            return;
        }

        event.renderer.box(renderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}