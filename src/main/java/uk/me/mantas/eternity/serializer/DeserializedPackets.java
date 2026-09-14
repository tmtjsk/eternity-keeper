/**
 * Eternity Keeper, a Pillars of Eternity save game editor.
 * Copyright (C) 2015 the authors.
 * <p>
 * Eternity Keeper is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * Eternity Keeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package uk.me.mantas.eternity.serializer;

import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.SharpSerializerFactory;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class DeserializedPackets {
	private List<Property> packets;
	private final SimpleProperty count;
	private final SharpSerializerFactory sharpSerializer;

	public DeserializedPackets (final List<Property> packets, final SimpleProperty count) {
		this.packets = packets;
		this.count = count;
		sharpSerializer = Environment.getInstance().factory().sharpSerializer();
	}

	public List<Property> getPackets () {
		return packets;
	}

	public void setPackets (final List<Property> packets) {
		this.packets = packets;
	}

	public SimpleProperty getCount () {
		return count;
	}

	/**
	 * Serializes into {@code destinationFile}, which must already exist and is
	 * appended to (invariant 13) -- right for a file created a moment ago. To
	 * write over a save that is already there, use {@link #replace(File)}.
	 */
	public void reserialize (final File destinationFile) throws IOException {
		reserialize(destinationFile, SerializerFormat.PRESERVE);
	}

	public void reserialize (final File destinationFile, final SerializerFormat outputFormat)
		throws IOException {

		final SharpSerializer serializer =
			sharpSerializer.forFile(destinationFile.getAbsolutePath()).toFormat(outputFormat);

		serializer.serializeAll(count, packets);
	}

	/**
	 * Writes these packets over {@code target}, all or nothing.
	 *
	 * <p>The stream goes into an empty sibling file first and is then moved
	 * over the original, so the original is only ever touched by a completed
	 * write. Nine managers used to do this by hand as delete, create,
	 * serialize, and between them had three ways to lose an edit: a delete
	 * that failed (a scanner holding the file open) was logged and followed by
	 * an append, which left the stale stream first in the file; a write that
	 * failed half way left a truncated file behind a delete that had already
	 * succeeded; and neither was ever reported, because the write swallowed
	 * its own exception.
	 *
	 * @throws IOException when the write or the move fails. {@code target} is
	 *         then exactly as it was, and no sibling is left behind.
	 */
	public void replace (final File target) throws IOException {
		final File directory = target.getAbsoluteFile().getParentFile();
		final File writing = File.createTempFile(target.getName() + ".", ".writing", directory);

		try {
			reserialize(writing);

			if (writing.length() < 1) {
				throw new IOException("Nothing was written for " + target.getName());
			}

			try {
				Files.move(writing.toPath(), target.toPath()
					, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (final AtomicMoveNotSupportedException e) {
				Files.move(writing.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			if (writing.exists() && !writing.delete()) {
				writing.deleteOnExit();
			}
		}
	}
}
