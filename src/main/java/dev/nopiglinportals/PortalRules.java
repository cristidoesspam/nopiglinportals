package dev.nopiglinportals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

public final class PortalRules {
	public enum Policy {
		BLOCK,
		ALLOW;

		public String id() {
			return this == BLOCK ? "block" : "allow";
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "nopiglinportals.json";

	private static @Nullable PortalRules current;

	private final Path file;
	private boolean enabled = true;
	private Policy defaultPolicy = Policy.BLOCK;
	private final Set<Identifier> mobs = new LinkedHashSet<>();
	private final Map<Identifier, Map<Long, Policy>> overrides = new HashMap<>();

	private static final long REPEAT_WINDOW_TICKS = 20;
	private static final int MAX_TRACKED = 512;

	private final Map<UUID, Long> lastBlocked = new HashMap<>();
	private int blockedEntries;

	private PortalRules(final Path file) {
		this.file = file;
		this.resetMobs();
	}

	private void resetMobs() {
		this.mobs.clear();
		this.mobs.add(EntityType.getKey(EntityTypes.PIGLIN));
		this.mobs.add(EntityType.getKey(EntityTypes.PIGLIN_BRUTE));
		this.mobs.add(EntityType.getKey(EntityTypes.ZOMBIFIED_PIGLIN));
	}

	public static @Nullable PortalRules current() {
		return current;
	}

	public static void load(final MinecraftServer server) {
		PortalRules rules = new PortalRules(server.getWorldPath(LevelResource.DATA).resolve(FILE_NAME));
		rules.read();
		current = rules;
	}

	public static void unload() {
		current = null;
	}

	public boolean shouldBlock(final ServerLevel level, final BlockPos portalPos, final Entity entity) {
		if (!this.enabled) {
			return false;
		}

		boolean affected = false;
		Iterator<Entity> stack = entity.getPassengersAndSelf().iterator();
		while (stack.hasNext()) {
			Entity member = stack.next();
			if (member instanceof Player) {
				return false;
			}
			if (this.mobs.contains(EntityType.getKey(member.getType()))) {
				affected = true;
			}
		}

		if (affected && this.policyAt(level.dimension().identifier(), ChunkPos.containing(portalPos)) == Policy.BLOCK) {
			this.noteBlocked(entity, level.getGameTime());
			return true;
		}
		return false;
	}

	public boolean shouldBlockSpawn(final ServerLevel level, final BlockPos portalPos) {
		return this.enabled
			&& this.mobs.contains(EntityType.getKey(EntityTypes.ZOMBIFIED_PIGLIN))
			&& this.policyAt(level.dimension().identifier(), ChunkPos.containing(portalPos)) == Policy.BLOCK;
	}

	public int blockedEntries() {
		return this.blockedEntries;
	}

	private void noteBlocked(final Entity entity, final long now) {
		Long last = this.lastBlocked.put(entity.getUUID(), now);
		if (last == null || now - last > REPEAT_WINDOW_TICKS) {
			this.blockedEntries++;
		}
		if (this.lastBlocked.size() > MAX_TRACKED) {
			this.lastBlocked.values().removeIf(time -> now - time > REPEAT_WINDOW_TICKS);
		}
	}

	public Policy policyAt(final Identifier dimension, final ChunkPos chunk) {
		Policy override = this.overrideAt(dimension, chunk);
		return override != null ? override : this.defaultPolicy;
	}

	public @Nullable Policy overrideAt(final Identifier dimension, final ChunkPos chunk) {
		Map<Long, Policy> chunks = this.overrides.get(dimension);
		return chunks == null ? null : chunks.get(ChunkPos.pack(chunk.x(), chunk.z()));
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(final boolean enabled) {
		this.enabled = enabled;
		this.save();
	}

	public Policy defaultPolicy() {
		return this.defaultPolicy;
	}

	public void setDefaultPolicy(final Policy policy) {
		this.defaultPolicy = policy;
		this.save();
	}

	public Set<Identifier> mobs() {
		return java.util.Collections.unmodifiableSet(this.mobs);
	}

	public boolean addMob(final Identifier id) {
		boolean changed = this.mobs.add(id);
		if (changed) {
			this.save();
		}
		return changed;
	}

	public boolean removeMob(final Identifier id) {
		boolean changed = this.mobs.remove(id);
		if (changed) {
			this.save();
		}
		return changed;
	}

	public int setChunks(final Identifier dimension, final ChunkPos center, final int radius, final @Nullable Policy policy) {
		Map<Long, Policy> chunks = this.overrides.computeIfAbsent(dimension, k -> new HashMap<>());
		int changed = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				long key = ChunkPos.pack(center.x() + dx, center.z() + dz);
				Policy before = policy == null ? chunks.remove(key) : chunks.put(key, policy);
				if (before != policy) {
					changed++;
				}
			}
		}

		if (chunks.isEmpty()) {
			this.overrides.remove(dimension);
		}
		if (changed > 0) {
			this.save();
		}
		return changed;
	}

	public int clearChunks(final Identifier dimension) {
		Map<Long, Policy> removed = this.overrides.remove(dimension);
		int count = removed == null ? 0 : removed.size();
		if (count > 0) {
			this.save();
		}
		return count;
	}

	public List<ChunkPos> chunksWith(final Identifier dimension, final Policy policy) {
		List<ChunkPos> result = new ArrayList<>();
		Map<Long, Policy> chunks = this.overrides.get(dimension);
		if (chunks != null) {
			chunks.forEach((key, value) -> {
				if (value == policy) {
					result.add(ChunkPos.unpack(key));
				}
			});
		}

		result.sort((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
		return result;
	}

	public Map<Identifier, Integer> overrideCounts() {
		Map<Identifier, Integer> counts = new HashMap<>();
		this.overrides.forEach((dimension, chunks) -> counts.put(dimension, chunks.size()));
		return counts;
	}

	private void read() {
		if (!Files.exists(this.file)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			JsonObject root = GSON.fromJson(reader, JsonObject.class);
			if (root == null) {
				return;
			}

			if (root.has("enabled")) {
				this.enabled = root.get("enabled").getAsBoolean();
			}
			if (root.has("default")) {
				this.defaultPolicy = parsePolicy(root.get("default").getAsString());
			}
			if (root.has("mobs")) {
				this.mobs.clear();
				for (JsonElement entry : root.getAsJsonArray("mobs")) {
					Identifier id = Identifier.tryParse(entry.getAsString());
					if (id != null) {
						this.mobs.add(id);
					}
				}
			}
			if (root.has("chunks")) {
				for (Map.Entry<String, JsonElement> dimension : root.getAsJsonObject("chunks").entrySet()) {
					Identifier dimensionId = Identifier.tryParse(dimension.getKey());
					if (dimensionId == null) {
						continue;
					}

					Map<Long, Policy> chunks = new HashMap<>();
					JsonObject byPolicy = dimension.getValue().getAsJsonObject();
					for (Policy policy : Policy.values()) {
						if (byPolicy.has(policy.id())) {
							for (JsonElement entry : byPolicy.getAsJsonArray(policy.id())) {
								ChunkPos pos = parseChunk(entry.getAsString());
								if (pos != null) {
									chunks.put(ChunkPos.pack(pos.x(), pos.z()), policy);
								}
							}
						}
					}

					if (!chunks.isEmpty()) {
						this.overrides.put(dimensionId, chunks);
					}
				}
			}
		} catch (Exception e) {
			Path broken = this.file.resolveSibling(FILE_NAME + ".bad");
			NoPiglinPortals.LOGGER.error("Could not read {}, using defaults. Moving it to {}", this.file, broken, e);
			try {
				Files.move(this.file, broken, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException moveError) {
				NoPiglinPortals.LOGGER.error("Could not move the unreadable file aside", moveError);
			}
			this.enabled = true;
			this.defaultPolicy = Policy.BLOCK;
			this.resetMobs();
			this.overrides.clear();
		}
	}

	private void save() {
		JsonObject root = new JsonObject();
		root.addProperty("enabled", this.enabled);
		root.addProperty("default", this.defaultPolicy.id());

		JsonArray mobArray = new JsonArray();
		this.mobs.forEach(id -> mobArray.add(id.toString()));
		root.add("mobs", mobArray);

		JsonObject chunkObject = new JsonObject();
		for (Identifier dimension : this.overrides.keySet()) {
			JsonObject byPolicy = new JsonObject();
			for (Policy policy : Policy.values()) {
				JsonArray array = new JsonArray();
				for (ChunkPos pos : this.chunksWith(dimension, policy)) {
					array.add(pos.x() + "," + pos.z());
				}
				if (!array.isEmpty()) {
					byPolicy.add(policy.id(), array);
				}
			}
			chunkObject.add(dimension.toString(), byPolicy);
		}
		root.add("chunks", chunkObject);

		Path temp = this.file.resolveSibling(FILE_NAME + ".tmp");
		try {
			Files.createDirectories(this.file.getParent());
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			try {
				Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			NoPiglinPortals.LOGGER.error("Could not save {}", this.file, e);
		}
	}

	private static Policy parsePolicy(final String text) {
		return "allow".equalsIgnoreCase(text) ? Policy.ALLOW : Policy.BLOCK;
	}

	private static @Nullable ChunkPos parseChunk(final String text) {
		String[] parts = text.split(",");
		if (parts.length != 2) {
			return null;
		}

		try {
			return new ChunkPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
