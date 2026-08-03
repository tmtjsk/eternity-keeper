/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2015 the authors.
 *
 *  Eternity Keeper is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  Eternity Keeper is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package uk.me.mantas.eternity.save;

import net.lingala.zip4j.ZipFile;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.game.AIController;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.CompanionRegistry.Companion;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import static uk.me.mantas.eternity.EKUtils.findComponent;
import static uk.me.mantas.eternity.EKUtils.unwrapPacket;

/**
 * Brings a companion who died in-game back to life. The game deletes a dead
 * companion's mobile object outright (leaving orphaned remnants of their
 * carried objects behind), records the death in a bespoke global for most
 * companions, and — for Sagani — kills her pet Itumaak in place. So
 * resurrection is a transplant: purge the remnants, lift the companion's
 * object graph (and their pet's, if any) from a donor save of the same
 * playthrough, de-collide any UUIDs the target still holds, give them a free
 * party slot, anchor them next to the player, and clear the death flags.
 * The recipe is verified to produce saves the game loads and plays
 * correctly, with the companion's full inventory and abilities intact.
 */
public class Resurrector {
	private static final Logger logger = Logger.getLogger(Resurrector.class);
	private static final int MAX_PARTY_SLOT = 5;

	public enum Result {
		OK
		, UNKNOWN_COMPANION
		, NO_DONOR
		, FAILED
	}

	private final File saveDirectory;

	public Resurrector (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	public Result resurrect (final String companionKey) throws IOException {
		final Optional<Companion> companion = CompanionRegistry.byKey(companionKey);
		if (!companion.isPresent()) {
			logger.error("Unknown companion key '%s'.%n", companionKey);
			return Result.UNKNOWN_COMPANION;
		}

		final Optional<File> donor = findDonor(companion.get().objectNamePrefix);
		if (!donor.isPresent()) {
			logger.error(
				"No same-session save contains %s alive.%n", companion.get().displayName);
			return Result.NO_DONOR;
		}

		return transplant(donor.get(), companion.get()) ? Result.OK : Result.FAILED;
	}

	// Scans the saves folder, newest first, for a save of the same
	// playthrough (session id — the first filename token) that contains the
	// companion. Returns the extracted MobileObjects.save of the first hit.
	public Optional<File> findDonor (final String objectNamePrefix) {
		final String savesLocation = Settings.getInstance().json.optString("savesLocation", "");
		if (savesLocation.isEmpty()) {
			logger.error("Saves location is not set; cannot search for a donor save.%n");
			return Optional.empty();
		}

		final String session = sessionOf(saveDirectory.getName());
		final File[] candidates = new File(savesLocation)
			.listFiles((dir, name) -> name.endsWith(".savegame")
				&& sessionOf(name).equalsIgnoreCase(session));

		if (candidates == null || candidates.length == 0) {
			return Optional.empty();
		}

		Arrays.sort(candidates, Comparator.comparingLong(File::lastModified).reversed());

		for (final File candidate : candidates) {
			final Optional<File> workspace = EKUtils.createTempDir("EK-donor");
			if (!workspace.isPresent()) {
				continue;
			}

			try {
				new ZipFile(candidate).extractFile("MobileObjects.save", workspace.get().getAbsolutePath());
				final File mobileObjects = new File(workspace.get(), "MobileObjects.save");
				final Optional<DeserializedPackets> deserialized =
					new PacketDeserializer(mobileObjects).deserialize();

				if (deserialized.isPresent()
					&& findRoot(deserialized.get().getPackets(), objectNamePrefix).isPresent()) {

					logger.info("Donor save for %s: %s%n", objectNamePrefix, candidate.getName());
					return Optional.of(mobileObjects);
				}
			} catch (final Exception e) {
				logger.error(
					"Unable to inspect candidate donor '%s': %s%n"
					, candidate.getName()
					, e.getMessage());
			}
		}

		return Optional.empty();
	}

	public boolean transplant (final File donorMobileObjects, final Companion companion)
		throws IOException {

		final List<String> extras = companion.extraFlagsToClear;
		return transplant(donorMobileObjects, companion.objectNamePrefix, companion.deathFlag,
			extras, companion.linkedCreaturePrefix, companion.questPath);
	}

	public boolean transplant (
		final File donorMobileObjects
		, final String objectNamePrefix
		, final String primaryDeathFlag
		, final List<String> extraFlagsToClear
		, final String linkedCreaturePrefix
		, final String questPath)
		throws IOException {

		final File deadMobileObjects = new File(saveDirectory, "MobileObjects.save");
		final Optional<DeserializedPackets> donorOpt =
			new PacketDeserializer(donorMobileObjects).deserialize();
		final Optional<DeserializedPackets> deadOpt =
			new PacketDeserializer(deadMobileObjects).deserialize();

		if (!donorOpt.isPresent() || !deadOpt.isPresent()) {
			logger.error("Unable to deserialize donor or target MobileObjects.save.%n");
			return false;
		}

		final DeserializedPackets donor = donorOpt.get();
		final DeserializedPackets dead = deadOpt.get();

		// The companion must exist in the donor and be absent from the target.
		final Optional<Property> root = findRoot(donor.getPackets(), objectNamePrefix);
		if (!root.isPresent()) {
			logger.error("Donor save does not contain '%s'.%n", objectNamePrefix);
			return false;
		}

		if (findRoot(dead.getPackets(), objectNamePrefix).isPresent()) {
			logger.error("'%s' is already present in this save.%n", objectNamePrefix);
			return false;
		}

		final List<Property> incoming = new ArrayList<>(collectGraph(donor.getPackets(), root.get()));

		// Death leaves the companion's carried objects behind, still parented
		// to the deleted character (any incarnation — real saves show junk
		// from Companion_X(Clone)_2 alongside _1). Purge them: nothing else
		// references them, and if the donor graph were merged on top they
		// would re-attach as duplicates, which the game's load-time
		// reconciliation resolves by dropping items and item-granted
		// abilities.
		final List<Property> retainedDead = new ArrayList<>();
		int purged = 0;
		for (final Property p : dead.getPackets()) {
			if (p.obj instanceof ObjectPersistencePacket) {
				final String parent = ((ObjectPersistencePacket) p.obj).Parent;
				if (parent != null && startsWith(parent, objectNamePrefix)) {
					purged++;
					continue;
				}
			}

			retainedDead.add(p);
		}

		// A linked pet (Sagani's Itumaak) dies IN PLACE: its object stays,
		// zero-health with Persistence.m_objDestroyed=true. Purge the corpse
		// (root, owned objects and orphans alike) and bring the donor's
		// living copy instead — same ObjectID, so identity is preserved.
		Property linkedRoot = null;
		if (linkedCreaturePrefix != null) {
			final Iterator<Property> it = retainedDead.iterator();
			int purgedLinked = 0;
			while (it.hasNext()) {
				final Property p = it.next();
				if (!(p.obj instanceof ObjectPersistencePacket)) continue;
				final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
				final boolean isCreature =
					packet.ObjectName != null && startsWith(packet.ObjectName, linkedCreaturePrefix);
				final boolean ownedByCreature =
					packet.Parent != null && startsWith(packet.Parent, linkedCreaturePrefix);

				if (isCreature || ownedByCreature) {
					it.remove();
					purgedLinked++;
				}
			}

			final Optional<Property> donorCreature =
				findRoot(donor.getPackets(), linkedCreaturePrefix);

			if (donorCreature.isPresent()) {
				linkedRoot = donorCreature.get();
				incoming.addAll(collectGraph(donor.getPackets(), linkedRoot));
				logger.info(
					"Replacing linked creature '%s' (purged %d dead objects).%n"
					, linkedCreaturePrefix, purgedLinked);
			} else {
				logger.warn(
					"Donor has no '%s'; resurrecting without the pet.%n", linkedCreaturePrefix);
			}
		}

		if (purged > 0) {
			logger.info(
				"Purged %d orphaned remnant objects of '%s'.%n", purged, objectNamePrefix);
		}

		// Anything the target STILL holds under an incoming id after the
		// purges has genuinely migrated to another owner (e.g. looted into
		// the player's inventory): keep it, and give the donor's incoming
		// copy a fresh id instead, rewriting every occurrence in the incoming
		// graphs so ItemList/SerializedItemList/Equipment stay consistent.
		final Set<String> deadIDs = new HashSet<>();
		for (final Property p : retainedDead) {
			if (p.obj instanceof ObjectPersistencePacket) {
				final String id = ((ObjectPersistencePacket) p.obj).ObjectID;
				if (id != null) {
					deadIDs.add(id);
				}
			}
		}

		final Map<String, UUID> remap = new LinkedHashMap<>();
		for (final Property p : incoming) {
			if (p == root.get() || p == linkedRoot) {
				continue;
			}

			final String id = ((ObjectPersistencePacket) p.obj).ObjectID;
			if (id != null && deadIDs.contains(id)) {
				remap.put(id, UUID.randomUUID());
			}
		}

		if (!remap.isEmpty()) {
			final Set<Property> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			int rewrites = 0;
			for (final Property p : incoming) {
				rewrites += rewriteUUIDs(p, remap, visited);
			}

			logger.info(
				"Regenerated %d colliding UUIDs (%d occurrences rewritten).%n"
				, remap.size(), rewrites);
		}

		// The companion joins the active party in a free slot.
		final Optional<Integer> freeSlot = findFreeSlot(retainedDead);
		if (!freeSlot.isPresent()) {
			logger.error("The party is full; no free slot to assign.%n");
			return false;
		}

		if (!joinParty(root.get(), objectNamePrefix, freeSlot.get(), false
			, AIController.AISummonType.NotSummoned, null)) {

			return false;
		}

		// A pet transplanted from a roster donor needs the same treatment,
		// linked to its owner and parked in the pet slot band (6 + owner's).
		if (linkedRoot != null) {
			final String ownerID = unwrapPacket(root.get()).ObjectID;
			if (!joinParty(linkedRoot, linkedCreaturePrefix, 6 + freeSlot.get(), true
				, AIController.AISummonType.AnimalCompanion
				, ownerID != null ? UUID.fromString(ownerID) : null)) {

				return false;
			}
		}

		// Anchor next to the player, like CharacterImporter does.
		final Optional<Property> player = retainedDead.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.filter(p -> {
				final String name = ((ObjectPersistencePacket) p.obj).ObjectName;
				return name != null && name.toLowerCase().startsWith("player_");
			})
			.findFirst();

		if (!player.isPresent()) {
			logger.error("No player character in target save.%n");
			return false;
		}

		final ObjectPersistencePacket playerPacket = unwrapPacket(player.get());
		if (!anchor(root.get(), playerPacket, 1f)) {
			return false;
		}

		if (linkedRoot != null && !anchor(linkedRoot, playerPacket, 2f)) {
			return false;
		}

		if (!clearFlags(retainedDead, primaryDeathFlag, extraFlagsToClear)) {
			return false;
		}

		// Death auto-failed the companion's quest; un-fail it. A failure here
		// is logged but doesn't abort — a revived companion with a failed
		// quest still beats no companion at all.
		if (questPath != null && !QuestRestorer.restore(retainedDead, questPath)) {
			logger.warn(
				"Could not restore quest '%s'; resurrecting without it.%n", questPath);
		}

		// Merge, fix the leading count, rewrite the file.
		final List<Property> merged = new ArrayList<>(incoming);
		merged.addAll(retainedDead);
		dead.setPackets(merged);
		Property.update(dead.getCount(), merged.size());

		if (deadMobileObjects.delete()) {
			if (!deadMobileObjects.createNewFile()) {
				logger.error(
					"Could not create empty '%s' for serialization!%n"
					, deadMobileObjects.getAbsolutePath());

				return false;
			}
		} else {
			logger.warn(
				"Could not delete '%s', attempting to overwrite directly.%n"
				, deadMobileObjects.getAbsolutePath());
		}

		dead.reserialize(deadMobileObjects);
		return true;
	}

	private static boolean startsWith (final String value, final String prefix) {
		return value.toLowerCase().startsWith(prefix.toLowerCase());
	}

	// Ensures a transplanted root joins the active party: updates the slot on
	// an existing PartyMemberAI (party donor) or swaps a roster copy's
	// AIPackageController for a freshly built PartyMemberAI (stronghold
	// donor) — the same component swap the game does when recruiting.
	private boolean joinParty (
		final Property root
		, final String prefix
		, final int slot
		, final boolean secondary
		, final AIController.AISummonType summonType
		, final UUID summoner) {

		final Optional<DictionaryProperty> aiVariables = ((ComplexProperty) root)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(c -> EKUtils.findSubComponent(c, "PartyMemberAI"))
			.flatMap(a -> a.<DictionaryProperty>findProperty("Variables"));

		if (aiVariables.isPresent()) {
			final Optional<Property> assignedSlot = aiVariables.get().findEntry("AssignedSlot");
			if (!assignedSlot.isPresent() || !Property.update(assignedSlot.get(), slot)) {
				logger.error("Unable to assign a party slot to '%s'.%n", prefix);
				return false;
			}
		} else if (PartyManager.findComponentProperty(root, "AIPackageController").isPresent()) {
			PartyManager.removeComponent(root, "AIPackageController");
			PartyManager.addComponent(root,
				PartyManager.buildPartyMemberAI(slot, secondary, summonType, summoner));
			logger.info("Converted roster copy of '%s' into a party member.%n", prefix);
		} else {
			logger.error("Donor copy of '%s' has no AI component.%n", prefix);
			return false;
		}

		// Roster members are stored packed at the stronghold; party members
		// must be unpacked so the game instantiates them next to the player.
		Property.update(root, "Packed", false);
		return true;
	}

	private static List<Property> collectGraph (
		final List<Property> packets, final Property root) {

		final String rootName = unwrapPacket(root).ObjectName;
		final List<Property> graph = new ArrayList<>();
		graph.add(root);
		for (final Property p : packets) {
			if (p.obj instanceof ObjectPersistencePacket
				&& rootName.equals(((ObjectPersistencePacket) p.obj).Parent)) {

				graph.add(p);
			}
		}

		return graph;
	}

	private boolean anchor (
		final Property root, final ObjectPersistencePacket playerPacket, final float offset) {

		if (!Property.update(root, "LevelName", playerPacket.LevelName)) {
			logger.error("Unable to set the transplanted object's level.%n");
			return false;
		}

		final Optional<Property> location = Property.find(root, "Location");
		if (!location.isPresent()
			|| !Property.update(location.get(), "x", playerPacket.Location.x + offset)
			|| !Property.update(location.get(), "y", playerPacket.Location.y + 1f)
			|| !Property.update(location.get(), "z", playerPacket.Location.z)) {

			logger.error("Unable to anchor the transplanted object next to the player.%n");
			return false;
		}

		return true;
	}

	private static String sessionOf (final String saveName) {
		return saveName.split(" ")[0].replace("-", "");
	}

	private static Optional<Property> findRoot (
		final List<Property> packets, final String objectNamePrefix) {

		return packets.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.filter(p -> {
				final String name = ((ObjectPersistencePacket) p.obj).ObjectName;
				return name != null
					&& startsWith(name, objectNamePrefix)
					&& !name.endsWith("_stored");
			})
			.findFirst();
	}

	private static Optional<Integer> findFreeSlot (final List<Property> packets) {
		final Set<Integer> used = new HashSet<>();
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (packet.ComponentPackets == null) continue;

			final Optional<ComponentPersistencePacket> ai =
				findComponent(packet.ComponentPackets, "PartyMemberAI");

			if (ai.isPresent() && Boolean.TRUE.equals(ai.get().Variables.get("IsActiveInParty"))
				&& ai.get().Variables.get("AssignedSlot") instanceof Integer) {

				used.add((Integer) ai.get().Variables.get("AssignedSlot"));
			}
		}

		for (int slot = 1; slot <= MAX_PARTY_SLOT; slot++) {
			if (!used.contains(slot)) {
				return Optional.of(slot);
			}
		}

		return Optional.empty();
	}

	// The primary death flag must exist and be cleared (a set flag with the
	// character alive breaks the game's narrative state); extra flags are
	// cleared only when present.
	private boolean clearFlags (
		final List<Property> packets
		, final String primaryFlag
		, final List<String> extraFlags) {

		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (packet.ObjectName == null || !packet.ObjectName.startsWith("InGameGlobal")) {
				continue;
			}

			final Optional<DictionaryProperty> mData = ((ComplexProperty) p)
				.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
				.flatMap(c -> EKUtils.findSubComponent(c, "GlobalVariables"))
				.flatMap(g -> g.<DictionaryProperty>findProperty("Variables"))
				.flatMap(v -> v.<DictionaryProperty>findEntry("m_data"));

			if (!mData.isPresent()) {
				continue;
			}

			if (primaryFlag != null) {
				final Optional<Property> flag = mData.get().findEntry(primaryFlag);
				if (!flag.isPresent()) {
					logger.error("Death flag '%s' not found in globals.%n", primaryFlag);
					return false;
				}

				if (!Property.update(flag.get(), 0)) {
					return false;
				}
			}

			for (final String extra : extraFlags) {
				final Optional<Property> flag = mData.get().findEntry(extra);
				if (flag.isPresent() && !Property.update(flag.get(), 0)) {
					return false;
				}
			}

			return true;
		}

		logger.error("No InGameGlobal object found in target save.%n");
		return false;
	}

	// Depth-first walk of a property tree replacing UUID/String occurrences
	// of remapped ids. Flat-copied reference stubs share child lists, so an
	// identity set guards against double-visits.
	private static int rewriteUUIDs (
		final Property property, final Map<String, UUID> remap, final Set<Property> visited) {

		if (property == null || !visited.add(property)) {
			return 0;
		}

		int rewrites = 0;

		if (property instanceof SimpleProperty) {
			final Object value = ((SimpleProperty) property).value;
			if (value instanceof UUID && remap.containsKey(value.toString())) {
				Property.update(property, remap.get(value.toString()));
				rewrites++;
			} else if (value instanceof String && remap.containsKey(value)) {
				Property.update(property, remap.get(value).toString());
				rewrites++;
			}

			return rewrites;
		}

		if (property instanceof SingleDimensionalArrayProperty) {
			for (final Object item : ((SingleDimensionalArrayProperty) property).items) {
				rewrites += rewriteUUIDs((Property) item, remap, visited);
			}
		} else if (property instanceof CollectionProperty) {
			for (final Property item : ((CollectionProperty) property).items) {
				rewrites += rewriteUUIDs(item, remap, visited);
			}
			for (final Object sub : ((CollectionProperty) property).properties) {
				rewrites += rewriteUUIDs((Property) sub, remap, visited);
			}
		} else if (property instanceof DictionaryProperty) {
			for (final Entry<Property, Property> entry : ((DictionaryProperty) property).items) {
				rewrites += rewriteUUIDs(entry.getKey(), remap, visited);
				rewrites += rewriteUUIDs(entry.getValue(), remap, visited);
			}
			for (final Object sub : ((DictionaryProperty) property).properties) {
				rewrites += rewriteUUIDs((Property) sub, remap, visited);
			}
		} else if (property instanceof ComplexProperty) {
			for (final Object sub : ((ComplexProperty) property).properties) {
				rewrites += rewriteUUIDs((Property) sub, remap, visited);
			}
		}

		return rewrites;
	}
}
