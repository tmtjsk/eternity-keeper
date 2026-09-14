/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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
import uk.me.mantas.eternity.serializer.properties.*;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * A new top-level object, made out of the shape of one already in the save.
 *
 * <p>A new item and a new ability are both built this way: the header fields
 * copied with their exact types and the object's own values written in, and a
 * few of the template's components copied across — everything else comes back
 * from the prefab when the game restores the object, which is exactly right
 * for a new one. {@code InstanceID.Guid} is always set to the new object's own
 * GUID, and every copied component is a copy: reusing the template's own
 * properties once made rewriting the new GUID rewrite the template's too, and
 * the game dropped both objects without a word.
 */
public final class PacketMint {
	private static final Logger logger = Logger.getLogger(PacketMint.class);

	private PacketMint () {}

	/** The header fields a new object has of its own. */
	public static final class Identity {
		final String objectName;
		final String objectID;
		final UUID guid;
		final String prefabResource;
		final String parent;
		final String levelName;

		/** @param levelName null keeps the template's */
		public Identity (
			final String objectName, final String objectID, final UUID guid
			, final String prefabResource, final String parent, final String levelName) {

			this.objectName = objectName;
			this.objectID = objectID;
			this.guid = guid;
			this.prefabResource = prefabResource;
			this.parent = parent;
			this.levelName = levelName;
		}
	}

	/** Which of the template's components the new object carries. */
	@FunctionalInterface
	public interface Keep {
		boolean test (String typeString, ComplexProperty component);
	}

	/**
	 * @param adjust given each copied component with its TypeString, once its
	 *               InstanceID GUID is written
	 * @return nothing when the template is not an object or no component was kept
	 */
	public static Optional<Property> mint (
		final Property template
		, final Identity identity
		, final Keep keep
		, final BiConsumer<String, ComplexProperty> adjust) {

		if (!(template instanceof ComplexProperty)) {
			return Optional.empty();
		}

		final ComplexProperty source = (ComplexProperty) template;
		final ComplexProperty packet = new ComplexProperty(source.name, source.type);
		String levelName = identity.levelName;

		for (final Property field : source.properties) {
			if (field.name == null) {
				continue;
			}

			if ("ComponentPackets".equals(field.name)) {
				final Optional<Property> components = components(field, identity.guid, keep, adjust);
				if (!components.isPresent()) {
					return Optional.empty();
				}

				packet.properties.add(components.get());
				continue;
			}

			if (!(field instanceof SimpleProperty)) {
				// Location and Rotation ride along unchanged: an item in a pack
				// is never placed, and an ability is moved onto its owner the
				// moment the game restores it.
				packet.properties.add(field);
				continue;
			}

			Object value = ((SimpleProperty) field).value;
			switch (field.name) {
				case "ObjectName":      value = identity.objectName; break;
				case "ObjectID":        value = identity.objectID; break;
				case "GUID":            value = identity.guid; break;
				case "PrefabResource":  value = identity.prefabResource; break;
				case "Parent":          value = identity.parent; break;
				case "LevelName":
					if (levelName == null) {
						levelName = value == null ? null : value.toString();
					} else {
						value = levelName;
					}
					break;
				default:                break;
			}

			final SimpleProperty copy = new SimpleProperty(field.name, field.type);
			copy.value = value;
			copy.obj = value;
			packet.properties.add(copy);
		}

		final ObjectPersistencePacket materialised = new ObjectPersistencePacket();
		materialised.ObjectName = identity.objectName;
		materialised.ObjectID = identity.objectID;
		materialised.GUID = identity.guid;
		materialised.PrefabResource = identity.prefabResource;
		materialised.Parent = identity.parent;
		materialised.LevelName = levelName;
		packet.obj = materialised;

		return Optional.of(packet);
	}

	private static Optional<Property> components (
		final Property template
		, final UUID guid
		, final Keep keep
		, final BiConsumer<String, ComplexProperty> adjust) {

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

			final String type = String.valueOf(((SimpleProperty) typeString.get()).value);
			if (!keep.test(type, component)) {
				continue;
			}

			final ComplexProperty copy = EKUtils.copyComponent(component);
			components.items.add(copy);

			if ("InstanceID".equals(type)) {
				copy.<DictionaryProperty>findProperty("Variables")
					.flatMap(variables -> variables.findEntry("Guid"))
					.ifPresent(entry -> {
						((SimpleProperty) entry).value = guid;
						entry.obj = guid;
					});
			}

			adjust.accept(type, copy);
		}

		if (components.items.isEmpty()) {
			logger.error("The template object had no component worth copying.%n");
			return Optional.empty();
		}

		return Optional.of(components);
	}
}
