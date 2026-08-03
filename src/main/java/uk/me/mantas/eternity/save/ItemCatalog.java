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

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real item names and inventory icons, keyed by prefab file name.
 *
 * <p>A save only ever stores a prefab path like
 * {@code Assets/Data/Prefabs/Items/Rings/Ring_PREORDER_Gauns_Pledge.prefab}.
 * The display name and icon live inside the game's Unity asset bundles —
 * every item has its own bundle under
 * {@code assetbundles/prefabs/objectbundle/}, named exactly like the lowercased
 * prefab file name, holding an Item component whose {@code DisplayName} points
 * into a {@code *.stringtable} and whose {@code IconTexture} is the 42x42
 * inventory icon. Parsing Unity bundles from Java 8 isn't realistic, so that
 * data is extracted once by a helper script into a plain
 * {@code catalog.json} + {@code icons/} directory, which this class reads.
 *
 * <p>Everything is optional: with no catalog present the editor still works and
 * simply falls back to a prettified file name and no icon.
 */
public class ItemCatalog {
	private static final Logger logger = Logger.getLogger(ItemCatalog.class);
	private static ItemCatalog instance = null;

	private final Map<String, Entry> entries = new HashMap<>();
	private final Map<String, String> iconCache = new HashMap<>();
	private final File iconDirectory;

	/** One catalogued item. All fields except {@link #name} may be absent. */
	public static final class Entry {
		public final String name;
		public final String icon;
		public final int maxStack;
		public final boolean quest;
		public final List<String> slots;
		/** "soulbound", "unique", "fine", or empty — drives the tile tint. */
		public final String quality;
		/** UIInventoryFilter.ItemFilterType, for the stash category filters. */
		public final int filter;
		/** Classes allowed to equip this; empty means anyone can. */
		public final List<String> classes;
		/** Prefab path as a save records it in InventoryItem.BaseItem. */
		public final String path;

		Entry (
			final String name
			, final String icon
			, final int maxStack
			, final boolean quest
			, final List<String> slots
			, final String quality
			, final int filter
			, final List<String> classes
			, final String path) {

			this.name = name;
			this.icon = icon;
			this.maxStack = maxStack;
			this.quest = quest;
			this.slots = slots;
			this.quality = quality;
			this.filter = filter;
			this.classes = classes;
			this.path = path;
		}
	}

	public static synchronized ItemCatalog getInstance () {
		if (instance == null) {
			instance = new ItemCatalog(locateCatalogDirectory());
		}

		return instance;
	}

	/** Testing seam — forces the next getInstance() to re-read from disk. */
	public static synchronized void reset () {
		instance = null;
	}

	/**
	 * Testing seam — pretends no catalog is installed. Without this, whether a
	 * test machine happens to have a game install would change the names and
	 * icons that extraction produces, and fixture comparisons would only pass
	 * on some machines.
	 */
	public static synchronized void useNoCatalog () {
		instance = new ItemCatalog(Optional.empty(), true);
	}

	private ItemCatalog (final Optional<File> directory) {
		this(directory, false);
	}

	private ItemCatalog (final Optional<File> directory, final boolean quiet) {
		iconDirectory = directory.map(d -> new File(d, "icons")).orElse(null);

		if (!directory.isPresent()) {
			if (!quiet) {
				logger.info(
					"No item catalog found; falling back to prettified file names.%n");
			}

			return;
		}

		load(new File(directory.get(), "catalog.json"));
	}

	private void load (final File catalogFile) {
		try (final Reader reader = new BufferedReader(
			new InputStreamReader(new FileInputStream(catalogFile), "UTF-8"))) {

			final JSONObject json = new JSONObject(new JSONTokener(reader));
			for (final String key : json.keySet()) {
				final JSONObject item = json.optJSONObject(key);
				if (item == null) {
					continue;
				}

				entries.put(key.toLowerCase(), new Entry(
					item.optString("name", "")
					, item.optString("icon", "")
					, item.optInt("maxStack", 1)
					, item.optInt("quest", 0) != 0
					, stringList(item.optJSONArray("slots"))
					, item.optString("quality", "")
					, item.optInt("filter", 0)
					, stringList(item.optJSONArray("classes"))
					, item.optString("path", "")));
			}

			logger.info("Loaded %d catalogued items.%n", entries.size());
		} catch (final Exception e) {
			logger.error(
				"Unable to read item catalog '%s': %s%n"
				, catalogFile.getAbsolutePath(), e.getMessage());
		}
	}

	private static List<String> stringList (final JSONArray array) {
		final List<String> values = new ArrayList<>();
		if (array != null) {
			for (int i = 0; i < array.length(); i++) {
				values.add(array.getString(i));
			}
		}

		return Collections.unmodifiableList(values);
	}

	// The catalog is derived from the user's own game install, so it lives
	// outside the source tree. Prefer an explicit setting, then the usual
	// spots relative to wherever the app was launched from.
	private static Optional<File> locateCatalogDirectory () {
		final List<File> candidates = new ArrayList<>();

		// Settings are mocked out in tests, so treat anything here as absent
		// rather than letting it break opening a save.
		try {
			final String configured =
				Settings.getInstance().json.optString("itemDataLocation", "");

			if (configured != null && !configured.isEmpty()) {
				candidates.add(new File(configured));
			}
		} catch (final Exception ignored) {
			// No usable setting; fall through to the conventional locations.
		}

		final File workingDirectory = new File(System.getProperty("user.dir", "."));
		candidates.add(new File(workingDirectory, "itemdata"));

		final File parent = workingDirectory.getAbsoluteFile().getParentFile();
		if (parent != null) {
			candidates.add(new File(parent, "itemdata"));
		}

		for (final File candidate : candidates) {
			if (candidate != null && new File(candidate, "catalog.json").isFile()) {
				return Optional.of(candidate);
			}
		}

		return Optional.empty();
	}

	/**
	 * Turns the prefab path stored in a save into its catalog key:
	 * {@code Assets/.../Ring_PREORDER_Gauns_Pledge.prefab} -&gt;
	 * {@code ring_preorder_gauns_pledge}.
	 */
	public static String keyOf (final String baseItem) {
		if (baseItem == null || baseItem.isEmpty()) {
			return "";
		}

		final String fileName = baseItem.substring(baseItem.lastIndexOf('/') + 1);
		final String withoutExtension = fileName.endsWith(".prefab")
			? fileName.substring(0, fileName.length() - ".prefab".length())
			: fileName;

		return withoutExtension.toLowerCase();
	}

	public Optional<Entry> lookup (final String baseItem) {
		return Optional.ofNullable(entries.get(keyOf(baseItem)));
	}

	public int size () {
		return entries.size();
	}

	/**
	 * Catalogued items matching a name fragment and/or an
	 * {@code ItemFilterType}, sorted by display name so paging is stable.
	 */
	public List<Map.Entry<String, Entry>> search (final String needle, final int filter) {
		final List<Map.Entry<String, Entry>> matches = new ArrayList<>();

		for (final Map.Entry<String, Entry> candidate : entries.entrySet()) {
			final Entry entry = candidate.getValue();

			if (filter != 0 && entry.filter != filter) {
				continue;
			}

			if (needle != null && !needle.isEmpty()
				&& !entry.name.toLowerCase().contains(needle)
				&& !candidate.getKey().contains(needle)) {

				continue;
			}

			matches.add(candidate);
		}

		matches.sort((a, b) -> {
			final int byName = a.getValue().name.compareToIgnoreCase(b.getValue().name);
			return byName != 0 ? byName : a.getKey().compareTo(b.getKey());
		});

		return matches;
	}

	/**
	 * Base64 PNG for an icon file name, or an empty string when the catalog or
	 * that particular icon is missing. Icons repeat heavily across a party's
	 * inventories, so results are cached for the life of the process.
	 */
	public String iconData (final String iconFile) {
		if (iconFile == null || iconFile.isEmpty() || iconDirectory == null) {
			return "";
		}

		final String cached = iconCache.get(iconFile);
		if (cached != null) {
			return cached;
		}

		String encoded = "";
		final File file = new File(iconDirectory, iconFile);
		if (file.isFile()) {
			try {
				encoded = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
			} catch (final IOException e) {
				logger.error(
					"Unable to read icon '%s': %s%n", file.getAbsolutePath(), e.getMessage());
			}
		}

		iconCache.put(iconFile, encoded);
		return encoded;
	}
}
