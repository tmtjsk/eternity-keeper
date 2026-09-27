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

import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.MultiDimensionalArrayProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gives objects new IDs, everywhere a set of packets names them.
 *
 * <p>Objects brought into a save from another -- a companion transplanted from
 * a donor, a character imported from a file -- keep the IDs they had there. If
 * the target already holds an object under one of those, both exist twice over
 * and the game drops both, so the newcomers get fresh ones. An ID is written in
 * two forms, both rewritten: as a UUID (an {@code InstanceID}, the item lists,
 * the equipment slots, a GUIDLink) and as text (an {@code ObjectID}). IDs are
 * matched ignoring case, since the game writes them in whatever case it
 * formatted them in.
 */
public final class GuidRemap {
	private GuidRemap () {}

	/**
	 * Rewrites every occurrence of the IDs {@code remap} names, in the given
	 * packets only.
	 *
	 * @return how many values were rewritten
	 */
	public static int rewrite (final Collection<Property> packets, final Map<String, UUID> remap) {
		final Map<String, UUID> byKey = new HashMap<>();
		remap.forEach((id, fresh) -> byKey.put(id.toLowerCase(), fresh));

		// Flat-copied reference stubs share child lists, so an identity set
		// guards against rewriting (or counting) one twice.
		final Set<Property> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		int rewrites = 0;
		for (final Property packet : packets) {
			rewrites += rewrite(packet, byKey, visited);
		}

		return rewrites;
	}

	private static int rewrite (
		final Property property, final Map<String, UUID> remap, final Set<Property> visited) {

		if (property == null || !visited.add(property)) {
			return 0;
		}

		if (property instanceof SimpleProperty) {
			final Object value = ((SimpleProperty) property).obj;
			if (value instanceof UUID && remap.containsKey(value.toString().toLowerCase())) {
				Property.update(property, remap.get(value.toString().toLowerCase()));
				return 1;
			}

			if (value instanceof String && remap.containsKey(((String) value).toLowerCase())) {
				Property.update(property, remap.get(((String) value).toLowerCase()).toString());
				return 1;
			}

			return 0;
		}

		int rewrites = 0;
		if (property instanceof SingleDimensionalArrayProperty) {
			for (final Object item : ((SingleDimensionalArrayProperty) property).items) {
				if (item instanceof Property) {
					rewrites += rewrite((Property) item, remap, visited);
				}
			}
		}

		if (property instanceof MultiDimensionalArrayProperty) {
			for (final MultiDimensionalArrayProperty.MultiDimensionalArrayItem item
				: ((MultiDimensionalArrayProperty) property).items) {

				if (item != null) {
					rewrites += rewrite(item.value, remap, visited);
				}
			}
		}

		if (property instanceof CollectionProperty) {
			for (final Property item : ((CollectionProperty) property).items) {
				rewrites += rewrite(item, remap, visited);
			}
		}

		if (property instanceof DictionaryProperty) {
			for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) property).items) {
				if (entry != null) {
					rewrites += rewrite(entry.getKey(), remap, visited);
					rewrites += rewrite(entry.getValue(), remap, visited);
				}
			}
		}

		if (property instanceof ComplexProperty) {
			for (final Property sub : ((ComplexProperty) property).properties) {
				rewrites += rewrite(sub, remap, visited);
			}
		}

		return rewrites;
	}
}
