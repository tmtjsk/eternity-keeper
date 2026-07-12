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

package uk.me.mantas.eternity.tests.serializer;

import com.google.common.io.RecursiveDeleteOption;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.CharacterStats;
import uk.me.mantas.eternity.serializer.*;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class SerializerTest extends TestHarness {

	@Test
	public void serializesSaveFile () throws URISyntaxException, IOException {
		final File saveFile = new File(getClass().getResource("/MobileObjects.save").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		try {
			EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.PRESERVE);
			assertFileContentsEquals(saveFile, saveOutputFile);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void serializesLevelFile () throws URISyntaxException, IOException {
		final File saveFile = new File(getClass().getResource("/SerializerTest/windowStoreSave/AR_0701_Encampment.lvl").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		try {
			EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.PRESERVE);
			assertFileContentsEquals(saveFile, saveOutputFile);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void serializesWindowsStoreToSteamSaveFile () throws URISyntaxException, IOException {
		final File saveFile = new File(getClass().getResource("/SerializerTest/windowStoreSave/MobileObjects.save").toURI());
		final File saveOutputFile = Files.createTempFile(PREFIX, null).toFile();

		try {
			EKUtils.reserializeFile(saveFile, saveOutputFile, SerializerFormat.UNITY_2017);

			final File expectedSaveFile = new File(getClass().getResource("/SerializerTest/windowStoreSaveConverted/MobileObjects.save").toURI());
			assertFileContentsEquals(expectedSaveFile, saveOutputFile);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void convertsWindowsStoreSaveFilesToSteam () throws URISyntaxException, IOException {
		final File inputDir = new File(getClass().getResource("/SerializerTest/windowStoreSave/").toURI());

		final Optional<File> outputDir = EKUtils.createTempDir(PREFIX);
		assertTrue(outputDir.isPresent());
		final Path outputDirPath = outputDir.get().toPath();

		try {
			EKUtils.convertWindowsStoreToSteamSaveFiles(inputDir, outputDir.get());

			// check the result
			// TODO: factor out to EKUtils.compareDirectoryContents() or similar. Or does guava have this already?
			final List<File> outputFiles = Arrays.asList(outputDirPath.toFile().listFiles());

			final File expectedDir = new File(getClass().getResource("/SerializerTest/windowStoreSaveConverted/").toURI());
			final List<String> expectedFilenames = Arrays.asList(expectedDir.list());

			assertEquals("number of files", expectedFilenames.size(), outputFiles.size());

			for (File outputFile : outputFiles) {
				String outputFilename = outputFile.getName();
				assertTrue(expectedFilenames.contains(outputFilename));

				final File expectedFile = new File(expectedDir, outputFilename);
				assertFileContentsEquals(expectedFile, outputFile);
			}

		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	// Newer versions of the game store enum values that this port's enum
	// definitions don't know about (e.g. CharacterStats+Class #53,
	// StatusEffect+ModifiedStat #282 in 2025-era saves). Unknown values must
	// survive a deserialize/reserialize round trip instead of being nulled.
	@Test
	public void preservesUnknownEnumValues () throws IOException {
		final int unknownValue = 999;
		final File file = Files.createTempFile(PREFIX, null).toFile();

		final SimpleProperty count = new SimpleProperty(
			"Root", new TypePair(int.class, "System.Int32"));
		count.value = 1;
		count.obj = 1;

		final SimpleProperty enumProperty = new SimpleProperty(
			"Root", new TypePair(CharacterStats.Class.class, "CharacterStats+Class"));
		enumProperty.value = unknownValue;
		enumProperty.obj = unknownValue;

		final SharpSerializer serializer = new SharpSerializer(file.getAbsolutePath());
		serializer.serialize(count);
		serializer.serialize(enumProperty);

		// The raw value must be preserved in memory...
		final SharpSerializer deserializer = new SharpSerializer(file.getAbsolutePath());
		assertTrue(deserializer.deserialize().isPresent()); // skip count
		final Optional<Property> read = deserializer.deserialize();
		assertTrue(read.isPresent());
		assertEquals(unknownValue, read.get().obj);

		// ...and the file must round trip byte-identically.
		final File output = Files.createTempFile(PREFIX, null).toFile();
		EKUtils.reserializeFile(file, output, SerializerFormat.PRESERVE);
		assertFileContentsEquals(file, output);
	}

	public static void assertFileContentsEquals(File expectedFile, File actualFile) throws IOException {
		try {
			final byte[] actual = FileUtils.readFileToByteArray(actualFile);
			final byte[] expected = FileUtils.readFileToByteArray(expectedFile);

			assertArrayEquals(actualFile.getName() + " contents", expected, actual);
		} catch (final Exception e) {
			throw e;
		}
	}
}
