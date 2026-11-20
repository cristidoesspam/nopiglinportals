package dev.nopiglinportals.mixin;

import dev.nopiglinportals.PortalRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void nopiglinportals$noPortalSpawns(
		final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random, final CallbackInfo ci
	) {
		PortalRules rules = PortalRules.current();
		if (rules != null && rules.shouldBlockSpawn(level, pos)) {
			ci.cancel();
		}
	}

	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void nopiglinportals$keepBlockedMobsOut(
		final BlockState state,
		final Level level,
		final BlockPos pos,
		final Entity entity,
		final InsideBlockEffectApplier effectApplier,
		final boolean isPrecise,
		final CallbackInfo ci
	) {
		if (level instanceof ServerLevel serverLevel) {
			PortalRules rules = PortalRules.current();
			if (rules != null && rules.shouldBlock(serverLevel, pos, entity)) {
				ci.cancel();
			}
		}
	}
}
