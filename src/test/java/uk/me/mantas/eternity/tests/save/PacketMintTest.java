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

package uk.me.mantas.eternity.tests.save;

import org.junit.Test;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.PacketMint;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.*;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Making a new top-level object out of the shape of one already in the save,
 * which is how both a new item and a new ability come to exist.
 *
 * <p>The two managers each had their own copy of this, and the lesson both
 * copies carry is the one that only ever surfaced in-game: never reuse the
 * template's own properties, or rewriting the new object's GUID rewrites the
 * template's too and the game silently drops both.
 */
public class PacketMintTest extends TestHarness {
	private static final UUID GUID = UUID.fromString("0a0a0a0a-1111-2222-3333-444444444444");

	private static List<Property> packets () throws Exception {
		final File save = new File(PacketMintTest.class.getResource("/MobileObjects.save").toURI());
		final Optional<DeserializedPackets> read = new PacketDeserializer(save).deserialize();
		assertTrue(read.isPresent());
		return read.get().getPackets();
	}

	/** A packet with an InstanceID and at least one other component to leave out. */
	private static Property template (final List<Property> packets) {
		for (final Property packet : packets) {
			final ObjectPersistencePacket object = (ObjectPersistencePacket) packet.obj;
			final Set<String> types = new HashSet<>();
			for (final ComponentPersistencePacket component : object.ComponentPackets) {
				if (component != null) {
					types.add(component.TypeString);
				}
			}

			if (types.contains("InstanceID") && types.contains("Persistence") && types.size() > 2) {
				return packet;
			}
		}

		throw new AssertionError("the fixture has no packet to model on");
	}

	private static PacketMint.Identity identity (final String levelName) {
		return new PacketMint.Identity(
			"Ring_Of_Tests(Clone)", GUID.toString(), GUID
			, "Assets/Prefabs/Items/Ring_Of_Tests.prefab", "Player_Elwyn(Clone)", levelName);
	}

	private static Object header (final Property packet, final String name) {
		return ((ComplexProperty) packet).<SimpleProperty>findProperty(name)
			.map(field -> field.value).orElse(null);
	}

	private static List<String> componentTypes (final Property packet) {
		final List<String> types = new ArrayList<>();
		((ComplexProperty) packet).<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
			.ifPresent(array -> array.items.forEach(item -> types.add(String.valueOf(
				((ComplexProperty) item).<SimpleProperty>findProperty("TypeString").get().value))));

		return types;
	}

	private static Object instanceGuid (final Property packet) {
		final ComplexProperty components = (ComplexProperty) packet;
		for (final Object item : components.<SingleDimensionalArrayProperty>findProperty(
			"ComponentPackets").get().items) {

			final ComplexProperty component = (ComplexProperty) item;
			if ("InstanceID".equals(component.<SimpleProperty>findProperty("TypeString").get().value)) {
				return component.<DictionaryProperty>findProperty("Variables").get()
					.findEntry("Guid").map(entry -> ((SimpleProperty) entry).value).orElse(null);
			}
		}

		return null;
	}

	private static final PacketMint.Keep IDENTITY_ONLY =
		(type, component) -> "InstanceID".equals(type) || "Persistence".equals(type);

	@Test
	public void theHeaderIsTheNewObjectsOwn () throws Exception {
		final Property minted = PacketMint.mint(
			template(packets()), identity("AR_0001_Test"), IDENTITY_ONLY, (type, copy) -> {}).get();

		assertEquals("Ring_Of_Tests(Clone)", header(minted, "ObjectName"));
		assertEquals(GUID.toString(), header(minted, "ObjectID"));
		assertEquals("the GUID is a real UUID, not its text", GUID, header(minted, "GUID"));
		assertEquals("Assets/Prefabs/Items/Ring_Of_Tests.prefab", header(minted, "PrefabResource"));
		assertEquals("Player_Elwyn(Clone)", header(minted, "Parent"));
		assertEquals("AR_0001_Test", header(minted, "LevelName"));

		final ObjectPersistencePacket object = (ObjectPersistencePacket) minted.obj;
		assertEquals("Ring_Of_Tests(Clone)", object.ObjectName);
		assertEquals(GUID.toString(), object.ObjectID);
		assertEquals("Player_Elwyn(Clone)", object.Parent);
	}

	@Test
	public void noLevelNameKeepsTheTemplates () throws Exception {
		final Property template = template(packets());
		final Property minted = PacketMint.mint(template, identity(null), IDENTITY_ONLY, (t, c) -> {}).get();

		assertEquals(header(template, "LevelName"), header(minted, "LevelName"));
	}

	@Test
	public void onlyTheKeptComponentsComeAcrossAndInstanceIdNamesTheNewObject () throws Exception {
		final Property minted = PacketMint.mint(
			template(packets()), identity(null), IDENTITY_ONLY, (t, c) -> {}).get();

		assertEquals(new HashSet<>(Arrays.asList("InstanceID", "Persistence"))
			, new HashSet<>(componentTypes(minted)));
		assertEquals("InstanceID.Guid must be the object's own ObjectID", GUID, instanceGuid(minted));
	}

	@Test
	public void theTemplateIsNeverTouched () throws Exception {
		final Property template = template(packets());
		final Object guidBefore = instanceGuid(template);
		final Object nameBefore = header(template, "ObjectName");

		final Property minted = PacketMint.mint(template, identity(null), IDENTITY_ONLY
			, (type, copy) -> copy.<DictionaryProperty>findProperty("Variables")
				.ifPresent(variables -> variables.items.forEach(entry -> {
					if (entry.getValue() instanceof SimpleProperty) {
						((SimpleProperty) entry.getValue()).value = "changed";
					}
				}))).get();

		assertNotNull(minted);
		assertEquals(guidBefore, instanceGuid(template));
		assertEquals(nameBefore, header(template, "ObjectName"));
	}

	@Test
	public void theAdjustmentSeesEachKeptCopyByType () throws Exception {
		final List<String> seen = new ArrayList<>();
		PacketMint.mint(template(packets()), identity(null), IDENTITY_ONLY
			, (type, copy) -> seen.add(type));

		assertEquals(new HashSet<>(Arrays.asList("InstanceID", "Persistence")), new HashSet<>(seen));
	}

	@Test
	public void keepingNothingMintsNothing () throws Exception {
		assertFalse(PacketMint.mint(
			template(packets()), identity(null), (type, component) -> false, (t, c) -> {}).isPresent());
	}
}
