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

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedInteger;
import org.apache.commons.io.FileUtils;
import org.cef.callback.CefQueryCallback;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.game.*;
import uk.me.mantas.eternity.handlers.OpenSavedGame;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.Property;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Map.Entry;
import static uk.me.mantas.eternity.EKUtils.*;

public class SavedGameOpener implements Runnable {
	private static final Logger logger = Logger.getLogger(SavedGameOpener.class);
	private final String saveGameLocation;
	private final CefQueryCallback callback;
	private final PacketDeserializerFactory packetDeserializer;

	private static final Set<String> SUPPORTED_CLASS_NAMES = ImmutableSet.copyOf(new String[] {
			"int", "Integer", "float", "Float", "double", "Double", "boolean", "Boolean", "String", "UnsignedInteger"
			// , "EternityDateTime"
			// , "EternityTimeInterval"
	});

	public SavedGameOpener(final String saveGameLocation, final CefQueryCallback callback) {
		this.saveGameLocation = saveGameLocation;
		this.callback = callback;
		packetDeserializer = Environment.getInstance().factory().packetDeserializer();
	}

	@Override
	public void run() {
		final File savedgame = new File(saveGameLocation);
		final File mobileObjectsFile = new File(savedgame, "MobileObjects.save");

		if (!mobileObjectsFile.exists()) {
			OpenSavedGame.notExists(callback);
			return;
		}

		final List<Property> gameObjects = deserialize(mobileObjectsFile).stream()
				.filter(this::isObjectPersistencePacket)
				.filter(this::hasObjectName)
				.collect(Collectors.toList());

		final float currency = extractCurrency(gameObjects);
		final Map<String, Property> globals = extractGlobals(gameObjects);
		final Map<String, Property> characters = extractCharacters(gameObjects);
		final List<JSONObject> deadCompanions = extractDeadCompanions(gameObjects, characters);
		final JSONObject inventory = extractInventory(gameObjects, characters);
		final JSONObject abilities = extractAbilities(gameObjects, characters);

		sendJSON(currency, globals, characters, deadCompanions, inventory, abilities);
	}

	// Companions who died in-game have no mobile object left in the save —
	// death is recorded only as a b_X_Dead global. Surface them as synthetic
	// list entries so the UI can offer resurrection.
	private List<JSONObject> extractDeadCompanions (
		final List<Property> gameObjects, final Map<String, Property> characters) {

		final List<JSONObject> dead = new ArrayList<>();
		final Optional<Property> global =
			findProperty(gameObjects, name -> name.startsWith("InGameGlobal"));

		if (!global.isPresent()) {
			return dead;
		}

		final Optional<ComponentPersistencePacket> globalVariables =
			findComponent(unwrapPacket(global.get()).ComponentPackets, "GlobalVariables");

		if (!globalVariables.isPresent()) {
			return dead;
		}

		final Object mData = globalVariables.get().Variables.get("m_data");
		if (!(mData instanceof Hashtable)) {
			return dead;
		}

		@SuppressWarnings("unchecked")
		final Map<String, Object> table = (Map<String, Object>) mData;
		final Set<String> presentNames = characters.values().stream()
			.map(p -> unwrapPacket(p).ObjectName)
			.filter(Objects::nonNull)
			.map(String::toLowerCase)
			.collect(Collectors.toSet());

		// Parents referenced by objects whose owner no longer exists — the
		// only death trace flagless companions (Zahua) leave behind.
		final Set<String> allNames = new HashSet<>();
		for (final Property p : gameObjects) {
			final String name = unwrapPacket(p).ObjectName;
			if (name != null) {
				allNames.add(name);
			}
		}

		final Set<String> orphanParents = new HashSet<>();
		for (final Property p : gameObjects) {
			final String parent = unwrapPacket(p).Parent;
			if (parent != null && !allNames.contains(parent)) {
				orphanParents.add(parent.toLowerCase());
			}
		}

		for (final CompanionRegistry.Companion companion : CompanionRegistry.all()) {
			if (!companion.isDeadIn(table, presentNames, orphanParents)) {
				continue;
			}

			final JSONObject json = new JSONObject();
			json.put("GUID", "dead:" + companion.key);
			json.put("name", companion.displayName);
			json.put("isCompanion", true);
			json.put("isMainCharacter", false);
			json.put("isDead", true);
			json.put("resurrectable", true);
			json.put("inParty", false);
			json.put("slot", -1);
			json.put("level", 0);
			json.put("className", "Unknown");
			json.put("portrait", portraitFromSubPath(Optional.of(String.format(
				Environment.getInstance().config().companionPortraitPath()
				, companion.portraitName()))));
			json.put("stats", new JSONObject());
			dead.add(json);
		}

		return dead;
	}

	private boolean isObjectPersistencePacket(final Property property) {
		return property.obj instanceof ObjectPersistencePacket;
	}

	private boolean hasObjectName(final Property property) {
		final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
		return packet.ObjectName != null;
	}

	private float extractCurrency(final List<Property> gameObjects) {
		final Optional<Property> playerProperty = findProperty(gameObjects,
				objectName -> objectName.toLowerCase().startsWith("player_"));

		if (!playerProperty.isPresent()) {
			logger.error("Unable to find player mobile object.%n");
			return 0f;
		}

		final ObjectPersistencePacket playerPacket = unwrapPacket(playerProperty.get());
		final Optional<ComponentPersistencePacket> inventoryComponent = findComponent(playerPacket.ComponentPackets,
				"PlayerInventory");

		if (!inventoryComponent.isPresent()) {
			logger.error("Unable to find PlayerInventory component.");
			return 0f;
		}

		final Object currencyValue = inventoryComponent.get().Variables.get("currencyTotalValue");
		if (currencyValue == null) {
			logger.error("Unable to find currencyTotalValue in PlayerInventory component.");
			return 0f;
		}

		return ((CurrencyValue) currencyValue).v;
	}

	// Every party member carries their OWN 16-slot pack — the player's is a
	// PlayerInventory, a companion's a plain Inventory, and the game sets
	// MaxItems from CharacterStats.InventoryMaxSize (16) on both. The four
	// quick-item slots are a QuickbarInventory, and worn gear lives in
	// Equipment as fixed-length UUID arrays. Only the stash (plus the quest
	// and crafting bags) is party-wide, and it hangs off the player alone.
	//
	// Each ItemList entry has a same-index UUID in the parallel
	// SerializedItemList, which is also the ObjectID of the item's own
	// standalone packet elsewhere in the save.
	private JSONObject extractInventory (
		final List<Property> gameObjects, final Map<String, Property> characters) {

		final JSONObject inventory = new JSONObject();
		final JSONArray charactersJson = new JSONArray();
		final JSONObject icons = new JSONObject();

		// Equipment only stores slot UUIDs, so we need to be able to get from
		// a UUID back to the item's own packet, whose ObjectName is the prefab
		// name with a "(Clone)" suffix.
		final Map<String, String> itemNamesByID = new HashMap<>();
		for (final Property property : gameObjects) {
			final ObjectPersistencePacket packet = unwrapPacket(property);
			if (packet.ObjectID != null && packet.ObjectName != null) {
				itemNamesByID.put(packet.ObjectID.toLowerCase(), packet.ObjectName);
			}
		}

		inventory.put("characters", charactersJson);
		inventory.put("icons", icons);

		for (final Entry<String, Property> entry : characters.entrySet()) {
			final ObjectPersistencePacket packet = unwrapPacket(entry.getValue());
			final boolean isPlayer =
				packet.ObjectName.toLowerCase().startsWith("player_");
			final String packComponent = isPlayer ? "PlayerInventory" : "Inventory";

			// Stored/roster characters carry no inventory components at all.
			if (!findComponent(packet.ComponentPackets, packComponent).isPresent()) {
				continue;
			}

			final JSONObject characterJson = new JSONObject();
			characterJson.put("guid", entry.getKey());
			characterJson.put("objectName", packet.ObjectName);
			characterJson.put("packComponent", packComponent);
			characterJson.put("isPlayer", isPlayer);

			// Which slots this character actually has is a function of their
			// race and class — Equipment.HasEquipmentSlot gates the grimoire on
			// being a wizard, the head slot on not being godlike, and the pet
			// slot on being the player.
			final String characterClass = characterStat(packet, "CharacterClass");
			final String characterRace = characterStat(packet, "CharacterRace");
			characterJson.put("characterClass", characterClass);
			characterJson.put("characterRace", characterRace);

			final JSONArray missing = new JSONArray();
			if (!"Wizard".equals(characterClass)) {
				missing.put("Grimoire");
			}

			if ("Godlike".equals(characterRace)) {
				missing.put("Head");
			}

			if (!isPlayer) {
				missing.put("Pet");
			}

			// Never populated by the game; shown by nobody.
			missing.put("Cape");
			characterJson.put("unavailableSlots", missing);

			// Everyone starts with two weapon sets; the third and fourth only
			// unlock through talents (CharacterStats.MaxWeaponSets is
			// 2 + BonusWeaponSets), so the editor greys out the rest.
			characterJson.put("maxWeaponSets", 2 + intStat(packet, "BonusWeaponSets"));
			characterJson.put("pack",
				inventoryComponentToJSON(packet, packComponent, itemNamesByID, icons));
			characterJson.put(
				"quickbar"
				, inventoryComponentToJSON(
					packet, "QuickbarInventory", itemNamesByID, icons));
			characterJson.put("equipment", equipmentToJSON(packet, itemNamesByID, icons));

			if (isPlayer) {
				inventory.put(
					"stash"
					, inventoryComponentToJSON(
						packet, "StashInventory", itemNamesByID, icons));
			}

			charactersJson.put(characterJson);
		}

		return inventory;
	}

	// Abilities, spells and talents, per character.
	//
	// Each ability is a standalone object parented to its owner, so what a
	// character "knows" is just which objects hang off them — CharacterStats
	// rebuilds its list from exactly that on load. Talents are the odd one out:
	// they are only prefab names in CharacterStats.m_serializedTalents, with
	// the abilities they grant existing as separate objects alongside.
	private JSONObject extractAbilities (
		final List<Property> gameObjects, final Map<String, Property> characters) {

		final JSONObject abilities = new JSONObject();
		final JSONArray charactersJson = new JSONArray();
		final AbilityCatalog catalog = AbilityCatalog.getInstance();

		abilities.put("characters", charactersJson);
		abilities.put("catalogued", catalog.size());

		for (final Entry<String, Property> entry : characters.entrySet()) {
			final ObjectPersistencePacket packet = unwrapPacket(entry.getValue());
			if (packet.ComponentPackets == null) {
				continue;
			}

			// Stored/roster duplicates carry no stats to speak of.
			if (!findComponent(packet.ComponentPackets, "CharacterStats").isPresent()) {
				continue;
			}

			final JSONObject characterJson = new JSONObject();
			characterJson.put("guid", entry.getKey());
			characterJson.put("objectName", packet.ObjectName);

			final String characterClass = characterStat(packet, "CharacterClass");
			characterJson.put("characterClass", characterClass);
			characterJson.put("characterSubrace", characterStat(packet, "CharacterSubrace"));
			characterJson.put("level", intStat(packet, "Level"));

			// A handful of abilities are the Watcher's alone, and the racial
			// table is gated on subrace, so both have to reach the browser.
			characterJson.put("isPlayer"
				, packet.ObjectName != null
					&& packet.ObjectName.toLowerCase().startsWith("player_"));

			final JSONArray owned = new JSONArray();
			for (final Entry<String, ObjectPersistencePacket> ability
				: AbilityManager.abilitiesOf(gameObjects, packet.ObjectName).entrySet()) {

				owned.put(abilityToJSON(ability.getKey(), ability.getValue()));
			}

			characterJson.put("abilities", owned);
			characterJson.put("talents", talentsToJSON(packet));

			// Which options this character may be offered, straight out of the
			// game's own AbilityProgressionTable rather than guessed at.
			characterJson.put(
				"progressionTable", progressionTableFor(packet, characterClass));

			charactersJson.put(characterJson);
		}

		return abilities;
	}

	private JSONObject abilityToJSON (
		final String guid, final ObjectPersistencePacket packet) {

		final JSONObject json = new JSONObject();
		final String prefab = packet.ObjectName == null
			? "" : packet.ObjectName.replace("(Clone)", "").trim();

		json.put("guid", guid);
		json.put("prefab", prefab);

		// EffectType is what the object was instantiated as, which is how the
		// game itself tells a talent's ability apart from a class ability.
		for (final ComponentPersistencePacket component : packet.ComponentPackets) {
			if (component == null || component.Variables == null
				|| !component.Variables.containsKey("EffectType")) {

				continue;
			}

			json.put("component", component.TypeString == null ? "" : component.TypeString);
			final Object effect = component.Variables.get("EffectType");
			json.put("effect", effect == null ? "" : effect.toString());
			break;
		}

		decorateWithAbilityCatalog(json, prefab);
		return json;
	}

	private JSONArray talentsToJSON (final ObjectPersistencePacket packet) {

		final JSONArray talents = new JSONArray();
		final Optional<ComponentPersistencePacket> stats =
			findComponent(packet.ComponentPackets, "CharacterStats");

		if (!stats.isPresent()) {
			return talents;
		}

		final Object list = stats.get().Variables.get("m_serializedTalents");
		if (!(list instanceof CSharpCollection)) {
			return talents;
		}

		final Iterator<?> iterator = ((CSharpCollection) list).iterator();
		while (iterator.hasNext()) {
			final Object prefab = iterator.next();
			if (prefab == null) {
				continue;
			}

			final JSONObject json = new JSONObject();
			json.put("prefab", prefab.toString());
			decorateWithAbilityCatalog(json, prefab.toString());
			talents.put(json);
		}

		return talents;
	}

	/**
	 * The progression table whose options this character sees. Story companions
	 * have their own on top of their class's, carrying the abilities only they
	 * can take. The table is named after the companion, which is not always
	 * what their object is called — Durance's object is Companion_GGP — so the
	 * registry does the translating rather than the object name.
	 */
	private String progressionTableFor (
		final ObjectPersistencePacket packet, final String characterClass) {

		final String name = packet.ObjectName == null ? "" : packet.ObjectName;
		if (!name.startsWith("Companion_")) {
			return "";
		}

		final AbilityCatalog catalog = AbilityCatalog.getInstance();
		final String lowered = name.toLowerCase();

		for (final CompanionRegistry.Companion companion : CompanionRegistry.all()) {
			if (!lowered.startsWith(companion.objectNamePrefix.toLowerCase())) {
				continue;
			}

			// Most tables are named after the object (Companion_GM -> gm), the
			// expansion companions carry their pack's prefix (px1_caroc), and
			// Durance is named after himself rather than his object.
			final String fromObject = companion.objectNamePrefix
				.substring("Companion_".length()).toLowerCase();

			final String fromKey = companion.key.toLowerCase();

			for (final String candidate : new String[] {
				fromObject, "px1_" + fromObject, "px2_" + fromObject
				, fromKey, "px1_" + fromKey, "px2_" + fromKey}) {

				if (catalog.hasProgressionTable(candidate)) {
					return candidate;
				}
			}

			return "";
		}

		return "";
	}

	// Deliberately records only the icon's file name, never its bytes. Ability
	// art is as bulky as the item art the inventory already ships, and a save
	// with a full party pushed the opener's single reply past what the JCEF
	// bridge will carry — the UI asks BrowseAbilities for the handful of icons
	// actually on screen instead.
	private void decorateWithAbilityCatalog (
		final JSONObject json, final String prefab) {

		final Optional<AbilityCatalog.Entry> entry =
			AbilityCatalog.getInstance().lookup(prefab);

		if (!entry.isPresent()) {
			json.put("name", prettifyPrefabName(prefab));
			return;
		}

		final AbilityCatalog.Entry ability = entry.get();
		json.put("name", ability.name.isEmpty() ? prettifyPrefabName(prefab) : ability.name);
		json.put("key", AbilityCatalog.keyOf(prefab));

		if (!ability.description.isEmpty()) {
			json.put("description", ability.description);
		}

		if (!ability.kind.isEmpty()) {
			json.put("kind", ability.kind);
		}

		if (!ability.characterClass.isEmpty()) {
			json.put("class", ability.characterClass);
		}

		if (ability.spellLevel > 0) {
			json.put("spellLevel", ability.spellLevel);
		}

		if (ability.level > 0) {
			json.put("level", ability.level);
		}

		if (ability.passive) {
			json.put("passive", true);
		}

		if (!ability.category.isEmpty()) {
			json.put("category", ability.category);
		}

		if (!ability.icon.isEmpty()) {
			json.put("icon", ability.icon);
		}
	}

	private static String prettifyPrefabName (final String prefab) {
		return prefab.replace('_', ' ').trim();
	}

	private static int intStat (
		final ObjectPersistencePacket packet, final String variable) {

		return findComponent(packet.ComponentPackets, "CharacterStats")
			.map(stats -> stats.Variables.get(variable))
			.filter(value -> value instanceof Integer)
			.map(value -> (Integer) value)
			.orElse(0);
	}

	private static String characterStat (
		final ObjectPersistencePacket packet, final String variable) {

		return findComponent(packet.ComponentPackets, "CharacterStats")
			.map(stats -> stats.Variables.get(variable))
			.map(Object::toString)
			.orElse("");
	}

	// Order comes from EquipmentSet.SerializedEquipment, NOT from the
	// Equippable.EquipmentSlot enum — the two disagree (the enum puts the rings
	// before Hands, the serialized array puts Hands first), and following the
	// enum mislabels every character's gear. Index 6 is the deprecated cape
	// slot and is always empty in real saves.
	private static final String[] EQUIPMENT_SLOTS = {
		"Head", "Neck", "Chest", "Hands", "RightRing", "LeftRing"
		, "Cape", "Feet", "Waist", "Grimoire", "Pet"
	};

	// The Equippable flag that an item must carry to be legal in each slot,
	// so the UI can refuse to put a sword on someone's feet.
	private static final String[] EQUIPMENT_SLOT_FLAGS = {
		"HeadSlot", "NeckSlot", "ArmorSlot", "HandSlot", "RingRightHandSlot"
		, "RingLeftHandSlot", "", "FeetSlot", "WaistSlot", "GrimoireSlot", "PetSlot"
	};

	private JSONObject equipmentToJSON (
		final ObjectPersistencePacket packet
		, final Map<String, String> itemNamesByID
		, final JSONObject icons) {

		final JSONObject json = new JSONObject();
		final JSONArray slots = new JSONArray();
		final JSONArray weaponSets = new JSONArray();
		json.put("slots", slots);
		json.put("weaponSets", weaponSets);
		json.put("selectedSet", 0);

		final Optional<ComponentPersistencePacket> equipment =
			findComponent(packet.ComponentPackets, "Equipment");

		if (!equipment.isPresent()) {
			return json;
		}

		final List<String> equipped =
			guidList(equipment.get().Variables.get("EquipmentSetSerialized"));

		for (int i = 0; i < EQUIPMENT_SLOTS.length; i++) {
			final JSONObject slot = new JSONObject();
			slot.put("slot", EQUIPMENT_SLOTS[i]);
			slot.put("flag", EQUIPMENT_SLOT_FLAGS[i]);
			slot.put("index", i);
			slot.put("item", i < equipped.size()
				? equippedItemToJSON(equipped.get(i), itemNamesByID, icons)
				: JSONObject.NULL);

			slots.put(slot);
		}

		// WeaponSetsSerialized is a flat list of primary/secondary pairs.
		final List<String> weapons =
			guidList(equipment.get().Variables.get("WeaponSetsSerialized"));

		for (int i = 0; i + 1 < weapons.size(); i += 2) {
			final JSONObject set = new JSONObject();
			set.put("index", i / 2);
			set.put("primary", equippedItemToJSON(weapons.get(i), itemNamesByID, icons));
			set.put("secondary", equippedItemToJSON(weapons.get(i + 1), itemNamesByID, icons));
			weaponSets.put(set);
		}

		final Object selected = equipment.get().Variables.get("SelectedWeaponSetSerialized");
		if (selected instanceof Integer) {
			json.put("selectedSet", selected);
		}

		return json;
	}

	private static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";

	private Object equippedItemToJSON (
		final String guid
		, final Map<String, String> itemNamesByID
		, final JSONObject icons) {

		if (guid == null || guid.isEmpty() || EMPTY_GUID.equals(guid)) {
			return JSONObject.NULL;
		}

		final String objectName = itemNamesByID.get(guid.toLowerCase());
		if (objectName == null) {
			return JSONObject.NULL;
		}

		// "PX2_War_Hammer_Abydons_Hammer(Clone)" -> the catalog key.
		final String prefabName = objectName.replace("(Clone)", "").trim();
		final JSONObject json = new JSONObject();
		json.put("guid", guid);
		json.put("baseItem", prefabName);
		json.put("stackSize", 1);
		json.put("uiSlot", -1);
		decorateWithCatalog(json, prefabName, icons);

		return json;
	}

	private static List<String> guidList (final Object value) {
		final List<String> guids = new ArrayList<>();
		if (!(value instanceof CSharpCollection)) {
			return guids;
		}

		final Iterator iterator = ((CSharpCollection) value).iterator();
		while (iterator.hasNext()) {
			final Object guid = iterator.next();
			guids.add(guid == null ? "" : guid.toString());
		}

		return guids;
	}

	private JSONObject inventoryComponentToJSON (
		final ObjectPersistencePacket ownerPacket
		, final String component
		, final Map<String, String> itemNamesByID
		, final JSONObject icons) {

		final JSONObject json = new JSONObject();
		final JSONArray items = new JSONArray();
		json.put("component", component);
		json.put("items", items);
		json.put("maxItems", 0);

		final Optional<ComponentPersistencePacket> inventoryComponent =
			findComponent(ownerPacket.ComponentPackets, component);

		if (!inventoryComponent.isPresent()) {
			return json;
		}

		final Object maxItems = inventoryComponent.get().Variables.get("MaxItems");
		if (maxItems instanceof Integer) {
			json.put("maxItems", maxItems);
		}

		final Object itemList = inventoryComponent.get().Variables.get("ItemList");
		final Object serializedItemList =
			inventoryComponent.get().Variables.get("SerializedItemList");

		if (!(itemList instanceof CSharpCollection) || !(serializedItemList instanceof CSharpCollection)) {
			logger.error("%s has no ItemList/SerializedItemList.%n", component);
			return json;
		}

		final Iterator itemIterator = ((CSharpCollection) itemList).iterator();
		final Iterator guidIterator = ((CSharpCollection) serializedItemList).iterator();
		while (itemIterator.hasNext() && guidIterator.hasNext()) {
			final Object item = itemIterator.next();
			final Object guid = guidIterator.next();
			if (!(item instanceof InventoryItem)) continue;

			final InventoryItem inventoryItem = (InventoryItem) item;

			// Some entries — quick-bar slots especially — carry no BaseItem at
			// all; the game identifies them purely through the GUIDLink in
			// SerializedItemList. Fall back to the item's own packet, whose
			// ObjectName is the prefab name plus a "(Clone)" suffix.
			String baseItem = inventoryItem.BaseItem;
			if (baseItem == null || baseItem.isEmpty()) {
				final String objectName = itemNamesByID.get(guid.toString().toLowerCase());
				baseItem = objectName == null
					? "" : objectName.replace("(Clone)", "").trim();
			}

			final JSONObject itemJson = new JSONObject();
			itemJson.put("guid", guid.toString());
			itemJson.put("baseItem", baseItem);
			itemJson.put("stackSize", inventoryItem.StackSize);
			itemJson.put("uiSlot", inventoryItem.uiSlot);
			decorateWithCatalog(itemJson, baseItem, icons);
			items.put(itemJson);
		}

		return json;
	}

	// Real names and icons come from the catalog extracted out of the game's
	// asset bundles; without one we degrade to a prettified file name.
	private void decorateWithCatalog (
		final JSONObject itemJson, final String baseItem, final JSONObject icons) {

		final String key = ItemCatalog.keyOf(baseItem);
		itemJson.put("key", key);

		final ItemCatalog catalog = ItemCatalog.getInstance();
		final Optional<ItemCatalog.Entry> entry = catalog.lookup(baseItem);

		if (!entry.isPresent()) {
			itemJson.put("displayName", prettifyItemName(baseItem));
			itemJson.put("maxStack", 1);
			return;
		}

		itemJson.put("displayName", entry.get().name.isEmpty()
			? prettifyItemName(baseItem)
			: entry.get().name);
		itemJson.put("maxStack", entry.get().maxStack);
		itemJson.put("quest", entry.get().quest);
		itemJson.put("quality", entry.get().quality);
		itemJson.put("filter", entry.get().filter);

		final JSONArray slots = new JSONArray();
		entry.get().slots.forEach(slots::put);
		itemJson.put("slots", slots);

		final JSONArray classes = new JSONArray();
		entry.get().classes.forEach(classes::put);
		itemJson.put("classes", classes);

		// Icons repeat constantly across a party, so they're sent once in a
		// shared map keyed by catalog key rather than inline per item.
		if (!entry.get().icon.isEmpty() && !icons.has(key)) {
			final String data = catalog.iconData(entry.get().icon);
			if (!data.isEmpty()) {
				icons.put(key, data);
			}
		}
	}

	// The save only stores a prefab file path — the real display names live
	// in the game's binary asset bundles, which would need a separate
	// item-catalog project to parse. This is an honest approximation:
	// "Rings/Ring_PREORDER_Gauns_Pledge.prefab" -> "Ring PREORDER Gauns Pledge".
	private static String prettifyItemName (final String baseItem) {
		if (baseItem == null || baseItem.isEmpty()) {
			return "(unknown item)";
		}

		final String fileName = baseItem.substring(baseItem.lastIndexOf('/') + 1);
		final String withoutExtension = fileName.endsWith(".prefab")
			? fileName.substring(0, fileName.length() - ".prefab".length())
			: fileName;

		return withoutExtension.replace('_', ' ').trim();
	}

	private static JSONObject globalsToJSON(final Property globalProperty) {
		final ObjectPersistencePacket global = unwrapPacket(globalProperty);
		final JSONObject json = new JSONObject();

		for (final String usefulGlobal : Environment.getInstance().config().usefulGlobals()) {
			final Optional<ComponentPersistencePacket> packet = findComponent(global.ComponentPackets, usefulGlobal);

			if (usefulGlobal.equals("GlobalVariables") && packet.isPresent()) {
				// TODO: deal with hashtables more generically.
				@SuppressWarnings("unchecked")
				final Map<String, JSONObject> data = ((Hashtable<String, Integer>) packet.get().Variables.get("m_data"))
						.entrySet().stream()
						.filter(entry -> isSupportedType(entry.getValue()))
						.map(entry -> new SimpleEntry<>(entry.getKey(), recordType(entry.getValue())))
						.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
				json.put("GlobalVariables", data);
				continue;
			}

			if (packet.isPresent()) {
				final Map<String, JSONObject> variables = packet.get().Variables.entrySet().stream()
						.filter(entry -> entry.getValue() != null)
						.filter(entry -> isSupportedType(entry.getValue()))
						.map(entry -> new SimpleEntry<>(entry.getKey(), recordType(entry.getValue())))
						.collect(Collectors.toMap(Entry::getKey, Entry::getValue));

				json.put(usefulGlobal, variables);
			}
		}

		return json;
	}

	private Optional<JSONObject> charactersToJSON(final Entry<String, Property> entry) {
		final JSONObject jsonObject = new JSONObject();
		jsonObject.put("GUID", entry.getKey());

		final Property property = entry.getValue();
		final ObjectPersistencePacket packet = unwrapPacket(property);
		final boolean isCompanion = detectCompanion(packet);
		final boolean isDead = detectDead(packet);
		String name = extractName(packet);

		final Optional<Map<String, JSONObject>> stats = extractCharacterStats(packet);
		if (!stats.isPresent()) {
			// This is a stored character that is not presently in the party.
			return Optional.empty();
		}

		if (stats.get().get("OverrideName").get("value") != null
				&& !stats.get().get("OverrideName").get("value").equals("")) {

			name = (String) stats.get().get("OverrideName").get("value");
		} else if (isCompanion) {
			final String mappedName = Environment.getInstance().config().companionNameMap().get(name);

			if (mappedName != null) {
				name = mappedName;
			}

			stats.get().get("OverrideName").put("value", name);
		}

		jsonObject.put("isCompanion", isCompanion);
		jsonObject.put("isMainCharacter", packet.ObjectName.toLowerCase().startsWith("player_"));
		jsonObject.put("isDead", isDead);
		jsonObject.put("inParty", detectInParty(packet));
		jsonObject.put("slot", extractPartySlot(packet));
		jsonObject.put("level", extractLevel(packet));
		jsonObject.put("className", extractClassName(packet));
		jsonObject.put("name", name);
		jsonObject.put("portrait", extractPortrait(packet, isCompanion));
		jsonObject.put("stats", stats.get());

		return Optional.of(jsonObject);
	}

	private void sendJSON(
			final float currency, final Map<String, Property> globals,
			final Map<String, Property> characters, final List<JSONObject> deadCompanions,
			final JSONObject inventory, final JSONObject abilities) {

		final JSONObject json = new JSONObject();
		json.put("isWindowStoreSave", false);

		json.put("currency", currency);
		json.put("achievementsDisabled", detectAchievementsDisabled(globals));
		json.put("inventory", inventory);
		json.put("abilities", abilities);

		final Map<String, JSONObject> jsonGlobals = globals.entrySet().stream()
				.map(entry -> new SimpleEntry<>(entry.getKey(), globalsToJSON(entry.getValue())))
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		json.put("globals", jsonGlobals);

		final JSONObject[] jsonCharacters = characters.entrySet().stream()
				.map(this::charactersToJSON)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toArray(JSONObject[]::new);
		final JSONArray charactersArray = new JSONArray(jsonCharacters);
		deadCompanions.forEach(charactersArray::put);
		json.put("characters", charactersArray);

		callback.success(json.toString());
	}

	// The game gates achievement unlocks on exactly one flag:
	// AchievementTracker.m_disableAchievements (set by the console's
	// IRoll20s). GameState.CheatsEnabled is intentionally NOT considered —
	// it only gates runtime cheat effects, and the editor's achievements
	// toggle leaves it alone so those effects keep working.
	private boolean detectAchievementsDisabled(final Map<String, Property> globals) {
		for (final Property global : globals.values()) {
			final ObjectPersistencePacket packet = unwrapPacket(global);
			if (packet.ComponentPackets == null) {
				continue;
			}

			final Optional<ComponentPersistencePacket> component =
					findComponent(packet.ComponentPackets, "AchievementTracker");

			if (component.isPresent()
					&& Boolean.TRUE.equals(component.get().Variables.get("m_disableAchievements"))) {
				return true;
			}
		}

		return false;
	}

	private boolean detectCompanion(final ObjectPersistencePacket packet) {
		final String objectName = packet.ObjectName.toLowerCase();
		return objectName.startsWith("companion_") && !objectName.startsWith("companion_generic");
	}

	private Optional<Map<String, JSONObject>> extractCharacterStats(
			final ObjectPersistencePacket packet) {

		return findComponent(packet.ComponentPackets, "CharacterStats")
				.map(c -> c.Variables.entrySet().stream()
						.filter(entry -> isSupportedType(entry.getValue()))
						.map(entry -> new SimpleEntry<>(entry.getKey(), recordType(entry.getValue())))
						.collect(Collectors.toMap(Entry::getKey, Entry::getValue)));
	}

	private static JSONObject recordType(final Object obj) {
		final JSONObject json = new JSONObject();
		json.put("type", obj.getClass().getName());

		if (obj.getClass().isEnum()) {
			json.put("value", enumConstantName(obj).orElse(""));
		} else if (obj instanceof UnsignedInteger) {
			json.put("value", ((UnsignedInteger) obj).longValue());
		} else if (obj instanceof EternityDateTime) {
			json.put("value", ((EternityDateTime) obj).TotalSeconds);
		} else if (obj instanceof EternityTimeInterval) {
			json.put("value", ((EternityTimeInterval) obj).SerializedSeconds);
		} else {
			json.put("value", obj);
		}

		return json;
	}

	private static boolean isSupportedType(final Object obj) {
		final String cls = obj.getClass().getSimpleName();
		return SUPPORTED_CLASS_NAMES.contains(cls) || obj.getClass().isEnum();
	}

	private String extractName(final ObjectPersistencePacket packet) {
		return extractCharacterName(packet.ObjectName);
	}

	private boolean detectInParty(final ObjectPersistencePacket packet) {
		final Optional<ComponentPersistencePacket> ai =
				findComponent(packet.ComponentPackets, "PartyMemberAI");

		return ai.isPresent()
				&& Boolean.TRUE.equals(ai.get().Variables.get("IsActiveInParty"));
	}

	private int extractPartySlot(final ObjectPersistencePacket packet) {
		final Optional<Integer> slot = findComponent(packet.ComponentPackets, "PartyMemberAI")
				.map(c -> c.Variables.get("AssignedSlot"))
				.filter(v -> v instanceof Integer)
				.map(v -> (Integer) v);

		return slot.orElse(-1);
	}

	private int extractLevel(final ObjectPersistencePacket packet) {
		final Optional<Integer> level = findComponent(packet.ComponentPackets, "CharacterStats")
				.map(c -> c.Variables.get("Level"))
				.filter(v -> v instanceof Integer)
				.map(v -> (Integer) v);

		return level.orElse(0);
	}

	private String extractClassName(final ObjectPersistencePacket packet) {
		final Optional<Object> cls = findComponent(packet.ComponentPackets, "CharacterStats")
				.map(c -> c.Variables.get("CharacterClass"));

		// Unknown enum values deserialize to their raw Integer.
		return cls.filter(v -> v.getClass().isEnum())
				.map(v -> ((Enum) v).name())
				.orElse("Unknown");
	}

	private boolean detectDead(final ObjectPersistencePacket packet) {
		final Optional<Float> currentHealth = findComponent(packet.ComponentPackets, "Health")
				.map(c -> (Float) c.Variables.get("CurrentHealth"));

		return currentHealth.isPresent() && currentHealth.get() == 0f;
	}

	private String extractPortrait(
			final ObjectPersistencePacket packet, final boolean isCompanion) {

		Optional<String> portraitSubPath = findComponent(packet.ComponentPackets, "Portrait")
				.map(c -> (String) c.Variables.get("m_textureLargePath"));

		if (isCompanion && portraitSubPath.orElse("").length() < 1) {
			final String name = extractName(packet);
			portraitSubPath = Optional.of(
					String.format(
							Environment.getInstance().config().companionPortraitPath(),
							name.toLowerCase().replace(" ", "_")));
		}

		return portraitFromSubPath(portraitSubPath);
	}

	private String portraitFromSubPath(final Optional<String> portraitSubPath) {
		final JSONObject settings = Settings.getInstance().json;

		if (!portraitSubPath.isPresent()) {
			return "";
		}

		final String installationPath;
		try {
			installationPath = settings.getString("gameLocation");
		} catch (final JSONException e) {
			return "";
		}

		final Path portraitPath = Paths.get(installationPath)
				.resolve(Environment.getInstance().config().pillarsDataDirectory())
				.resolve(portraitSubPath.get())
				.normalize();

		if (!portraitPath.toFile().exists()) {
			logger.error(
					"Game files contained reference to portrait at '%s' "
							+ "but it didn't exist.%n",
					portraitPath.toString());

			return "";
		}

		try {
			final byte[] portraitData = FileUtils.readFileToByteArray(portraitPath.toFile());
			return Base64.getEncoder().encodeToString(portraitData);
		} catch (final IOException e) {
			logger.error(
					"Unable to open portrait file '%s': %s%n", portraitPath.toString(), e.getMessage());
		}

		return "";
	}

	private Map<String, Property> extractGlobals(final List<Property> gameObjects) {
		final Map<String, Property> globals = new HashMap<>();
		for (final Property property : gameObjects) {
			final ObjectPersistencePacket packet = unwrapPacket(property);

			if (packet.ObjectName.startsWith("Global")
					|| packet.ObjectName.startsWith("InGameGlobal")) {

				globals.put(packet.ObjectName.replace("(Clone)", ""), property);
			}
		}

		return globals;
	}

	private Map<String, Property> extractCharacters(final List<Property> gameObjects) {
		final Map<String, Property> characters = new HashMap<>();
		for (final Property property : gameObjects) {
			final ObjectPersistencePacket packet = unwrapPacket(property);
			final String objectName = packet.ObjectName.toLowerCase();

			if (packet.ObjectID != null
					&& (objectName.startsWith("player_")
							|| objectName.startsWith("companion_"))) {

				characters.put(packet.ObjectID, property);
			}
		}

		return characters;
	}

	private List<Property> deserialize(final File mobileObjectsFile) {
		List<Property> objects = new ArrayList<>();
		try {
			final PacketDeserializer deserializer = packetDeserializer.forFile(mobileObjectsFile);
			final Optional<DeserializedPackets> deserialized = deserializer.deserialize();
			if (!deserialized.isPresent()) {
				OpenSavedGame.deserializationError(callback);
				return objects;
			}

			objects = deserialized.get().getPackets();
		} catch (final FileNotFoundException | IndexOutOfBoundsException e) {
			OpenSavedGame.deserializationError(callback);
		}

		return objects;
	}
}
