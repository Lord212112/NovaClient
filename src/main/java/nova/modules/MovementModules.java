package nova.modules;

import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import nova.core.*;

public final class MovementModules {
    private MovementModules() {}

    public static class Flight extends Mod {
        Setting.Num speed = num("Speed", 1.0, 0.1, 5, 0.1);
        Setting.Num vertical = num("Vertical", 0.5, 0.1, 3, 0.1);
        public Flight() { super("Flight", Category.MOVEMENT, "Velocity based flight."); }

        @Override public void onTick() {
            var p = mc.player;
            Vec3d d = U.moveDir(speed.get());
            double vy = U.jump() ? vertical.get() : U.sneak() ? -vertical.get() : 0;
            p.setVelocity(d.x, vy, d.z);
        }
    }

    public static class Speed extends Mod {
        Setting.Num speed = num("Speed", 0.4, 0.1, 3, 0.05);
        Setting.Bool groundOnly = bool("Ground only", false);
        public Speed() { super("Speed", Category.MOVEMENT, "Sets horizontal velocity."); }

        @Override public void onTick() {
            var p = mc.player;
            if (!U.moving()) return;
            if (groundOnly.get() && !p.isOnGround()) return;
            Vec3d d = U.moveDir(speed.get());
            p.setVelocity(d.x, p.getVelocity().y, d.z);
        }
    }

    public static class Sprint extends Mod {
        public Sprint() { super("Sprint", Category.MOVEMENT, "Always sprint while moving forward."); }

        @Override public void onTick() {
            var p = mc.player;
            if (U.fwd() && !p.horizontalCollision && !p.isSneaking() && p.getHungerManager().getFoodLevel() > 6)
                p.setSprinting(true);
        }
    }

    public static class Step extends Mod {
        Setting.Num height = num("Height", 1.0, 0.6, 2.5, 0.1);
        public Step() { super("Step", Category.MOVEMENT, "Step up blocks."); }

        @Override public void onTick() {
            var a = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
            if (a != null) a.setBaseValue(height.get());
        }
        @Override public void onDisable() {
            if (mc.player == null) return;
            var a = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
            if (a != null) a.setBaseValue(0.6);
        }
    }

    public static class HighJump extends Mod {
        Setting.Num power = num("Power", 0.9, 0.42, 3, 0.05);
        public HighJump() { super("HighJump", Category.MOVEMENT, "Jump higher."); }

        @Override public void onTick() {
            var p = mc.player;
            if (U.jump() && p.isOnGround()) { Vec3d v = p.getVelocity(); p.setVelocity(v.x, power.get(), v.z); }
        }
    }

    public static class AutoJump extends Mod {
        public AutoJump() { super("AutoJump", Category.MOVEMENT, "Jumps automatically while moving."); }
        @Override public void onTick() { if (mc.player.isOnGround() && U.moving()) mc.player.jump(); }
    }

    public static class Parkour extends Mod {
        public Parkour() { super("Parkour", Category.MOVEMENT, "Jumps at the edge of blocks."); }

        @Override public void onTick() {
            var p = mc.player;
            if (!p.isOnGround() || p.isSneaking() || !U.moving()) return;
            Box b = p.getBoundingBox().offset(0, -0.5, 0).expand(-0.001, 0, -0.001);
            if (!mc.world.getBlockCollisions(p, b).iterator().hasNext()) p.jump();
        }
    }

    public static class AutoWalk extends Mod {
        public AutoWalk() { super("AutoWalk", Category.MOVEMENT, "Holds the forward key."); }
        @Override public void onTick() { mc.options.forwardKey.setPressed(true); }
        @Override public void onDisable() { mc.options.forwardKey.setPressed(false); }
    }

    public static class Sneak extends Mod {
        public Sneak() { super("Sneak", Category.MOVEMENT, "Holds the sneak key."); }
        @Override public void onTick() { mc.options.sneakKey.setPressed(true); }
        @Override public void onDisable() { mc.options.sneakKey.setPressed(false); }
    }

    public static class Glide extends Mod {
        Setting.Num fall = num("Fall speed", 0.05, 0.01, 0.5, 0.01);
        public Glide() { super("Glide", Category.MOVEMENT, "Falls slowly."); }

        @Override public void onTick() {
            var p = mc.player;
            Vec3d v = p.getVelocity();
            if (!p.isOnGround() && v.y < -fall.get()) p.setVelocity(v.x, -fall.get(), v.z);
        }
    }

    public static class Spider extends Mod {
        public Spider() { super("Spider", Category.MOVEMENT, "Climb walls."); }

        @Override public void onTick() {
            var p = mc.player;
            if (p.horizontalCollision) { Vec3d v = p.getVelocity(); p.setVelocity(v.x, 0.2, v.z); }
        }
    }

    public static class FastClimb extends Mod {
        public FastClimb() { super("FastClimb", Category.MOVEMENT, "Climb ladders faster."); }

        @Override public void onTick() {
            var p = mc.player;
            if (p.isClimbing() && U.jump()) { Vec3d v = p.getVelocity(); p.setVelocity(v.x, 0.3, v.z); }
        }
    }

    public static class Jesus extends Mod {
        Setting.Num lift = num("Lift", 0.11, 0.02, 0.4, 0.01);
        public Jesus() { super("Jesus", Category.MOVEMENT, "Stay on top of water."); }
        @Override public void onTick() {
            var p = mc.player;
            if (U.sneak() || !p.isTouchingWater()) return;
            Vec3d v = p.getVelocity();
            p.setVelocity(v.x, Math.max(v.y, lift.get()), v.z);
        }
    }

    public static class FastFall extends Mod {
        Setting.Num speed = num("Fall speed", 0.8, 0.2, 3, 0.1);
        public FastFall() { super("FastFall", Category.MOVEMENT, "Falls faster."); }
        @Override public void onTick() {
            var p = mc.player;
            if (p.isOnGround() || p.isClimbing() || p.isTouchingWater() || p.fallDistance < 0.5f) return;
            Vec3d v = p.getVelocity();
            if (v.y < 0 && v.y > -speed.get()) p.setVelocity(v.x, -speed.get(), v.z);
        }
    }

    public static class LongJump extends Mod {
        Setting.Num boost = num("Boost", 1.6, 1.0, 4, 0.1);
        boolean wasGround;
        public LongJump() { super("LongJump", Category.MOVEMENT, "Jump further forward."); }
        @Override public void onTick() {
            var p = mc.player;
            Vec3d v = p.getVelocity();
            if (wasGround && !p.isOnGround() && v.y > 0 && U.moving()) p.setVelocity(v.x * boost.get(), v.y, v.z * boost.get());
            wasGround = p.isOnGround();
        }
    }
}
