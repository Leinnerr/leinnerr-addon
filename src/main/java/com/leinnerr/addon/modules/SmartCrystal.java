package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.utils.BlockPlacer;
import com.leinnerr.addon.utils.LUtils;
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
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class SmartCrystal extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Objetivo");
    private final SettingGroup sgTrigger = settings.createGroup("Cuándo explotar");
    private final SettingGroup sgSafety = settings.createGroup("Seguridad");
    private final SettingGroup sgPlace = settings.createGroup("Colocación");
    private final SettingGroup sgRender = settings.createGroup("Renderizado");

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use").description("Se pausa mientras comes")
        .defaultValue(true).build());

    private final Setting<Double> minHealth = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-health").description("Solo se activa segun tu vida")
        .defaultValue(14.0).min(1).sliderMax(36).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rota en silencio hacia el cristal o el bloque antes de actuar")
        .defaultValue(true).build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing").description("Mueve la mano en cada accion")
        .defaultValue(true).build());

    private final Setting<Double> targetRange = sgTarget.add(new DoubleSetting.Builder()
        .name("target-range").description("Distancia maxima a un objetivo valido")
        .defaultValue(10.0).min(1).sliderMax(20).build());

    private final Setting<Double> targetMaxHealth = sgTarget.add(new DoubleSetting.Builder()
        .name("target-max-health").description("Solo se activa segun tu vida")
        .defaultValue(10.0).min(1).sliderMax(36).build());

    private final Setting<SortPriority> priority = sgTarget.add(new EnumSetting.Builder<SortPriority>()
        .name("priority").description("Como ordenar los objetivos")
        .defaultValue(SortPriority.LowestHealth).build());

    private final Setting<Boolean> ignoreFriends = sgTarget.add(new BoolSetting.Builder()
        .name("ignore-friends").description("No ataca tus panas")
        .defaultValue(true).build());

    private final Setting<Boolean> breakTotems = sgTrigger.add(new BoolSetting.Builder()
        .name("break-totems").description("Explota si la explosion rompe el totem del enemigo")
        .defaultValue(true).build());

    private final Setting<Double> leaveHealth = sgTrigger.add(new DoubleSetting.Builder()
        .name("leave-health").description("Explota si el enemigo quedaria con esta vida o menos tras la explosion")
        .defaultValue(4.0).min(0).sliderMax(20).build());

    private final Setting<Double> maxSelfDamage = sgSafety.add(new DoubleSetting.Builder()
        .name("max-self-damage").description("Daño propio maximo permitido por explosion")
        .defaultValue(6.0).min(0).sliderMax(36).build());

    private final Setting<Double> minHealthAfter = sgSafety.add(new DoubleSetting.Builder()
        .name("min-health-after").description("No actua si tienes menos vida que lo selccionado (aunke en inf no hay selfdamague no es como q importe mucho)")
        .defaultValue(8.0).min(0).sliderMax(36).build());

    private final Setting<Double> selfWeight = sgSafety.add(new DoubleSetting.Builder()
        .name("self-damage-weight").description("Cuanto pesa el daño propio al elegir la mejor posición")
        .defaultValue(1.0).min(0).sliderMax(3).build());

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range").description("Distancia maxima a la que colocas el cristal")
        .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Double> breakRange = sgPlace.add(new DoubleSetting.Builder()
        .name("break-range").description("Distancia maxima a la que rompes un cristal")
        .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay").description("Ticks entre cada colocacion")
        .defaultValue(2).min(0).sliderMax(20).build());

    private final Setting<Integer> breakDelay = sgPlace.add(new IntSetting.Builder()
        .name("break-delay").description("Ticks entre cada ruptura")
        .defaultValue(1).min(0).sliderMax(20).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Dibuja el bloque donde se coloca el cristal")
        .defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Como se dibujan")
        .defaultValue(ShapeMode.Both).visible(render::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Colo")
        .defaultValue(new SettingColor(120, 60, 200, 40)).visible(render::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Color de las lineas")
        .defaultValue(new SettingColor(120, 60, 200, 150)).visible(render::get).build());

    private final Setting<Integer> renderTime = sgRender.add(new IntSetting.Builder()
        .name("render-time").description("Cuanto tiempo se queda el render")
        .defaultValue(300).min(0).sliderMax(1000).visible(render::get).build());

    private PlayerEntity target;
    private int placeTimer;
    private int breakTimer;
    private BlockPos renderPos;
    private long renderStamp;

    public SmartCrystal() {
        super(LeinnerrAddon.CATEGORY, "smart-crystal",
            "Cristal inteligente el cual solo explota cuando un enemigo tiene una cantidad de vida seleccionad");
    }

    @Override
    public void onActivate() {
        target = null;
        placeTimer = 0;
        breakTimer = 0;
        renderPos = null;
    }

    @Override
    public void onDeactivate() {
        target = null;
        renderPos = null;
    }

    @Override
    public String getInfoString() {
        if (target == null) return null;
        return target.getName().getString() + " " + Math.round(target.getHealth() + target.getAbsorptionAmount());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        target = null;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (placeTimer > 0) placeTimer--;
        if (breakTimer > 0) breakTimer--;

        if (pauseOnUse.get() && mc.player.isUsingItem()) return;

        double selfHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (selfHealth < minHealth.get()) return;

        target = findTarget();
        if (target == null) return;

        double targetHealth = target.getHealth() + target.getAbsorptionAmount();
        boolean hasTotem = target.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)
            || target.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);

        EndCrystalEntity bestCrystal = null;
        double bestCrystalScore = -Double.MAX_VALUE;
        Vec3d eye = mc.player.getEyePos();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal) || !crystal.isAlive()) continue;

            Vec3d pos = LUtils.pos(crystal);
            if (eye.squaredDistanceTo(pos) > breakRange.get() * breakRange.get()) continue;

            double score = evaluate(pos, targetHealth, hasTotem, selfHealth);
            if (Double.isNaN(score)) continue;

            if (score > bestCrystalScore) {
                bestCrystalScore = score;
                bestCrystal = crystal;
            }
        }

        if (bestCrystal != null) {
            if (breakTimer == 0) {
                breakCrystal(bestCrystal);
                breakTimer = breakDelay.get();
            }
            return;
        }

        if (placeTimer > 0) return;

        FindItemResult crystalItem = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!crystalItem.found()) return;

        BlockPos best = findBestPlace(targetHealth, hasTotem, selfHealth);
        if (best == null) return;

        placeCrystal(best, crystalItem);
        placeTimer = placeDelay.get();
    }

    private double evaluate(Vec3d crystalPos, double targetHealth, boolean hasTotem, double selfHealth) {
        if (target.squaredDistanceTo(crystalPos) > 144.0) return Double.NaN;

        float targetDamage = DamageUtils.crystalDamage(target, crystalPos);
        if (targetDamage <= 0f) return Double.NaN;

        boolean lethal = targetDamage >= targetHealth;

        if (lethal) {
            if (hasTotem && !breakTotems.get()) return Double.NaN;
        } else if (targetHealth - targetDamage > leaveHealth.get()) {
            return Double.NaN;
        }

        float selfDamage = DamageUtils.crystalDamage(mc.player, crystalPos);
        if (selfDamage > maxSelfDamage.get()) return Double.NaN;
        if (selfHealth - selfDamage < minHealthAfter.get()) return Double.NaN;

        double score = targetDamage - selfDamage * selfWeight.get();
        if (lethal) score += 1000.0;
        return score;
    }

    private BlockPos findBestPlace(double targetHealth, boolean hasTotem, double selfHealth) {
        Vec3d eye = mc.player.getEyePos();
        BlockPos origin = mc.player.getBlockPos();
        int r = (int) Math.ceil(placeRange.get());
        double rangeSq = placeRange.get() * placeRange.get();

        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos base = origin.add(x, y, z);

                    if (!mc.world.getBlockState(base).isOf(Blocks.OBSIDIAN) && !mc.world.getBlockState(base).isOf(Blocks.BEDROCK)) continue;
                    if (!mc.world.getBlockState(base.up()).isAir()) continue;

                    if (eye.squaredDistanceTo(Vec3d.ofCenter(base)) > rangeSq) continue;

                    Vec3d crystalPos = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                    if (target.squaredDistanceTo(crystalPos) > 144.0) continue;

                    Box box = new Box(base.getX(), base.getY() + 1, base.getZ(), base.getX() + 1, base.getY() + 3, base.getZ() + 1);
                    if (!mc.world.getOtherEntities(null, box).isEmpty()) continue;

                    double score = evaluate(crystalPos, targetHealth, hasTotem, selfHealth);
                    if (Double.isNaN(score)) continue;

                    if (score > bestScore) {
                        bestScore = score;
                        best = base;
                    }
                }
            }
        }
        return best;
    }

    private void breakCrystal(EndCrystalEntity crystal) {
        if (rotate.get()) BlockPlacer.rotateTo(crystal.getBoundingBox().getCenter());

        LUtils.send(PlayerInteractEntityC2SPacket.attack(crystal, mc.player.isSneaking()));
        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);

        if (rotate.get()) BlockPlacer.resync();
    }

    private void placeCrystal(BlockPos base, FindItemResult item) {
        Vec3d hitVec = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);

        Hand hand = item.getHand();
        boolean swapped = false;
        if (hand == null) {
            InvUtils.swap(item.slot(), true);
            hand = Hand.MAIN_HAND;
            swapped = true;
        }

        if (rotate.get()) BlockPlacer.rotateTo(hitVec);

        mc.interactionManager.interactBlock(mc.player, hand, new BlockHitResult(hitVec, Direction.UP, base, false));
        if (swing.get()) mc.player.swingHand(hand);

        if (swapped) InvUtils.swapBack();
        if (rotate.get()) BlockPlacer.resync();

        renderPos = base;
        renderStamp = System.currentTimeMillis();
    }

    private PlayerEntity findTarget() {
        Entity e = TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity p) || p == mc.player) return false;
            if (!p.isAlive() || p.isSpectator() || p.isCreative()) return false;
            if (p.distanceTo(mc.player) > targetRange.get()) return false;
            if (ignoreFriends.get() && Friends.get().isFriend(p)) return false;
            return p.getHealth() + p.getAbsorptionAmount() <= targetMaxHealth.get();
        }, priority.get());

        return e instanceof PlayerEntity p ? p : null;
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
