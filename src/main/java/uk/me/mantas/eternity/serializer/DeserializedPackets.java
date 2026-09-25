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
import java.util.Optional;

public class DeserializedPackets {
	private List<Property> packets;
	private final SimpleProperty count;
	private final SharpSerializerFactory sharpSerializer;

	// What the read that made these found: the file, how many packets its
	// count promised and how many came back. Fixed when it was read, because
	// every writer sets the count to the packets it holds before writing,
	// which would make a short read look whole by the time it is written.
	private final String source;
	private final int declared;
	private final int read;

	/** Packets put together in memory: never read, so nothing is missing from them. */
	public DeserializedPackets (final List<Property> packets, final SimpleProperty count) {
		this(packets, count, null, packets.size());
	}

	/**
	 * What reading {@code source} gave back, whose leading count promised
	 * {@code declared} packets.
	 */
	DeserializedPackets (
		final List<Property> packets
		, final SimpleProperty count
		, final String source
		, final int declared) {

		this.packets = packets;
		this.count = count;
		this.source = source;
		this.declared = declared;
		this.read = packets.size();
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

	/** Whether every packet the file's count promised was read. */
	public boolean isWhole () {
		return read >= declared;
	}

	/**
	 * Why these packets must not be written, when they are only the start of
	 * the file they were read from.
	 */
	public Optional<ShortReadException> shortRead () {
		return isWhole()
			? Optional.empty()
			: Optional.of(new ShortReadException(source, declared, read));
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

		refuseIfShort();
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
	 * @throws ShortReadException when these packets are only the start of the
	 *         file they were read from; nothing is written at all.
	 */
	public void replace (final File target) throws IOException {
		refuseIfShort();
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

	// Whatever a caller has done with a short read since -- setting its count
	// to the packets it holds included -- it is not written.
	private void refuseIfShort () throws ShortReadException {
		final Optional<ShortReadException> shortRead = shortRead();
		if (shortRead.isPresent()) {
			throw shortRead.get();
		}
	}
}
