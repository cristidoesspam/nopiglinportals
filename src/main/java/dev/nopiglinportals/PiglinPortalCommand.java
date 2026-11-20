package dev.nopiglinportals;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.List;
import dev.nopiglinportals.PortalRules.Policy;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public final class PiglinPortalCommand {
	private static final int MAX_RADIUS = 16;
	private static final int MAX_LISTED = 40;
	private static final SimpleCommandExceptionType NOT_RUNNING =
		new SimpleCommandExceptionType(Component.literal("No Piglin Portals is not loaded on this server yet."));

	private PiglinPortalCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext buildContext) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("piglinportal")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.executes(PiglinPortalCommand::status);

		root.then(Commands.literal("enable").executes(c -> setEnabled(c, true)));
		root.then(Commands.literal("disable").executes(c -> setEnabled(c, false)));

		root.then(
			Commands.literal("default")
				.then(Commands.literal("block").executes(c -> setDefault(c, Policy.BLOCK)))
				.then(Commands.literal("allow").executes(c -> setDefault(c, Policy.ALLOW)))
		);

		LiteralArgumentBuilder<CommandSourceStack> chunk = Commands.literal("chunk");
		chunk.then(editChunks("allow", Policy.ALLOW));
		chunk.then(editChunks("block", Policy.BLOCK));
		chunk.then(editChunks("reset", null));
		chunk.then(
			Commands.literal("status")
				.executes(c -> chunkStatus(c, here(c)))
				.then(Commands.argument("pos", ColumnPosArgument.columnPos()).executes(c -> chunkStatus(c, at(c))))
		);
		chunk.then(Commands.literal("list").executes(PiglinPortalCommand::chunkList));
		chunk.then(Commands.literal("clear").executes(PiglinPortalCommand::chunkClear));
		root.then(chunk);

		root.then(
			Commands.literal("mobs")
				.then(Commands.literal("list").executes(PiglinPortalCommand::mobList))
				.then(
					Commands.literal("add")
						.then(
							Commands.argument("mob", ResourceArgument.resource(buildContext, Registries.ENTITY_TYPE))
								.executes(c -> mobAdd(c, mobArgument(c)))
						)
				)
				.then(
					Commands.literal("remove")
						.then(
							Commands.argument("mob", ResourceArgument.resource(buildContext, Registries.ENTITY_TYPE))
								.executes(c -> mobRemove(c, mobArgument(c)))
						)
				)
		);

		dispatcher.register(root);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> editChunks(final String literal, final @Nullable Policy policy) {
		return Commands.literal(literal)
			.executes(c -> editChunks(c, here(c), 0, policy))
			.then(
				Commands.argument("pos", ColumnPosArgument.columnPos())
					.executes(c -> editChunks(c, at(c), 0, policy))
					.then(
						Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
							.executes(c -> editChunks(c, at(c), IntegerArgumentType.getInteger(c, "radius"), policy))
					)
			);
	}

	private static int status(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		PortalRules rules = rules();
		CommandSourceStack source = context.getSource();
		Identifier dimension = source.getLevel().dimension().identifier();
		ChunkPos here = here(context);

		MutableComponent message = Component.literal("No Piglin Portals: ")
			.append(rules.isEnabled() ? colored("on", ChatFormatting.GREEN) : colored("off", ChatFormatting.RED))
			.append("\nBy default portals ").append(colored(describe(rules.defaultPolicy()), ChatFormatting.YELLOW))
			.append("\nAffected mobs: ").append(Component.literal(mobNames(rules)))
			.append("\nChunk overrides: ");

		var counts = rules.overrideCounts();
		if (counts.isEmpty()) {
			message.append("none");
		} else {
			boolean first = true;
			for (var entry : counts.entrySet()) {
				message.append((first ? "" : ", ") + entry.getKey() + " (" + entry.getValue() + ")");
				first = false;
			}
		}

		message.append("\nSince the server started: ").append(rules.blockedEntries() + " mob(s) kept out of portals");
		message.append("\nYour chunk ").append(chunkLabel(here)).append(" in ").append(dimension.toString()).append(": ")
			.append(chunkVerdict(rules, dimension, here));
		source.sendSuccess(() -> message, false);
		return 1;
	}

	private static int setEnabled(final CommandContext<CommandSourceStack> context, final boolean enabled) throws CommandSyntaxException {
		rules().setEnabled(enabled);
		context.getSource().sendSuccess(
			() -> Component.literal(enabled
				? "Portal blocking is on again."
				: "Portal blocking is off. Affected mobs can use portals everywhere. Your chunk settings are kept."),
			true
		);
		return 1;
	}

	private static int setDefault(final CommandContext<CommandSourceStack> context, final Policy policy) throws CommandSyntaxException {
		rules().setDefaultPolicy(policy);
		context.getSource().sendSuccess(
			() -> Component.literal("Chunks without an override now " + describe(policy) + "."),
			true
		);
		return 1;
	}

	private static int editChunks(
		final CommandContext<CommandSourceStack> context, final ChunkPos center, final int radius, final @Nullable Policy policy
	) throws CommandSyntaxException {
		PortalRules rules = rules();
		Identifier dimension = context.getSource().getLevel().dimension().identifier();
		int total = (radius * 2 + 1) * (radius * 2 + 1);
		int changed = rules.setChunks(dimension, center, radius, policy);

		String area = total == 1 ? "chunk " + chunkLabel(center) : total + " chunks around " + chunkLabel(center);
		String result;
		if (policy == null) {
			result = changed == 0
				? "No overrides to remove at " + area + "."
				: "Removed the override from " + changed + " of " + area + ". They follow the default again.";
		} else if (changed == 0) {
			result = area + " already set to " + policy.id() + ".";
		} else {
			result = "Portals in " + area + " now " + describe(policy) + " (" + changed + " changed, " + dimension + ").";
		}

		context.getSource().sendSuccess(() -> Component.literal(result), true);
		return changed;
	}

	private static int chunkStatus(final CommandContext<CommandSourceStack> context, final ChunkPos chunk) throws CommandSyntaxException {
		PortalRules rules = rules();
		Identifier dimension = context.getSource().getLevel().dimension().identifier();
		context.getSource().sendSuccess(
			() -> Component.literal("Chunk " + chunkLabel(chunk) + " in " + dimension + ": ").append(chunkVerdict(rules, dimension, chunk)),
			false
		);
		return 1;
	}

	private static int chunkList(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		PortalRules rules = rules();
		Identifier dimension = context.getSource().getLevel().dimension().identifier();
		List<ChunkPos> allowed = rules.chunksWith(dimension, Policy.ALLOW);
		List<ChunkPos> blocked = rules.chunksWith(dimension, Policy.BLOCK);

		MutableComponent message = Component.literal("Chunk overrides in " + dimension + " (default: " + rules.defaultPolicy().id() + ")");
		if (allowed.isEmpty() && blocked.isEmpty()) {
			message.append("\nNone.");
		} else {
			appendChunks(message, "allowed", allowed);
			appendChunks(message, "blocked", blocked);
		}

		context.getSource().sendSuccess(() -> message, false);
		return allowed.size() + blocked.size();
	}

	private static int chunkClear(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Identifier dimension = context.getSource().getLevel().dimension().identifier();
		int removed = rules().clearChunks(dimension);
		context.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " chunk override(s) in " + dimension + "."), true);
		return removed;
	}

	private static int mobList(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		PortalRules rules = rules();
		context.getSource().sendSuccess(() -> Component.literal("Affected mobs: " + mobNames(rules)), false);
		return rules.mobs().size();
	}

	private static int mobAdd(final CommandContext<CommandSourceStack> context, final Identifier mob) throws CommandSyntaxException {
		boolean changed = rules().addMob(mob);
		context.getSource().sendSuccess(
			() -> Component.literal(changed ? mob + " is now kept out of nether portals." : mob + " was already on the list."),
			changed
		);
		return changed ? 1 : 0;
	}

	private static int mobRemove(final CommandContext<CommandSourceStack> context, final Identifier mob) throws CommandSyntaxException {
		boolean changed = rules().removeMob(mob);
		context.getSource().sendSuccess(
			() -> Component.literal(changed ? mob + " can use nether portals again." : mob + " was not on the list."),
			changed
		);
		return changed ? 1 : 0;
	}

	private static PortalRules rules() throws CommandSyntaxException {
		PortalRules rules = PortalRules.current();
		if (rules == null) {
			throw NOT_RUNNING.create();
		}
		return rules;
	}

	private static ChunkPos here(final CommandContext<CommandSourceStack> context) {
		return ChunkPos.containing(BlockPos.containing(context.getSource().getPosition()));
	}

	private static ChunkPos at(final CommandContext<CommandSourceStack> context) {
		ColumnPos column = ColumnPosArgument.getColumnPos(context, "pos");
		return column.toChunkPos();
	}

	private static Identifier mobArgument(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return ResourceArgument.getResource(context, "mob", Registries.ENTITY_TYPE).key().identifier();
	}

	private static String describe(final Policy policy) {
		return policy == Policy.BLOCK ? "keep affected mobs out" : "let affected mobs through";
	}

	private static String chunkLabel(final ChunkPos chunk) {
		return "[" + chunk.x() + ", " + chunk.z() + "]";
	}

	private static MutableComponent chunkVerdict(final PortalRules rules, final Identifier dimension, final ChunkPos chunk) {
		@Nullable Policy override = rules.overrideAt(dimension, chunk);
		Policy effective = override != null ? override : rules.defaultPolicy();
		String source = override != null ? "set for this chunk" : "default";
		MutableComponent verdict = effective == Policy.BLOCK
			? colored("mobs are blocked", ChatFormatting.RED)
			: colored("mobs can pass", ChatFormatting.GREEN);
		verdict.append(Component.literal(" (" + source + ")"));
		if (!rules.isEnabled()) {
			verdict.append(colored(" - but blocking is switched off", ChatFormatting.GRAY));
		}
		return verdict;
	}

	private static void appendChunks(final MutableComponent message, final String label, final List<ChunkPos> chunks) {
		if (chunks.isEmpty()) {
			return;
		}

		StringBuilder line = new StringBuilder("\n").append(label).append(" (").append(chunks.size()).append("): ");
		for (int i = 0; i < chunks.size() && i < MAX_LISTED; i++) {
			line.append(i == 0 ? "" : " ").append(chunkLabel(chunks.get(i)));
		}
		if (chunks.size() > MAX_LISTED) {
			line.append(" ... and ").append(chunks.size() - MAX_LISTED).append(" more");
		}
		message.append(line.toString());
	}

	private static String mobNames(final PortalRules rules) {
		return rules.mobs().isEmpty() ? "none" : String.join(", ", rules.mobs().stream().map(Identifier::toString).toList());
	}

	private static MutableComponent colored(final String text, final ChatFormatting color) {
		return Component.literal(text).withStyle(color);
	}
}
