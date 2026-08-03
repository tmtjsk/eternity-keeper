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

import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Edits the inventories carried by individual party members.
 *
 * <p>Every character owns their own 16-slot pack — the player's is a
 * {@code PlayerInventory}, a companion's a plain {@code Inventory} — plus a
 * four-slot {@code QuickbarInventory}. Only the {@code StashInventory} (and the
 * quest/crafting bags) is party-wide, and it hangs off the player alone. A
 * container is therefore addressed by the pair (owning character, component).
 *
 * <p>Each {@code InventoryItem} entry in a container's {@code ItemList} has a
 * same-index counterpart UUID in its {@code SerializedItemList}, and that UUID
 * is ALSO the ObjectID of a standalone top-level packet elsewhere in the save,
 * parented to the owning character — so removing an item means deleting all
 * three in sync, not just unlinking it from the list (the same "purge, don't
 * orphan" lesson as {@link Resurrector}), and moving one between characters
 * has to re-point that packet's {@code Parent} at its new owner.
 */
public class InventoryManager {
	private static final Logger logger = Logger.getLogger(InventoryManager.class);

	private final File saveDirectory;

	public InventoryManager (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	/** The equipment component's fixed slot array, addressed by index. */
	public static final String EQUIPMENT = "Equipment";
	private static final int EQUIPMENT_SLOTS = 11;
	private static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";

	/** One item's desired final state, relative to where it is now. */
	public static final class Change {
		public final String character;       // ObjectID of the current owner
		public final String component;       // container the item is in now
		public final String itemGuid;
		public final int stackSize;          // <= 0 means remove, whatever else is set
		public final String destCharacter;   // ObjectID of the final owner
		public final String destComponent;   // final container
		public final int destSlot;           // uiSlot to land on; < 0 picks the first free one
		public final int fromEquipmentSlot;  // equipment index the item starts in, else -1
		public final int toEquipmentSlot;    // equipment index it ends up in, else -1
		/** Indexes WeaponSetsSerialized rather than EquipmentSetSerialized. */
		public boolean weaponSet = false;
		/** Set only when adding a brand new item from the catalog. */
		public String newItemPrefab = null;
		public String newItemPath = null;

		public Change (
			final String character
			, final String component
			, final String itemGuid
			, final int stackSize
			, final String destCharacter
			, final String destComponent
			, final int destSlot) {

			this(character, component, itemGuid, stackSize, destCharacter, destComponent
				, destSlot, -1, -1);
		}

		public Change (
			final String character
			, final String component
			, final String itemGuid
			, final int stackSize
			, final String destCharacter
			, final String destComponent
			, final int destSlot
			, final int fromEquipmentSlot
			, final int toEquipmentSlot) {

			this.character = character;
			this.component = component;
			this.itemGuid = itemGuid;
			this.stackSize = stackSize;
			this.destCharacter = destCharacter;
			this.destComponent = destComponent;
			this.destSlot = destSlot;
			this.fromEquipmentSlot = fromEquipmentSlot;
			this.toEquipmentSlot = toEquipmentSlot;
		}

		/** Convenience for edits that stay inside one container. */
		public Change (
			final String character
			, final String component
			, final String itemGuid
			, final int stackSize) {

			this(character, component, itemGuid, stackSize, character, component, -1);
		}

		/** Move an item out of a pack and onto a character's equipment slot. */
		public static Change equip (
			final String character
			, final String component
			, final String itemGuid
			, final String destCharacter
			, final int equipmentSlot) {

			return new Change(character, component, itemGuid, 1, destCharacter
				, EQUIPMENT, -1, -1, equipmentSlot);
		}

		/** Put an item into one of the four weapon sets (index 0..7). */
		public static Change equipWeapon (
			final String character
			, final String component
			, final String itemGuid
			, final String destCharacter
			, final int weaponSlot) {

			final Change change = new Change(character, component, itemGuid, 1
				, destCharacter, EQUIPMENT, -1, -1, weaponSlot);

			change.weaponSet = true;
			return change;
		}

		/** Take a weapon out of a set and drop it into a pack. */
		public static Change unequipWeapon (
			final String character
			, final int weaponSlot
			, final String itemGuid
			, final String destCharacter
			, final String destComponent
			, final int destSlot) {

			final Change change = new Change(character, EQUIPMENT, itemGuid, 1
				, destCharacter, destComponent, destSlot, weaponSlot, -1);

			change.weaponSet = true;
			return change;
		}

		/** Take a worn item off and drop it into a pack. */
		public static Change unequip (
			final String character
			, final int equipmentSlot
			, final String itemGuid
			, final String destCharacter
			, final String destComponent
			, final int destSlot) {

			return new Change(character, EQUIPMENT, itemGuid, 1, destCharacter
				, destComponent, destSlot, equipmentSlot, -1);
		}

		boolean isMove () {
			return !character.equalsIgnoreCase(destCharacter)
				|| !component.equals(destComponent);
		}
	}

	public boolean apply (final List<Change> changes) throws IOException {
		final File mobileObjects = new File(saveDirectory, "MobileObjects.save");
		final Optional<DeserializedPackets> deserializedOpt =
			new PacketDeserializer(mobileObjects).deserialize();

		if (!deserializedOpt.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final DeserializedPackets deserialized = deserializedOpt.get();
		final List<Property> packets = new ArrayList<>(deserialized.getPackets());

		boolean packetsChanged = false;
		for (final Change change : changes) {
			final Optional<Property> owner = findCharacter(packets, change.character);
			if (!owner.isPresent()) {
				logger.error("No character '%s' in target save.%n", change.character);
				return false;
			}

			// Adding an item that isn't in the save yet: it needs its own
			// top-level packet before anything can reference it.
			if (change.newItemPrefab != null) {
				if (!addNewItem(packets, owner.get(), change)) {
					return false;
				}

				packetsChanged = true;
				continue;
			}

			// Worn gear lives in a fixed slot array rather than an item list,
			// so equipping and unequipping are their own operations.
			if (change.toEquipmentSlot >= 0 || change.fromEquipmentSlot >= 0) {
				if (!applyEquipmentChange(packets, owner.get(), change)) {
					return false;
				}

				continue;
			}

			final Optional<CollectionProperty> itemList =
				findList(owner.get(), change.component, "ItemList");
			final Optional<CollectionProperty> serializedList =
				findList(owner.get(), change.component, "SerializedItemList");

			if (!itemList.isPresent() || !serializedList.isPresent()) {
				logger.error(
					"Component '%s' has no ItemList/SerializedItemList.%n", change.component);
				return false;
			}

			final int index = indexOfGuid(serializedList.get(), change.itemGuid);
			if (index < 0) {
				logger.error(
					"Item '%s' not found in '%s'.%n", change.itemGuid, change.component);
				return false;
			}

			if (change.stackSize <= 0) {
				itemList.get().items.remove(index);
				serializedList.get().items.remove(index);

				final boolean removed =
					packets.removeIf(p -> p.obj instanceof ObjectPersistencePacket
						&& change.itemGuid.equalsIgnoreCase(
							((ObjectPersistencePacket) p.obj).ObjectID));

				if (!removed) {
					logger.error(
						"Item '%s' had no standalone packet to remove.%n", change.itemGuid);
					return false;
				}

				packetsChanged = true;
				continue;
			}

			if (!change.isMove()) {
				final Property item = itemList.get().items.get(index);
				if (!setStackSize(item, change.stackSize)) {
					return false;
				}

				if (change.destSlot >= 0
					&& !setSlot(item, freeSlot(itemList.get(), change.destSlot, index))) {

					return false;
				}

				continue;
			}

			final Optional<Property> destOwner = findCharacter(packets, change.destCharacter);
			if (!destOwner.isPresent()) {
				logger.error("No destination character '%s'.%n", change.destCharacter);
				return false;
			}

			final Optional<CollectionProperty> destItemList =
				findList(destOwner.get(), change.destComponent, "ItemList");
			final Optional<CollectionProperty> destSerializedList =
				findList(destOwner.get(), change.destComponent, "SerializedItemList");
			final Optional<Integer> maxItems =
				findMaxItems(destOwner.get(), change.destComponent);

			if (!destItemList.isPresent() || !destSerializedList.isPresent()
				|| !maxItems.isPresent()) {

				logger.error("Destination component '%s' not found.%n", change.destComponent);
				return false;
			}

			if (destItemList.get().items.size() >= maxItems.get()) {
				logger.error(
					"'%s' is full (%d/%d); refusing to move '%s' into it.%n"
					, change.destComponent, destItemList.get().items.size(), maxItems.get()
					, change.itemGuid);

				return false;
			}

			final Property item = itemList.get().items.remove(index);
			final Property guid = serializedList.get().items.remove(index);
			destItemList.get().items.add(item);
			destSerializedList.get().items.add(guid);

			if (!setStackSize(item, change.stackSize)
				|| !setSlot(item, freeSlot(destItemList.get(), change.destSlot, -1))) {

				return false;
			}

			// An item's own packet is parented to whoever carries it, so
			// handing it to another character has to re-point that link.
			if (!change.character.equalsIgnoreCase(change.destCharacter)
				&& !reparent(packets, change.itemGuid, destOwner.get())) {

				return false;
			}
		}

		if (packetsChanged) {
			deserialized.setPackets(packets);
			if (!Property.update(deserialized.getCount(), packets.size())) {
				logger.error("Unable to update the leading packet count.%n");
				return false;
			}
		}

		if (mobileObjects.delete()) {
			if (!mobileObjects.createNewFile()) {
				logger.error(
					"Could not create empty '%s' for serialization!%n"
					, mobileObjects.getAbsolutePath());

				return false;
			}
		} else {
			logger.warn(
				"Could not delete '%s', attempting to overwrite directly.%n"
				, mobileObjects.getAbsolutePath());
		}

		deserialized.reserialize(mobileObjects);
		return true;
	}

	/**
	 * Equipping, unequipping, and the swap that happens when you drop an item
	 * onto an occupied slot.
	 *
	 * <p>Worn gear is referenced only by UUID from the wearer's fixed 11-entry
	 * {@code EquipmentSetSerialized} array, so putting something on is really
	 * "unlink it from its pack and write its GUID into slot N" — the item's own
	 * top-level packet never moves, it just gets re-parented if it changed
	 * hands. Taking something off is the awkward direction: the pack has no
	 * entry for it, so one has to be manufactured.
	 */
	private boolean applyEquipmentChange (
		final List<Property> packets, final Property owner, final Change change) {

		final int slotIndex = change.toEquipmentSlot >= 0
			? change.toEquipmentSlot : change.fromEquipmentSlot;

		if (!change.weaponSet && slotIndex >= EQUIPMENT_SLOTS) {
			logger.error("Equipment slot %d is out of range.%n", slotIndex);
			return false;
		}

		final boolean equipping = change.toEquipmentSlot >= 0;
		final String wearerID = equipping ? change.destCharacter : change.character;
		final Optional<Property> wearer = findCharacter(packets, wearerID);

		if (!wearer.isPresent()) {
			logger.error("No character '%s' to wear the item.%n", wearerID);
			return false;
		}

		final Optional<CollectionProperty> equipment = change.weaponSet
			? findEquipmentList(wearer.get(), "WeaponSetsSerialized")
			: findEquipmentSlots(wearer.get());

		if (!equipment.isPresent()) {
			logger.error("Character '%s' has no Equipment component.%n", wearerID);
			return false;
		}

		final List<Property> slots = equipment.get().items;
		if (slotIndex >= slots.size()) {
			logger.error(
				"Equipment only has %d slots; %d requested.%n", slots.size(), slotIndex);

			return false;
		}

		// The pack the item comes from (equip) or goes to (unequip).
		final String packCharacter = equipping ? change.character : change.destCharacter;
		final String packComponent = equipping ? change.component : change.destComponent;
		final Optional<Property> packOwner = findCharacter(packets, packCharacter);

		if (!packOwner.isPresent()) {
			logger.error("No character '%s' for the pack side.%n", packCharacter);
			return false;
		}

		final Optional<CollectionProperty> itemList =
			findList(packOwner.get(), packComponent, "ItemList");
		final Optional<CollectionProperty> serializedList =
			findList(packOwner.get(), packComponent, "SerializedItemList");

		if (!itemList.isPresent() || !serializedList.isPresent()) {
			logger.error("Component '%s' has no item lists.%n", packComponent);
			return false;
		}

		final String occupant = guidOf(slots.get(slotIndex));

		if (!equipping) {
			if (!change.itemGuid.equalsIgnoreCase(occupant)) {
				logger.error(
					"Slot %d holds '%s', not '%s'.%n", slotIndex, occupant, change.itemGuid);

				return false;
			}

			final Optional<Integer> maxItems = findMaxItems(packOwner.get(), packComponent);
			if (maxItems.isPresent() && itemList.get().items.size() >= maxItems.get()) {
				logger.error("'%s' is full; nowhere to put the unequipped item.%n"
					, packComponent);

				return false;
			}

			final Optional<Property> entry = buildInventoryEntry(
				packets, change.itemGuid, freeSlot(itemList.get(), change.destSlot, -1));

			if (!entry.isPresent()) {
				return false;
			}

			itemList.get().items.add(entry.get());
			addGuid(serializedList.get(), change.itemGuid);
			setGuid(slots.get(slotIndex), EMPTY_GUID);

			return change.character.equalsIgnoreCase(change.destCharacter)
				|| reparent(packets, change.itemGuid, packOwner.get());
		}

		// Equipping: unlink from the pack, then hand the slot over.
		final int index = indexOfGuid(serializedList.get(), change.itemGuid);
		if (index < 0) {
			logger.error(
				"Item '%s' not found in '%s'.%n", change.itemGuid, packComponent);

			return false;
		}

		final Property displacedEntry = itemList.get().items.remove(index);
		serializedList.get().items.remove(index);
		setGuid(slots.get(slotIndex), change.itemGuid);

		// Whatever was already worn falls back into the pack. Reusing the
		// entry we just freed keeps this allocation-free and type-exact.
		if (!occupant.isEmpty() && !occupant.equalsIgnoreCase(change.itemGuid)) {
			final Optional<String> baseItem = prefabPathOf(packets, occupant);
			if (!baseItem.isPresent()) {
				return false;
			}

			if (!setBaseItem(displacedEntry, baseItem.get())
				|| !setStackSize(displacedEntry, 1)
				|| !setSlot(displacedEntry, freeSlot(itemList.get(), -1, -1))) {

				return false;
			}

			itemList.get().items.add(displacedEntry);
			addGuid(serializedList.get(), occupant);

			if (!reparent(packets, occupant, packOwner.get())) {
				return false;
			}
		}

		return change.character.equalsIgnoreCase(wearerID)
			|| reparent(packets, change.itemGuid, wearer.get());
	}

	/**
	 * Creates an item that isn't in the save at all yet.
	 *
	 * <p>Three things have to appear together: a top-level
	 * {@code ObjectPersistencePacket} carrying a fresh GUID and the prefab to
	 * instantiate, an {@code ItemList} entry, and the matching UUID in
	 * {@code SerializedItemList} (which the game reads as a GUIDLink and
	 * resolves against the persistence manager — hence the packet). The packet
	 * is deliberately minimal: {@code CreateObject} loads the prefab from
	 * {@code PrefabResource}, so anything not overridden here comes back as the
	 * prefab's own defaults, which is exactly right for a brand new item.
	 */
	private boolean addNewItem (
		final List<Property> packets, final Property owner, final Change change) {

		final Optional<CollectionProperty> itemList =
			findList(owner, change.destComponent, "ItemList");
		final Optional<CollectionProperty> serializedList =
			findList(owner, change.destComponent, "SerializedItemList");

		if (!itemList.isPresent() || !serializedList.isPresent()) {
			logger.error("Component '%s' has no item lists.%n", change.destComponent);
			return false;
		}

		final Optional<Integer> maxItems = findMaxItems(owner, change.destComponent);
		if (maxItems.isPresent() && itemList.get().items.size() >= maxItems.get()) {
			logger.error("'%s' is full; cannot add '%s'.%n"
				, change.destComponent, change.newItemPrefab);

			return false;
		}

		final Optional<Property> template = findAnyItemPacket(packets);
		if (!template.isPresent()) {
			logger.error("This save has no item packet to model a new one on.%n");
			return false;
		}

		final String guid = change.itemGuid;
		final String ownerName = ((ObjectPersistencePacket) owner.obj).ObjectName;
		final Optional<Property> packet = buildItemPacket(
			template.get(), guid, change.newItemPrefab, change.newItemPath, ownerName);

		if (!packet.isPresent()) {
			return false;
		}

		final Optional<ComplexProperty> entryTemplate = findAnyInventoryEntry(packets);
		if (!entryTemplate.isPresent()) {
			logger.error("This save has no InventoryItem to model a new entry on.%n");
			return false;
		}

		final ComplexProperty entry = new ComplexProperty(null, entryTemplate.get().type);
		for (final Property field : entryTemplate.get().properties) {
			if (!(field instanceof SimpleProperty) || field.name == null) {
				continue;
			}

			final Object value;
			switch (field.name) {
				case "BaseItem":        value = change.newItemPath; break;
				case "uiSlot":          value = freeSlot(itemList.get(), change.destSlot, -1); break;
				case "StackSize":       value = Math.max(1, change.stackSize); break;
				case "stackSize":       value = Math.max(1, change.stackSize); break;
				case "Original":        value = false; break;
				case "AreaLootSource":  value = false; break;
				default:                value = ((SimpleProperty) field).value; break;
			}

			final SimpleProperty copy = new SimpleProperty(field.name, field.type);
			copy.value = value;
			copy.obj = value;
			entry.properties.add(copy);
		}

		itemList.get().items.add(entry);
		addGuid(serializedList.get(), guid);
		packets.add(packet.get());

		return true;
	}

	/** Any existing item packet, used purely for its type information. */
	private static Optional<Property> findAnyItemPacket (final List<Property> packets) {
		for (final Property packet : packets) {
			if (!(packet.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket item = (ObjectPersistencePacket) packet.obj;
			if (item.PrefabResource != null
				&& item.PrefabResource.toLowerCase().contains("/items/")
				&& item.ComponentPackets != null) {

				return Optional.of(packet);
			}
		}

		return Optional.empty();
	}

	private Optional<Property> buildItemPacket (
		final Property template
		, final String guid
		, final String prefabName
		, final String prefabPath
		, final String ownerName) {

		if (!(template instanceof ComplexProperty)) {
			return Optional.empty();
		}

		final ComplexProperty source = (ComplexProperty) template;
		final ComplexProperty packet = new ComplexProperty(source.name, source.type);
		final UUID uuid = UUID.fromString(guid);

		for (final Property field : source.properties) {
			if (field.name == null) {
				continue;
			}

			// The component array is rebuilt below; everything else is either
			// copied (so types stay identical) or overridden.
			if ("ComponentPackets".equals(field.name)) {
				final Optional<Property> components = buildItemComponents(field, uuid);
				if (!components.isPresent()) {
					return Optional.empty();
				}

				packet.properties.add(components.get());
				continue;
			}

			if (!(field instanceof SimpleProperty)) {
				// Location/Rotation come across as-is; a packed inventory item
				// is never placed in the world so the value is irrelevant.
				packet.properties.add(field);
				continue;
			}

			Object value = ((SimpleProperty) field).value;
			switch (field.name) {
				case "ObjectName":      value = prefabName + "(Clone)"; break;
				case "ObjectID":        value = guid; break;
				case "GUID":            value = uuid; break;
				case "PrefabResource":  value = prefabPath; break;
				case "Parent":          value = ownerName; break;
				default:                break;
			}

			final SimpleProperty copy = new SimpleProperty(field.name, field.type);
			copy.value = value;
			copy.obj = value;
			packet.properties.add(copy);
		}

		final ObjectPersistencePacket materialised = new ObjectPersistencePacket();
		materialised.ObjectName = prefabName + "(Clone)";
		materialised.ObjectID = guid;
		materialised.GUID = uuid;
		materialised.PrefabResource = prefabPath;
		materialised.Parent = ownerName;
		packet.obj = materialised;

		return Optional.of(packet);
	}

	/**
	 * Keeps only InstanceID and Persistence. The prefab brings its own
	 * Equippable/Weapon/Consumable components with default state, which is what
	 * a newly created item should have.
	 */
	private Optional<Property> buildItemComponents (final Property template, final UUID guid) {
		if (!(template instanceof SingleDimensionalArrayProperty)) {
			logger.error("ComponentPackets was not an array.%n");
			return Optional.empty();
		}

		final SingleDimensionalArrayProperty source = (SingleDimensionalArrayProperty) template;
		final SingleDimensionalArrayProperty components =
			new SingleDimensionalArrayProperty(source.name, source.type);

		components.elementType = source.elementType;

		for (final Object item : source.items) {
			if (!(item instanceof ComplexProperty)) {
				continue;
			}

			final ComplexProperty component = (ComplexProperty) item;
			final Optional<Property> typeString = component.findProperty("TypeString");
			if (!typeString.isPresent() || !(typeString.get() instanceof SimpleProperty)) {
				continue;
			}

			final Object type = ((SimpleProperty) typeString.get()).value;
			if (!"InstanceID".equals(type) && !"Persistence".equals(type)) {
				continue;
			}

			// Copy, never share: adding the template's own properties here
			// would make two packets reference one object, and rewriting the
			// GUID below would then silently change the template item too.
			final ComplexProperty copy = copyComponent(component);
			components.items.add(copy);

			if ("InstanceID".equals(type)) {
				copy.<DictionaryProperty>findProperty("Variables")
					.flatMap(v -> v.findEntry("Guid"))
					.ifPresent(entry -> {
						((SimpleProperty) entry).value = guid;
						entry.obj = guid;
					});
			}
		}

		if (components.items.isEmpty()) {
			logger.error("Template item packet had no InstanceID/Persistence to copy.%n");
			return Optional.empty();
		}

		return Optional.of(components);
	}

	/**
	 * Independent copy of a component packet — the TypeString plus a fresh
	 * Variables dictionary whose values are new SimpleProperty instances.
	 * InstanceID and Persistence only ever hold simple values, so this doesn't
	 * need to recurse any further.
	 */
	private static ComplexProperty copyComponent (final ComplexProperty source) {
		final ComplexProperty copy = new ComplexProperty(source.name, source.type);

		for (final Property field : source.properties) {
			if (field instanceof DictionaryProperty) {
				final DictionaryProperty variables = (DictionaryProperty) field;
				final DictionaryProperty copiedVariables =
					new DictionaryProperty(variables.name, variables.type);

				copiedVariables.keyType = variables.keyType;
				copiedVariables.valueType = variables.valueType;

				for (final Map.Entry<Property, Property> entry : variables.items) {
					copiedVariables.items.add(new AbstractMap.SimpleEntry<>(
						copySimple(entry.getKey()), copySimple(entry.getValue())));
				}

				copy.properties.add(copiedVariables);
				continue;
			}

			copy.properties.add(copySimple(field));
		}

		return copy;
	}

	private static Property copySimple (final Property source) {
		if (!(source instanceof SimpleProperty)) {
			// Nothing in these two components has a nested shape; anything
			// unexpected is carried through untouched rather than dropped.
			return source;
		}

		final SimpleProperty copy = new SimpleProperty(source.name, source.type);
		copy.value = ((SimpleProperty) source).value;
		copy.obj = source.obj;
		return copy;
	}

	private static Optional<CollectionProperty> findEquipmentSlots (final Property character) {
		return findEquipmentList(character, "EquipmentSetSerialized");
	}

	private static Optional<CollectionProperty> findEquipmentList (
		final Property character, final String listName) {

		return ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(c -> EKUtils.findSubComponent(c, EQUIPMENT))
			.<DictionaryProperty>flatMap(e -> e.findProperty("Variables"))
			.flatMap(v -> v.findEntry(listName));
	}

	private static String guidOf (final Property property) {
		if (!(property instanceof SimpleProperty)
			|| ((SimpleProperty) property).value == null) {

			return "";
		}

		final String guid = ((SimpleProperty) property).value.toString();
		return EMPTY_GUID.equals(guid) ? "" : guid;
	}

	// Equipment slots hold real UUID objects, not their text — writing a String
	// through Property.update leaves the serializer emitting the wrong type and
	// the save no longer reads back.
	private static boolean setGuid (final Property property, final String guid) {
		if (!(property instanceof SimpleProperty)) {
			logger.error("Equipment slot was not a simple property.%n");
			return false;
		}

		final UUID value = UUID.fromString(guid);
		((SimpleProperty) property).value = value;
		property.obj = value;
		return true;
	}

	private static void addGuid (final CollectionProperty list, final String guid) {
		final SimpleProperty item = new SimpleProperty(null, new TypePair(UUID.class, null));
		item.value = UUID.fromString(guid);
		item.obj = item.value;
		list.items.add(item);
	}

	/**
	 * An item's prefab path, rebuilt from its own packet plus the catalog.
	 * A save records something like
	 * {@code Assets/Data/Prefabs/Items/Rings/Ring_PREORDER_Gauns_Pledge.prefab},
	 * and the packet's ObjectName gives the exact-case file name. Only that
	 * file name actually matters — {@code GameResources.LoadPrefab} throws the
	 * directory away and lowercases the rest — but keeping the real directory
	 * makes edited saves look like the game wrote them.
	 */
	private static Optional<String> prefabPathOf (
		final List<Property> packets, final String itemGuid) {

		for (final Property packet : packets) {
			if (!(packet.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket item = (ObjectPersistencePacket) packet.obj;
			if (!itemGuid.equalsIgnoreCase(item.ObjectID) || item.ObjectName == null) {
				continue;
			}

			final String prefab = item.ObjectName.replace("(Clone)", "").trim();
			final Optional<ItemCatalog.Entry> entry =
				ItemCatalog.getInstance().lookup(prefab);

			final String directory = entry
				.map(e -> e.path)
				.filter(p -> !p.isEmpty() && p.lastIndexOf('/') > 0)
				.map(p -> p.substring(0, p.lastIndexOf('/') + 1))
				.orElse("Assets/Data/Prefabs/Items/");

			return Optional.of(directory + prefab + ".prefab");
		}

		logger.error("No packet for item '%s'; cannot rebuild its prefab path.%n", itemGuid);
		return Optional.empty();
	}

	/**
	 * Manufactures the {@code InventoryItem} entry an unequipped item needs.
	 * The type strings are copied from an entry that already exists in this
	 * save rather than hardcoded, so the result is byte-compatible with
	 * whatever the game wrote.
	 */
	private Optional<Property> buildInventoryEntry (
		final List<Property> packets, final String itemGuid, final int uiSlot) {

		final Optional<String> baseItem = prefabPathOf(packets, itemGuid);
		if (!baseItem.isPresent()) {
			return Optional.empty();
		}

		final Optional<ComplexProperty> template = findAnyInventoryEntry(packets);
		if (!template.isPresent()) {
			logger.error(
				"This save has no InventoryItem to model a new entry on.%n");

			return Optional.empty();
		}

		final ComplexProperty entry =
			new ComplexProperty(null, template.get().type);

		for (final Property field : template.get().properties) {
			if (!(field instanceof SimpleProperty) || field.name == null) {
				continue;
			}

			final Object value;
			switch (field.name) {
				case "BaseItem":        value = baseItem.get(); break;
				case "uiSlot":          value = uiSlot; break;
				case "StackSize":       value = 1; break;
				case "stackSize":       value = 1; break;
				case "Original":        value = false; break;
				case "AreaLootSource":  value = false; break;
				default:                value = ((SimpleProperty) field).value; break;
			}

			final SimpleProperty copy = new SimpleProperty(field.name, field.type);
			copy.value = value;
			copy.obj = value;
			entry.properties.add(copy);
		}

		return Optional.of(entry);
	}

	private static Optional<ComplexProperty> findAnyInventoryEntry (
		final List<Property> packets) {

		for (final Property packet : packets) {
			if (!(packet instanceof ComplexProperty)) {
				continue;
			}

			final Optional<SingleDimensionalArrayProperty> components =
				((ComplexProperty) packet).findProperty("ComponentPackets");

			if (!components.isPresent()) {
				continue;
			}

			for (final Object component : components.get().items) {
				if (!(component instanceof ComplexProperty)) {
					continue;
				}

				final Optional<DictionaryProperty> variables =
					((ComplexProperty) component).findProperty("Variables");

				if (!variables.isPresent()) {
					continue;
				}

				final Optional<CollectionProperty> list = variables.get().findEntry("ItemList");
				if (!list.isPresent()) {
					continue;
				}

				for (final Property entry : list.get().items) {
					if (entry instanceof ComplexProperty
						&& !((ComplexProperty) entry).properties.isEmpty()) {

						return Optional.of((ComplexProperty) entry);
					}
				}
			}
		}

		return Optional.empty();
	}

	private static boolean setBaseItem (final Property itemProperty, final String baseItem) {
		if (!(itemProperty instanceof ComplexProperty)) {
			return false;
		}

		final Optional<Property> field = findExact((ComplexProperty) itemProperty, "BaseItem");
		if (!field.isPresent()) {
			logger.error("Inventory item is missing BaseItem.%n");
			return false;
		}

		return Property.update(field.get(), baseItem);
	}

	// The game keeps one item per uiSlot and fills gaps with the lowest free
	// index (BaseInventory.FirstFreeSlot), so mirror that rather than letting
	// two items claim the same tile.
	private static int freeSlot (
		final CollectionProperty itemList, final int preferred, final int ignoreIndex) {

		final Set<Integer> taken = new HashSet<>();
		for (int i = 0; i < itemList.items.size(); i++) {
			if (i == ignoreIndex) {
				continue;
			}

			slotOf(itemList.items.get(i)).ifPresent(taken::add);
		}

		if (preferred >= 0 && !taken.contains(preferred)) {
			return preferred;
		}

		int slot = 0;
		while (taken.contains(slot)) {
			slot++;
		}

		return slot;
	}

	private static Optional<Integer> slotOf (final Property itemProperty) {
		if (!(itemProperty instanceof ComplexProperty)) {
			return Optional.empty();
		}

		return findExact((ComplexProperty) itemProperty, "uiSlot")
			.filter(p -> p instanceof SimpleProperty)
			.map(p -> ((SimpleProperty) p).value)
			.filter(v -> v instanceof Integer)
			.map(v -> (Integer) v);
	}

	private static boolean setSlot (final Property itemProperty, final int slot) {
		if (!(itemProperty instanceof ComplexProperty)) {
			logger.error("Inventory item entry was not a ComplexProperty.%n");
			return false;
		}

		final Optional<Property> uiSlot = findExact((ComplexProperty) itemProperty, "uiSlot");
		if (!uiSlot.isPresent()) {
			logger.error("Inventory item is missing uiSlot.%n");
			return false;
		}

		return Property.update(uiSlot.get(), slot);
	}

	private static boolean reparent (
		final List<Property> packets, final String itemGuid, final Property newOwner) {

		final String ownerName = ((ObjectPersistencePacket) newOwner.obj).ObjectName;
		for (final Property packet : packets) {
			if (!(packet.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			if (!itemGuid.equalsIgnoreCase(((ObjectPersistencePacket) packet.obj).ObjectID)) {
				continue;
			}

			final Optional<Property> parent = ((ComplexProperty) packet).findProperty("Parent");
			if (!parent.isPresent()) {
				logger.error("Item packet '%s' has no Parent property.%n", itemGuid);
				return false;
			}

			return Property.update(parent.get(), ownerName);
		}

		logger.error("Item '%s' had no standalone packet to reparent.%n", itemGuid);
		return false;
	}

	// InventoryItem is the one C# type in the whole format with both a
	// backing field ("stackSize") and a same-named property ("StackSize")
	// independently serialized (Polenter.Serialization doesn't know the
	// property just wraps the field). ComplexProperty.findProperty() matches
	// case-insensitively — exactly right for bridging C#/Java naming
	// elsewhere, but here it would resolve BOTH names to whichever of the
	// two appears first, silently leaving the other one stale. Match exact
	// case instead so both are actually found and updated; the real game
	// always writes them in lockstep, so both must agree on write-back too.
	private static boolean setStackSize (final Property itemProperty, final int stackSize) {
		if (!(itemProperty instanceof ComplexProperty)) {
			logger.error("Inventory item entry was not a ComplexProperty.%n");
			return false;
		}

		final ComplexProperty item = (ComplexProperty) itemProperty;
		final Optional<Property> lower = findExact(item, "stackSize");
		final Optional<Property> upper = findExact(item, "StackSize");

		if (!lower.isPresent() || !upper.isPresent()) {
			logger.error("Inventory item is missing stackSize/StackSize.%n");
			return false;
		}

		return Property.update(lower.get(), stackSize) && Property.update(upper.get(), stackSize);
	}

	private static Optional<Property> findExact (final ComplexProperty complex, final String name) {
		for (final Property p : complex.properties) {
			if (p != null && name.equals(p.name)) {
				return Optional.of(p);
			}
		}

		return Optional.empty();
	}

	private static int indexOfGuid (final CollectionProperty serializedList, final String guid) {
		for (int i = 0; i < serializedList.items.size(); i++) {
			final Property item = serializedList.items.get(i);
			if (item instanceof SimpleProperty
				&& ((SimpleProperty) item).value != null
				&& guid.equalsIgnoreCase(((SimpleProperty) item).value.toString())) {

				return i;
			}
		}

		return -1;
	}

	private static Optional<CollectionProperty> findList (
		final Property character, final String component, final String listName) {

		return ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(c -> EKUtils.findSubComponent(c, component))
			.<DictionaryProperty>flatMap(inv -> inv.findProperty("Variables"))
			.flatMap(v -> v.findEntry(listName));
	}

	private static Optional<Integer> findMaxItems (
		final Property character, final String component) {

		final Optional<Property> maxItems = ((ComplexProperty) character)
			.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.flatMap(c -> EKUtils.findSubComponent(c, component))
			.<DictionaryProperty>flatMap(inv -> inv.findProperty("Variables"))
			.flatMap(v -> v.findEntry("MaxItems"));

		return maxItems.filter(p -> p instanceof SimpleProperty)
			.map(p -> (Integer) ((SimpleProperty) p).value);
	}

	private static Optional<Property> findCharacter (
		final List<Property> packets, final String objectID) {

		return packets.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.filter(p -> objectID.equalsIgnoreCase(
				((ObjectPersistencePacket) p.obj).ObjectID))
			.findFirst();
	}
}
