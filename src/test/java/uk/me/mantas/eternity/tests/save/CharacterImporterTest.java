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


package uk.me.mantas.eternity.tests.save;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.CharacterExporter;
import uk.me.mantas.eternity.save.CharacterImporter;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.ExposedClass;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.game.UnityEngine.Vector3;

public class CharacterImporterTest extends TestHarness {
	private static final String PLAYER_GUID = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String COMPANION_GUID = "b1a7e809-0000-0000-0000-000000000000";

	private File resourceDirectory () throws URISyntaxException {
		return new File(getClass().getResource("/").toURI());
	}

	private File tempDir () {
		final Optional<File> tempDir = EKUtils.createTempDir(PREFIX);
		assertTrue(tempDir.isPresent());
		return tempDir.get();
	}

	private String requestJSON (final File saveDirectory) {
		final JSONObject request = new JSONObject();
		request.put("oldSave", saveDirectory.getAbsolutePath());
		request.put("savedYet", false);
		return request.toString();
	}

	private File exportCompanionChr (final File destinationDir)
		throws IOException
		, URISyntaxException {

		final File chrFile = new File(destinationDir, "companion.chr");
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, COMPANION_GUID
			, chrFile.getAbsolutePath());

		assertTrue(exporter.export());
		return chrFile;
	}

	@Test(expected = FileNotFoundException.class)
	public void constructorThrowsWhenSaveDirectoryMissing ()
		throws IOException
		, URISyntaxException {

		final File chrFile = exportCompanionChr(tempDir());
		new CharacterImporter(
			requestJSON(new File("404"))
			, chrFile.getAbsolutePath());
	}

	@Test(expected = FileNotFoundException.class)
	public void constructorThrowsWhenChrFileMissing ()
		throws IOException
		, URISyntaxException {

		new CharacterImporter(requestJSON(resourceDirectory()), "404.chr");
	}

	@Test
	public void importsCompanionIntoSave ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(
			new File(resourceDirectory(), "MobileObjects.save")
			, saveDir);

		// Record the pre-import state of the target save.
		final DeserializedPackets original =
			deserialize(new File(saveDir, "MobileObjects.save"));
		final int originalCount = (int) original.getCount().obj;
		final ObjectPersistencePacket player =
			findByObjectID(original.getPackets(), PLAYER_GUID);
		final String companionName =
			findByObjectID(original.getPackets(), COMPANION_GUID).ObjectName;

		final File chrFile = exportCompanionChr(workingDir);
		final DeserializedPackets chrContents = deserialize(chrFile);
		final int chrCount = (int) chrContents.getCount().obj;

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());

		assertTrue(importer.importCharacter());

		// The modified save must contain every original object plus every
		// object from the CHR file and declare the right count.
		final DeserializedPackets modified =
			deserialize(new File(saveDir, "MobileObjects.save"));
		final int modifiedCount = (int) modified.getCount().obj;

		assertEquals(originalCount + chrCount, modifiedCount);
		assertEquals(modifiedCount, modified.getPackets().size());

		// The imported companion coexists with the original: same
		// ObjectName, freshly generated GUID.
		final List<ObjectPersistencePacket> companions = modified.getPackets().stream()
			.map(property -> (ObjectPersistencePacket) property.obj)
			.filter(packet -> companionName.equals(packet.ObjectName))
			.collect(Collectors.toList());

		assertEquals(2, companions.size());

		final List<ObjectPersistencePacket> imported = companions.stream()
			.filter(packet -> !COMPANION_GUID.equals(packet.ObjectID))
			.collect(Collectors.toList());

		assertEquals(1, imported.size());
		final ObjectPersistencePacket importedPacket = imported.get(0);

		// The regenerated ObjectID must be a well-formed, distinct UUID.
		assertEquals(
			importedPacket.ObjectID
			, UUID.fromString(importedPacket.ObjectID).toString());

		// The imported character must have been anchored next to the player.
		assertEquals(player.LevelName, importedPacket.LevelName);
		assertEquals(player.Location.x + 1, importedPacket.Location.x, 1e-4);
		assertEquals(player.Location.y + 1, importedPacket.Location.y, 1e-4);
		assertEquals(player.Location.z, importedPacket.Location.z, 1e-4);
	}

	@Test
	public void detectsMainCharacterConflict ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File chrFile = new File(workingDir, "player.chr");
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, PLAYER_GUID
			, chrFile.getAbsolutePath());
		assertTrue(exporter.export());

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(resourceDirectory()), chrFile.getAbsolutePath());

		final Optional<CharacterImporter.ImportConflict> conflict = importer.detectConflict();
		assertTrue(conflict.isPresent());
		assertTrue(conflict.get().mainCharacter);
		assertEquals("Elwyn", conflict.get().characterName);
	}

	@Test
	public void detectsCompanionConflict ()
		throws IOException
		, URISyntaxException {

		final File chrFile = exportCompanionChr(tempDir());
		final CharacterImporter importer =
			new CharacterImporter(requestJSON(resourceDirectory()), chrFile.getAbsolutePath());

		final Optional<CharacterImporter.ImportConflict> conflict = importer.detectConflict();
		assertTrue(conflict.isPresent());
		assertFalse(conflict.get().mainCharacter);
		assertEquals("Calisca", conflict.get().characterName);
	}

	@Test
	public void noConflictWhenCharacterNotInTargetSave ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File chrFile = exportCompanionChr(workingDir);

		// Build a target save that does not contain the companion.
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		final DeserializedPackets source =
			deserialize(new File(resourceDirectory(), "MobileObjects.save"));

		final String companionName =
			findByObjectID(source.getPackets(), COMPANION_GUID).ObjectName;

		final List<Property> withoutCompanion = source.getPackets().stream()
			.filter(property -> {
				final ObjectPersistencePacket packet =
					(ObjectPersistencePacket) property.obj;
				return !COMPANION_GUID.equals(packet.ObjectID)
					&& !companionName.equals(packet.Parent);
			})
			.collect(Collectors.toList());

		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		assertTrue(mobileObjects.createNewFile());
		Property.update(source.getCount(), withoutCompanion.size());
		source.setPackets(withoutCompanion);
		source.reserialize(mobileObjects);

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());

		assertFalse(importer.detectConflict().isPresent());
	}

	@Test
	public void overwriteReplacesExistingCompanion ()
		throws IOException
		, URISyntaxException {

		assertOverwrite(COMPANION_GUID);
	}

	@Test
	public void overwriteReplacesExistingMainCharacter ()
		throws IOException
		, URISyntaxException {

		assertOverwrite(PLAYER_GUID);
	}

	private void assertOverwrite (final String guid)
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(
			new File(resourceDirectory(), "MobileObjects.save")
			, saveDir);

		final DeserializedPackets original =
			deserialize(new File(saveDir, "MobileObjects.save"));
		final int originalCount = (int) original.getCount().obj;
		final ObjectPersistencePacket existing =
			findByObjectID(original.getPackets(), guid);
		final String objectName = existing.ObjectName;
		final Vector3 originalLocation = existing.Location;
		final String originalLevel = existing.LevelName;

		final File chrFile = new File(workingDir, "subject.chr");
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, guid
			, chrFile.getAbsolutePath());
		assertTrue(exporter.export());

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());

		assertTrue(importer.overwriteCharacter());

		// Exporting from and overwriting into the same save replaces the
		// character's packets with an identical set so the count is stable.
		final DeserializedPackets modified =
			deserialize(new File(saveDir, "MobileObjects.save"));

		assertEquals(originalCount, (int) modified.getCount().obj);
		assertEquals(originalCount, modified.getPackets().size());

		final List<ObjectPersistencePacket> matches = modified.getPackets().stream()
			.map(property -> (ObjectPersistencePacket) property.obj)
			.filter(packet -> objectName.equals(packet.ObjectName))
			.collect(Collectors.toList());

		assertEquals(1, matches.size());
		final ObjectPersistencePacket overwritten = matches.get(0);

		// Identity is preserved, no GUID regeneration on overwrite.
		assertEquals(guid, overwritten.ObjectID);

		// The character takes the old character's exact position.
		assertEquals(originalLevel, overwritten.LevelName);
		assertEquals(originalLocation.x, overwritten.Location.x, 1e-4);
		assertEquals(originalLocation.y, overwritten.Location.y, 1e-4);
		assertEquals(originalLocation.z, overwritten.Location.z, 1e-4);
	}

	@Test
	public void importReturnsFalseWhenChrContainsNoCharacter ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(
			new File(resourceDirectory(), "MobileObjects.save")
			, saveDir);

		// Fabricate a CHR file whose objects are not characters.
		final File chrFile = new File(workingDir, "notACharacter.chr");
		final DeserializedPackets source =
			deserialize(new File(saveDir, "MobileObjects.save"));

		final List<Property> nonCharacters = source.getPackets().stream()
			.filter(property -> {
				final String name =
					((ObjectPersistencePacket) property.obj).ObjectName;
				return name != null
					&& !name.startsWith("Player_")
					&& !name.startsWith("Companion_");
			})
			.limit(1)
			.collect(Collectors.toList());

		assertEquals(1, nonCharacters.size());
		assertTrue(chrFile.createNewFile());
		Property.update(source.getCount(), 1);
		source.setPackets(nonCharacters);
		source.reserialize(chrFile);

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());

		assertFalse(importer.importCharacter());
	}

	@Test
	public void findCharacterAndAnchorPointTolerateHostileData ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File chrFile = exportCompanionChr(workingDir);
		final CharacterImporter importer =
			new CharacterImporter(requestJSON(resourceDirectory()), chrFile.getAbsolutePath());

		final Property nullName = packetProperty(null, "some-guid", null);
		final Property garbage = new ComplexProperty("Root", null);
		garbage.obj = "not a packet";
		final Property companion =
			packetProperty("Companion_Test(Clone)", "companion-guid", "");
		final Property player = packetProperty("Player_Test(Clone)", "player-guid", "");

		final List<Property> objects = new ArrayList<>();
		objects.add(nullName);
		objects.add(garbage);
		objects.add(companion);
		objects.add(player);

		final ExposedClass exposedImporter = expose(importer);
		final Map<Object, Class> argMap = new LinkedHashMap<>();
		argMap.put(objects, List.class);

		final Optional<Property> character =
			exposedImporter.call("findCharacter", argMap);

		assertTrue(character.isPresent());
		assertSame(companion, character.get());

		final Optional<ObjectPersistencePacket> anchor =
			exposedImporter.call("findAnchorPoint", argMap);

		assertTrue(anchor.isPresent());
		assertSame(player.obj, anchor.get());
	}

	private DeserializedPackets deserialize (final File file)
		throws FileNotFoundException {

		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(file).deserialize();

		assertTrue(deserialized.isPresent());
		return deserialized.get();
	}

	private ObjectPersistencePacket findByObjectID (
		final List<Property> packets
		, final String objectID) {

		final Optional<ObjectPersistencePacket> found = packets.stream()
			.map(property -> (ObjectPersistencePacket) property.obj)
			.filter(packet -> objectID.equals(packet.ObjectID))
			.findFirst();

		assertTrue(found.isPresent());
		return found.get();
	}

	private Property packetProperty (
		final String objectName
		, final String objectID
		, final String parent) {

		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		packet.ObjectName = objectName;
		packet.ObjectID = objectID;
		packet.Parent = parent;

		final Property property = new ComplexProperty("Root", null);
		property.obj = packet;
		return property;
	}
}
