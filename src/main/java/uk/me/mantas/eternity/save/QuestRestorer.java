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
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.QuestTrackerBlob.Tracker;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.util.List;
import java.util.Optional;

/**
 * Un-fails a companion quest that the game auto-failed when the companion
 * died. Empirically (paired pre/post-death saves for all eleven companions),
 * death touches exactly one entry of the QuestManager.QuestTrackers blob —
 * the companion quest's tracker gets EndState set to its "died" end state and
 * Failed set to true — so restoring is the inverse patch. Durance is the one
 * exception: his death additionally fires his quest's "Durance Died" event
 * (bit 7 of the tracker's TriggeredEvents) and sets the progression global
 * nDuranceQuestState to its dedicated death value 8; both are reverted too.
 */
public class QuestRestorer {
	private static final Logger logger = Logger.getLogger(QuestRestorer.class);

	private static final String DURANCE_QUEST =
		"data/quests/companions/companion_qst_durance.quest";
	private static final String DURANCE_STATE_GLOBAL = "nDuranceQuestState";
	private static final int DURANCE_DEATH_EVENT = 7;
	private static final int DURANCE_DEATH_STATE = 8;

	// Durance's progression quest events and the nDuranceQuestState value each
	// one records, highest progress first (from companion_qst_durance.quest;
	// event 5 writes a different variable and event 7 is the death event).
	private static final int[][] DURANCE_EVENT_STATES =
		{{6, 7}, {4, 5}, {3, 4}, {2, 3}, {1, 2}, {0, 1}};

	private QuestRestorer () {}

	/**
	 * Restores the given quest in the packets of a save, if its tracker says
	 * it failed. Returns false only on structural surprises (no QuestManager,
	 * unparseable blob); an absent or unfailed tracker is a successful no-op.
	 */
	public static boolean restore (final List<Property> packets, final String questPath) {
		final Optional<Property> blobProperty = findQuestTrackers(packets);
		if (!blobProperty.isPresent()) {
			logger.error("Save has no QuestManager.QuestTrackers blob.%n");
			return false;
		}

		final byte[] raw = unbox((Byte[]) ((SimpleProperty) blobProperty.get()).value);
		final Optional<QuestTrackerBlob> blob = QuestTrackerBlob.parse(raw);
		if (!blob.isPresent()) {
			return false;
		}

		final Optional<Tracker> tracker = blob.get().tracker(questPath);
		if (!tracker.isPresent()) {
			logger.info("No tracker for quest '%s'; nothing to restore.%n", questPath);
			return true;
		}

		if (!tracker.get().failed()) {
			logger.info("Quest '%s' has not failed; leaving it as is.%n", questPath);
			return true;
		}

		final int failedEndState = tracker.get().endState();
		tracker.get().setEndState(-1);
		tracker.get().setFailed(false);

		if (DURANCE_QUEST.equals(questPath)) {
			restoreDurance(packets, tracker.get());
		}

		if (!Property.update(blobProperty.get(), box(raw))) {
			return false;
		}

		logger.info(
			"Restored quest '%s' (was failed with end state %d).%n", questPath, failedEndState);
		return true;
	}

	private static void restoreDurance (final List<Property> packets, final Tracker tracker) {
		tracker.clearTriggeredEvent(DURANCE_DEATH_EVENT);

		int restoredState = 1; // he joined, or his quest would have no tracker
		for (final int[] eventState : DURANCE_EVENT_STATES) {
			if (tracker.triggeredEvent(eventState[0])) {
				restoredState = eventState[1];
				break;
			}
		}

		final Optional<Property> global = findGlobal(packets, DURANCE_STATE_GLOBAL);
		if (!global.isPresent()) {
			logger.warn("Global '%s' not found; skipping.%n", DURANCE_STATE_GLOBAL);
			return;
		}

		final Object current = ((SimpleProperty) global.get()).value;
		if (current instanceof Number && ((Number) current).intValue() == DURANCE_DEATH_STATE) {
			Property.update(global.get(), restoredState);
			logger.info(
				"Restored %s from %d to %d.%n"
				, DURANCE_STATE_GLOBAL, DURANCE_DEATH_STATE, restoredState);
		}
	}

	/** The QuestTrackers Byte[] property inside InGameGlobal's QuestManager. */
	public static Optional<Property> findQuestTrackers (final List<Property> packets) {
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (packet.ObjectName == null || !packet.ObjectName.startsWith("InGameGlobal")) {
				continue;
			}

			final Optional<Property> blob = ((ComplexProperty) p)
				.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
				.flatMap(c -> EKUtils.findSubComponent(c, "QuestManager"))
				.flatMap(q -> q.<DictionaryProperty>findProperty("Variables"))
				.flatMap(v -> v.findEntry("QuestTrackers"));

			if (blob.isPresent()
				&& blob.get() instanceof SimpleProperty
				&& ((SimpleProperty) blob.get()).value instanceof Byte[]) {

				return blob;
			}
		}

		return Optional.empty();
	}

	private static Optional<Property> findGlobal (
		final List<Property> packets, final String name) {

		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (packet.ObjectName == null || !packet.ObjectName.startsWith("InGameGlobal")) {
				continue;
			}

			final Optional<Property> entry = ((ComplexProperty) p)
				.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
				.flatMap(c -> EKUtils.findSubComponent(c, "GlobalVariables"))
				.flatMap(g -> g.<DictionaryProperty>findProperty("Variables"))
				.flatMap(v -> v.<DictionaryProperty>findEntry("m_data"))
				.flatMap(d -> ((DictionaryProperty) d).findEntry(name));

			if (entry.isPresent()) {
				return entry;
			}
		}

		return Optional.empty();
	}

	private static byte[] unbox (final Byte[] boxed) {
		final byte[] raw = new byte[boxed.length];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = boxed[i];
		}

		return raw;
	}

	private static Byte[] box (final byte[] raw) {
		final Byte[] boxed = new Byte[raw.length];
		for (int i = 0; i < raw.length; i++) {
			boxed[i] = raw[i];
		}

		return boxed;
	}
}
