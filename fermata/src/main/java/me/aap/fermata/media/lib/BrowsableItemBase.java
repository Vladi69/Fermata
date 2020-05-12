package me.aap.fermata.media.lib;

import android.support.v4.media.MediaMetadataCompat;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import me.aap.fermata.R;
import me.aap.fermata.media.engine.MetadataBuilder;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.utils.async.Async;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.holder.IntHolder;
import me.aap.utils.log.Log;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.vfs.VirtualResource;

import static java.util.Collections.emptyList;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedEmptyList;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.collection.CollectionUtils.filterMap;
import static me.aap.utils.collection.NaturalOrderComparator.compareNatural;

/**
 * @author Andrey Pavlenko
 */
public abstract class BrowsableItemBase extends ItemBase implements BrowsableItem, BrowsableItemPrefs {
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final AtomicReferenceFieldUpdater<BrowsableItemBase, FutureSupplier<List<Item>>> CHILDREN =
			(AtomicReferenceFieldUpdater) AtomicReferenceFieldUpdater.newUpdater(BrowsableItemBase.class, FutureSupplier.class, "children");
	@Keep
	@SuppressWarnings("unused")
	private volatile FutureSupplier<List<Item>> children;
	private FutureSupplier<Iterator<PlayableItem>> shuffle;

	public BrowsableItemBase(String id, @Nullable BrowsableItem parent, @Nullable VirtualResource file) {
		super(id, parent, file);
	}

	protected abstract FutureSupplier<List<Item>> listChildren();

	@NonNull
	@Override
	public BrowsableItemPrefs getPrefs() {
		return this;
	}

	@NonNull
	@Override
	public FutureSupplier<List<Item>> getUnsortedChildren() {
		FutureSupplier<List<Item>> c = CHILDREN.get(this);
		if (c != null) return c;

		Promise<List<Item>> load = new Promise<>();

		for (; !CHILDREN.compareAndSet(this, null, load); c = CHILDREN.get(this)) {
			if (c != null) return c;
		}

		listChildren().thenReplaceOrClear(CHILDREN, this, load);
		c = CHILDREN.get(this);
		return (c != null) ? c : load;
	}

	@NonNull
	@Override
	public FutureSupplier<List<Item>> getChildren() {
		FutureSupplier<List<Item>> c = CHILDREN.get(this);
		if (isDone(c)) return c;

		LoadChildren load = new LoadChildren();

		for (; !CHILDREN.compareAndSet(this, c, load); c = CHILDREN.get(this)) {
			if (isDone(c)) return c;
		}

		if (c == null) c = listChildren();

		c.then(list -> {
			if (list.isEmpty()) return completedEmptyList();

			load.setProgress(list, 1, 2);
			return loadMetadata(list).then(v -> sortChildren(list));
		}).thenReplaceOrClear(CHILDREN, this, load);

		c = CHILDREN.get(this);
		return (c != null) ? c : load;
	}

	private boolean isDone(FutureSupplier<List<Item>> children) {
		if (children == null) return false;
		if (children.isDone()) {
			List<Item> list = children.peek();
			return (list instanceof SortedItems) || list.isEmpty();
		} else {
			return children instanceof LoadChildren;
		}
	}

	private FutureSupplier<Void> loadMetadata(List<Item> children) {
		String pattern = getChildrenIdPattern();

		if (pattern != null) {
			return getLib().getMetadataRetriever().queryMetadata(pattern).then(meta -> {
				List<PlayableItem> playable;
				int size = meta.size();

				if (size != 0) {
					playable = new ArrayList<>(Math.max(children.size() - size, 0));

					for (Item c : children) {
						if (!(c instanceof PlayableItemBase)) continue;
						PlayableItemBase p = (PlayableItemBase) c;
						MetadataBuilder b = meta.get(p.getId());
						if (b != null) p.setMeta(b);
						else playable.add(p);
					}
				} else {
					playable = filterMap(children, PlayableItem.class::isInstance, PlayableItem.class::cast);
				}

				return playable.isEmpty() ? completedVoid() : Async.forEach(PlayableItem::getMediaData, playable);
			});
		} else {
			List<PlayableItem> playable = filterMap(children, PlayableItem.class::isInstance, PlayableItem.class::cast);
			return playable.isEmpty() ? completedVoid() : Async.forEach(PlayableItem::getMediaData, playable);
		}
	}

	protected FutureSupplier<String> buildTitle(int seqNum, BrowsableItemPrefs parentPrefs) {
		try (SharedTextBuilder tb = SharedTextBuilder.get()) {
			if (seqNum != 0) tb.append(seqNum).append(". ");
			tb.append(getName());
			return completed(tb.toString());
		}
	}

	@Override
	protected FutureSupplier<String> buildSubtitle() {
		return getUnsortedChildren().ifFail(fail -> {
			Log.e(fail);
			return emptyList();
		}).map(this::buildSubtitle);
	}

	protected String buildSubtitle(List<Item> children) {
		int files = 0;
		int folders = 0;
		for (Item i : children) {
			if (i instanceof PlayableItem) files++;
			else folders++;
		}
		return getLib().getContext().getResources().getString(R.string.folder_subtitle, files, folders);
	}

	@NonNull
	@Override
	public FutureSupplier<Iterator<PlayableItem>> getShuffleIterator() {
		if ((shuffle == null) || (shuffle.isDone() && !shuffle.get(null).hasNext())) {
			shuffle = getPlayableChildren(false, false, Integer.MAX_VALUE).map(list -> {
				Random rnd = ThreadLocalRandom.current();
				List<PlayableItem> l = new ArrayList<>(list);

				for (int i = 0, s = l.size(); i < s; i++) {
					int next = rnd.nextInt(s - i);
					Collections.swap(l, i, next);
				}

				Iterator<PlayableItem> it = l.iterator();
				shuffle = completed(it);
				return it;
			});
		}

		return shuffle;
	}

	protected String getChildrenIdPattern() {
		return null;
	}

	@NonNull
	@Override
	public FutureSupplier<Void> updateTitles() {
		FutureSupplier<List<Item>> list = CHILDREN.get(this);
		if (list == null) return super.updateTitles();
		return list.then(children -> Async.forEach(Item::updateTitles, children)).then(v -> super.updateTitles());
	}

	@NonNull
	@Override
	public FutureSupplier<Void> refresh() {
		updateTitles();
		CHILDREN.set(this, null);
		return updateTitles();
	}

	@NonNull
	@Override
	public FutureSupplier<Void> rescan() {
		FutureSupplier<List<Item>> list = CHILDREN.get(this);
		String pattern = getChildrenIdPattern();
		if (pattern != null) getLib().getMetadataRetriever().clearMetadata(pattern);
		if (list == null) return refresh();
		CHILDREN.set(this, null);
		return list.then(children -> Async.forEach(i -> {
			if (i instanceof ItemBase) ((ItemBase) i).reset();
			return completedVoid();
		}, children)).then(v -> super.updateTitles());
	}

	@NonNull
	@Override
	public FutureSupplier<Void> updateSorting() {
		FutureSupplier<List<Item>> list = CHILDREN.get(this);
		if (list == null) return completedVoid();
		return list.map(ArrayList::new).thenReplaceOrClear(CHILDREN, this, list).then(v -> updateTitles());
	}

	void setChildren(List<Item> c) {
		SortedItems sorted = new SortedItems(c);
		setSeqNum(sorted);
		CHILDREN.set(this, completed(sorted));
		updateTitles();
	}

	private FutureSupplier<List<Item>> sortChildren(List<Item> list) {
		SortedItems sorted = new SortedItems(list);

		if (!sortChildrenEnabled()) {
			setSeqNum(sorted);
			return completed(sorted);
		}

		BrowsableItemPrefs prefs = getPrefs();
		boolean desc = prefs.getSortDescPref();

		switch (prefs.getSortByPref()) {
			case BrowsableItemPrefs.SORT_BY_FILE_NAME:
				Collections.sort(sorted, (i1, i2) -> compareByFile(i1, i2, desc));
				break;
			case BrowsableItemPrefs.SORT_BY_NAME:
				Collections.sort(sorted, (i1, i2) -> compareByName(i1, i2, desc));
				break;
			case BrowsableItemPrefs.SORT_BY_DATE:
				return getDates(list).then(dates -> {
					Collections.sort(sorted, (i1, i2) -> compareByDate(i1, i2, list, dates, desc));
					setSeqNum(sorted);
					return completed(sorted);
				});
		}

		setSeqNum(sorted);
		return completed(sorted);
	}

	private void setSeqNum(SortedItems sorted) {
		for (int i = 0; i < sorted.size(); i++) {
			((ItemBase) sorted.get(i)).setSeqNum(i + 1);
		}
	}

	private int compareByFile(Item i1, Item i2, boolean desc) {
		if (i1 instanceof BrowsableItem) {
			if (i2 instanceof BrowsableItem) {
				VirtualResource f1 = i1.getFile();
				VirtualResource f2 = i2.getFile();
				return (f1 != null) && (f2 != null) ? compareNatural(f1.getName(), f2.getName(), desc) :
						compareNatural(name(i1), name(i2), desc);
			} else {
				return -1;
			}
		} else if (i2 instanceof BrowsableItem) {
			return 1;
		} else {
			VirtualResource f1 = i1.getFile();
			VirtualResource f2 = i2.getFile();
			return (f1 != null) && (f2 != null) ? compareNatural(f1.getName(), f2.getName(), desc) :
					compareNatural(name(i1), name(i2), desc);
		}
	}

	private int compareByName(Item i1, Item i2, boolean desc) {
		if (i1 instanceof BrowsableItem) {
			return (i2 instanceof BrowsableItem) ? compareNatural(name(i1), name(i2), desc) : -1;
		} else if (i2 instanceof BrowsableItem) {
			return 1;
		} else {
			return compareNatural(name(i1), name(i2), desc);
		}
	}

	private int compareByDate(Item i1, Item i2, List<Item> list, long[] dates, boolean desc) {
		if (i1 instanceof BrowsableItem) {
			if (i2 instanceof BrowsableItem) {
				return desc ? compareDate(i2, i1, list, dates) : compareDate(i1, i2, list, dates);
			} else {
				return -1;
			}
		} else if (i2 instanceof BrowsableItem) {
			return 1;
		} else if (desc) {
			return compareDate(i2, i1, list, dates);
		} else {
			return compareDate(i1, i2, list, dates);
		}
	}

	private int compareDate(Item i1, Item i2, List<Item> list, long[] dates) {
		int idx1 = -1;
		int idx2 = -1;

		for (int i = 0; i < list.size(); i++) {
			Item item = list.get(i);

			if (item == i1) {
				idx1 = i;
				if (idx2 != -1) break;
			} else if (item == i2) {
				idx2 = i;
				if (idx1 != -1) break;
			}
		}

		long d1 = (idx1 != -1) ? dates[idx1] : 0;
		long d2 = (idx2 != -1) ? dates[idx2] : 0;
		return Long.compare(d1, d2);
	}

	private static FutureSupplier<long[]> getDates(List<Item> list) {
		IntHolder i = new IntHolder();
		long[] dates = new long[list.size()];
		return Async.forEach(item -> item.getFile().getLastModified()
				.onSuccess(d -> dates[i.value++] = d), list).map(v -> dates);
	}

	private static String name(Item i) {
		if (i instanceof BrowsableItem) {
			return ((BrowsableItem) i).getName();
		} else if (i instanceof PlayableItem) {
			MediaMetadataCompat md = ((PlayableItem) i).getMediaData().get(null);
			String title = (md != null) ? md.getString(MediaMetadataCompat.METADATA_KEY_TITLE) : null;
			if (title != null) return title;
		}

		return i.getFile().getName();
	}

	private static final class LoadChildren extends Promise<List<Item>> {
	}

	private static final class SortedItems extends ArrayList<Item> {
		public SortedItems(@NonNull Collection<? extends Item> c) {
			super(c);
		}
	}
}
