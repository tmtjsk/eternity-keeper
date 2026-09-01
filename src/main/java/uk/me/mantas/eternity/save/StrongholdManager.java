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
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.game.StrongholdUpgrade;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Building and demolishing stronghold upgrades, and the bookkeeping that has to
 * go with them.
 *
 * <p>The list itself is the easy half: {@code m_upgradesBuilt} is a plain
 * {@code List<StrongholdUpgrade.Type>} with no cross-references, no UUIDs and
 * no parallel structure to keep in step — unlike almost everything else in a
 * save.
 *
 * <p>The half that needs care is that Prestige and Security are independent
 * persisted scalars. {@code Stronghold.CompleteBuildingUpgrade()} adds the
 * upgrade's own adjustments once, when it is built, and
 * {@code DestroyUpgrade()} subtracts them again; neither number is ever
 * recomputed from the list. Appending to the list alone would therefore produce
 * a stronghold with upgrades it gets no credit for — a state the game could
 * never have reached. The same goes for
 * {@code UpgradeCompletedGlobalVariableName}, which area content keys off.
 *
 * <p>Everything here is refused outright unless the player actually owns Caed
 * Nua ({@code SerializedIsActivated}), because until then the stronghold does
 * not exist as far as the game is concerned.
 */
public class StrongholdManager {
	private static final Logger logger = Logger.getLogger(StrongholdManager.class);

	private static final String TYPE_UPGRADE =
		"StrongholdUpgrade+Type, Assembly-CSharp";

	private final File saveDirectory;

	public StrongholdManager (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	/** One edit. Order is preserved: the arithmetic runs as the game's would. */
	public static final class Change {
		public enum Kind { ACTIVATE, ADD_UPGRADE, REMOVE_UPGRADE, SET_NUMBER, SET_FLAG }

		public final Kind kind;
		public final String name;
		public final int number;
		public final boolean flag;

		private Change (
			final Kind kind, final String name, final int number, final boolean flag) {

			this.kind = kind;
			this.name = name;
			this.number = number;
			this.flag = flag;
		}

		public static Change activate (final boolean activated) {
			return new Change(Kind.ACTIVATE, "SerializedIsActivated", 0, activated);
		}

		public static Change addUpgrade (final String key) {
			return new Change(Kind.ADD_UPGRADE, key, 0, false);
		}

		public static Change removeUpgrade (final String key) {
			return new Change(Kind.REMOVE_UPGRADE, key, 0, false);
		}

		public static Change setNumber (final String variable, final int value) {
			return new Change(Kind.SET_NUMBER, variable, value, false);
		}

		public static Change setFlag (final String variable, final boolean value) {
			return new Change(Kind.SET_FLAG, variable, 0, value);
		}
	}

	/** Whether the player has taken Caed Nua in this save. */
	public boolean isActivated () throws IOException {
		final Optional<DeserializedPackets> deserialized = read();
		if (!deserialized.isPresent()) {
			return false;
		}

		return findStronghold(deserialized.get())
			.flatMap(s -> s.<Property>findEntry("SerializedIsActivated"))
			.map(p -> Boolean.TRUE.equals(p.obj))
			.orElse(false);
	}

	/**
	 * @return true when the save was changed and rewritten.
	 */
	public boolean apply (final List<Change> changes) throws IOException {
		if (changes == null || changes.isEmpty()) {
			return false;
		}

		final Optional<DeserializedPackets> deserialized = read();
		if (!deserialized.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final Optional<DictionaryProperty> stronghold = findStronghold(deserialized.get());
		if (!stronghold.isPresent()) {
			logger.error("Save has no Stronghold component.%n");
			return false;
		}

		// Activation has to be settled before anything else: an upgrade on a
		// stronghold nobody owns is meaningless, and the ACTIVATE change in
		// this very batch may be what grants it.
		boolean activated = stronghold.get()
			.<Property>findEntry("SerializedIsActivated")
			.map(p -> Boolean.TRUE.equals(p.obj))
			.orElse(false);

		boolean changed = false;

		for (final Change change : changes) {
			if (change.kind == Change.Kind.ACTIVATE) {
				if (activated != change.flag
					&& setEntry(stronghold.get(), change.name, change.flag)) {

					activated = change.flag;
					changed = true;
				}

				continue;
			}

			if (!activated) {
				logger.error(
					"Refusing to edit a stronghold the player does not own yet.%n");
				continue;
			}

			switch (change.kind) {
				case ADD_UPGRADE:
					changed |= buildUpgrade(deserialized.get(), stronghold.get(), change.name);
					break;

				case REMOVE_UPGRADE:
					changed |= destroyUpgrade(deserialized.get(), stronghold.get(), change.name);
					break;

				case SET_NUMBER:
					changed |= setEntry(stronghold.get(), change.name, change.number);
					break;

				case SET_FLAG:
					changed |= setEntry(stronghold.get(), change.name, change.flag);
					break;

				default:
					break;
			}
		}

		if (!changed) {
			return false;
		}

		// serializeAll appends and seeks to the end, because the save pipeline
		// normally writes a file that does not exist yet. Editing in place
		// means clearing the old contents first, or the new stream lands after
		// the old one and the game reads the stale copy.
		final File mobileObjectsFile = new File(saveDirectory, "MobileObjects.save");
		if (!mobileObjectsFile.delete() || !mobileObjectsFile.createNewFile()) {
			logger.error(
				"Unable to replace '%s'.%n", mobileObjectsFile.getAbsolutePath());

			return false;
		}

		deserialized.get().reserialize(mobileObjectsFile);
		return true;
	}

	// Mirrors Stronghold.CompleteBuildingUpgrade: record it, pay the
	// adjustments, raise the global.
	private boolean buildUpgrade (
		final DeserializedPackets packets
		, final DictionaryProperty stronghold
		, final String key) {

		final Optional<StrongholdCatalog.Upgrade> upgrade =
			StrongholdCatalog.getInstance().lookup(key);

		if (!upgrade.isPresent()) {
			logger.error("No such buildable upgrade: '%s'.%n", key);
			return false;
		}

		final Optional<StrongholdUpgrade.Type> type = upgrade.get().type();
		final Optional<CollectionProperty> list = upgradeList(stronghold);
		if (!type.isPresent() || !list.isPresent()) {
			return false;
		}

		// HasUpgrade() guards the game's own call; without the same guard the
		// adjustments would be paid twice for one upgrade.
		if (contains(list.get(), type.get())) {
			return false;
		}

		list.get().items.add(upgradeEntry(type.get()));

		adjust(stronghold, "Prestige", upgrade.get().prestige);
		adjust(stronghold, "Security", upgrade.get().security);
		setUpgradeGlobal(packets, upgrade.get(), 1);
		return true;
	}

	// Mirrors Stronghold.DestroyUpgrade, which is the same steps in reverse.
	private boolean destroyUpgrade (
		final DeserializedPackets packets
		, final DictionaryProperty stronghold
		, final String key) {

		final Optional<StrongholdCatalog.Upgrade> upgrade =
			StrongholdCatalog.getInstance().lookup(key);

		if (!upgrade.isPresent()) {
			logger.error("No such buildable upgrade: '%s'.%n", key);
			return false;
		}

		final Optional<StrongholdUpgrade.Type> type = upgrade.get().type();
		final Optional<CollectionProperty> list = upgradeList(stronghold);
		if (!type.isPresent() || !list.isPresent()) {
			return false;
		}

		boolean removed = false;
		for (int i = list.get().items.size() - 1; i >= 0; i--) {
			if (type.get().equals(list.get().items.get(i).obj)) {
				list.get().items.remove(i);
				removed = true;
			}
		}

		if (!removed) {
			return false;
		}

		adjust(stronghold, "Prestige", -upgrade.get().prestige);
		adjust(stronghold, "Security", -upgrade.get().security);
		setUpgradeGlobal(packets, upgrade.get(), 0);
		return true;
	}

	// A list entry the serializer will write back as the enum's ordinal. Both
	// value and obj have to carry it: the write path reads one and the property
	// tree the other.
	private static SimpleProperty upgradeEntry (final StrongholdUpgrade.Type type) {
		final SimpleProperty entry = new SimpleProperty(
			null, new TypePair(StrongholdUpgrade.Type.class, TYPE_UPGRADE));

		entry.value = type;
		entry.obj = type;
		return entry;
	}

	private static boolean contains (
		final CollectionProperty list, final StrongholdUpgrade.Type type) {

		for (final Property item : list.items) {
			if (type.equals(item.obj)) {
				return true;
			}
		}

		return false;
	}

	private static void adjust (
		final DictionaryProperty stronghold, final String variable, final int delta) {

		if (delta == 0) {
			return;
		}

		final Optional<Property> entry = stronghold.findEntry(variable);
		if (!entry.isPresent() || !(entry.get().obj instanceof Integer)) {
			logger.error("Stronghold has no '%s' to adjust.%n", variable);
			return;
		}

		Property.update(entry.get(), ((Integer) entry.get().obj) + delta);
	}

	private static boolean setEntry (
		final DictionaryProperty stronghold, final String variable, final Object value) {

		final Optional<Property> entry = stronghold.findEntry(variable);
		if (!entry.isPresent()) {
			logger.error("Stronghold has no '%s' to set.%n", variable);
			return false;
		}

		if (value.equals(entry.get().obj)) {
			return false;
		}

		return Property.update(entry.get(), value);
	}

	/**
	 * "This global is set to 1 when the upgrade is built and back to 0 when it
	 * is destroyed." Only the Eastern Barbican actually names one, but the
	 * world keys off it, so leaving it stale would show an upgrade the area
	 * does not reflect.
	 */
	private static void setUpgradeGlobal (
		final DeserializedPackets packets
		, final StrongholdCatalog.Upgrade upgrade
		, final int value) {

		if (upgrade.global == null || upgrade.global.isEmpty()) {
			return;
		}

		final Optional<DictionaryProperty> globals = findGlobals(packets);
		if (!globals.isPresent()) {
			logger.error("Save has no GlobalVariables to update.%n");
			return;
		}

		final Optional<Property> entry = globals.get().findEntry(upgrade.global);
		if (!entry.isPresent()) {
			logger.error(
				"Global '%s' is not in this save; leaving it alone.%n", upgrade.global);

			return;
		}

		Property.update(entry.get(), value);
	}

	private Optional<DeserializedPackets> read () throws IOException {
		return Environment.getInstance().factory().packetDeserializer()
			.forFile(new File(saveDirectory, "MobileObjects.save")).deserialize();
	}

	private static Optional<CollectionProperty> upgradeList (
		final DictionaryProperty stronghold) {

		final Optional<Property> entry = stronghold.findEntry("m_upgradesBuilt");
		if (!entry.isPresent() || !(entry.get() instanceof CollectionProperty)) {
			logger.error("Stronghold has no m_upgradesBuilt list.%n");
			return Optional.empty();
		}

		return Optional.of((CollectionProperty) entry.get());
	}

	private static Optional<DictionaryProperty> findStronghold (
		final DeserializedPackets packets) {

		return findOnGlobal(packets, "Stronghold")
			.flatMap(c -> c.<DictionaryProperty>findProperty("Variables"));
	}

	private static Optional<DictionaryProperty> findGlobals (
		final DeserializedPackets packets) {

		return findOnGlobal(packets, "GlobalVariables")
			.flatMap(c -> c.<DictionaryProperty>findProperty("Variables"))
			.flatMap(v -> v.<DictionaryProperty>findEntry("m_data"));
	}

	private static Optional<ComplexProperty> findOnGlobal (
		final DeserializedPackets packets, final String component) {

		for (final Property property : packets.getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)
				|| !(property instanceof ComplexProperty)) {

				continue;
			}

			final String name = ((ObjectPersistencePacket) property.obj).ObjectName;
			if (name == null || !name.startsWith("InGameGlobal")) {
				continue;
			}

			final Optional<ComplexProperty> found = ((ComplexProperty) property)
				.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
				.flatMap(c -> EKUtils.findSubComponent(c, component));

			if (found.isPresent()) {
				return found;
			}
		}

		return Optional.empty();
	}
}
