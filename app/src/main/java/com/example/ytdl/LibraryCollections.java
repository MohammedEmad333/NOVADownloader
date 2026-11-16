package com.example.ytdl;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Device-local labels for saved MediaStore entries. Members are keyed by BOTH
 * the queue id and its exact saved URI; a reused id cannot inherit membership.
 *
 * This class writes only its own SharedPreferences document. It never changes
 * the download queue, touches a media file or deletes a MediaStore item.
 */
final class LibraryCollections {
    static final String PREFS = "nova_library_collections";
    static final String KEY = "collections_v1";
    static final int MAX_COLLECTIONS = 25;
    static final int MAX_MEMBERS = 4000;
    static final int MAX_NAME_LENGTH = 48;
    private static final int MAX_DOCUMENT_LENGTH = 2_000_000;

    static final class Collection {
        final String id;
        final String name;
        final Map<Long, String> members;

        Collection(String id, String name, Map<Long, String> members) {
            this.id = id;
            this.name = name;
            this.members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }

        int count() {
            return members.size();
        }

        boolean contains(DownloadService.Task task) {
            return LibraryFavorites.eligible(task)
                    && task.outputUri.equals(members.get(task.id));
        }
    }

    private LibraryCollections() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String checkedName(String raw) {
        if (raw == null) throw new IllegalArgumentException("Collection name is required");
        final String name = raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Collection name must be 1–48 characters");
        }
        return name;
    }

    private static String idOf(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid collection id");
        }
        return id;
    }

    private static boolean duplicateName(List<Collection> all, String name, String excludingId) {
        for (Collection collection : all) {
            if (!collection.id.equals(excludingId)
                    && collection.name.toLowerCase(Locale.ROOT)
                            .equals(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    static synchronized List<Collection> list(Context context) {
        final String raw;
        try {
            raw = prefs(context).getString(KEY, null);
        } catch (RuntimeException badType) {
            throw new IllegalStateException("Collections metadata cannot be read", badType);
        }
        if (raw == null) return Collections.emptyList();
        if (raw.length() > MAX_DOCUMENT_LENGTH) {
            throw new IllegalStateException("Collections metadata is too large");
        }
        try {
            final JSONArray data = new JSONArray(raw);
            if (data.length() > MAX_COLLECTIONS) throw new JSONException("too many collections");
            final List<Collection> entries = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                final JSONObject object = data.getJSONObject(i);
                final String id = idOf(object.getString("id"));
                final String name = checkedName(object.getString("name"));
                if (duplicateName(entries, name, null)) {
                    throw new JSONException("duplicate collection name");
                }
                for (Collection item : entries) {
                    if (item.id.equals(id)) throw new JSONException("duplicate collection id");
                }
                final JSONObject stored = object.getJSONObject("members");
                if (stored.length() > MAX_MEMBERS) throw new JSONException("too many members");
                final Map<Long, String> members = new LinkedHashMap<>();
                final java.util.Iterator<String> keys = stored.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    final long taskId = Long.parseLong(key);
                    final String uri = stored.getString(key);
                    if (taskId <= 0 || !PendingMediaLedger.isOwnedItemUri(Uri.parse(uri))) {
                        throw new JSONException("unsafe saved file reference");
                    }
                    members.put(taskId, uri);
                }
                entries.add(new Collection(id, name, members));
            }
            return Collections.unmodifiableList(entries);
        } catch (JSONException | RuntimeException badData) {
            // Do NOT overwrite malformed metadata with an empty collection list.
            throw new IllegalStateException("Collections metadata is unreadable", badData);
        }
    }

    private static void persist(Context context, List<Collection> collections) {
        final JSONArray data = new JSONArray();
        try {
            for (Collection collection : collections) {
                final JSONObject members = new JSONObject();
                for (Map.Entry<Long, String> entry : collection.members.entrySet()) {
                    members.put(Long.toString(entry.getKey()), entry.getValue());
                }
                data.put(new JSONObject().put("id", collection.id)
                        .put("name", collection.name).put("members", members));
            }
            final String serialized = data.toString();
            if (serialized.length() > MAX_DOCUMENT_LENGTH
                    || !prefs(context).edit().putString(KEY, serialized).commit()) {
                throw new IllegalStateException("Could not save collection changes");
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode collection changes", error);
        }
    }

    static synchronized Collection create(Context context, String rawName) {
        final String name = checkedName(rawName);
        final List<Collection> current = new ArrayList<>(list(context));
        if (current.size() >= MAX_COLLECTIONS) {
            throw new IllegalArgumentException("Collection limit reached");
        }
        if (duplicateName(current, name, null)) {
            throw new IllegalArgumentException("A collection with this name already exists");
        }
        final Collection created = new Collection(UUID.randomUUID().toString(), name,
                Collections.emptyMap());
        current.add(created);
        persist(context, current);
        return created;
    }

    static synchronized void rename(Context context, String id, String rawName) {
        idOf(id);
        final String name = checkedName(rawName);
        final List<Collection> current = new ArrayList<>(list(context));
        if (duplicateName(current, name, id)) {
            throw new IllegalArgumentException("A collection with this name already exists");
        }
        for (int i = 0; i < current.size(); i++) {
            final Collection item = current.get(i);
            if (item.id.equals(id)) {
                if (item.name.equals(name)) return;
                current.set(i, new Collection(id, name, item.members));
                persist(context, current);
                return;
            }
        }
        throw new IllegalArgumentException("Collection no longer exists");
    }

    static synchronized boolean remove(Context context, String id) {
        idOf(id);
        final List<Collection> current = new ArrayList<>(list(context));
        final boolean changed = current.removeIf(item -> item.id.equals(id));
        if (changed) persist(context, current);
        return changed;
    }

    static synchronized int add(Context context, String id, List<DownloadService.Task> tasks) {
        idOf(id);
        final List<Collection> current = new ArrayList<>(list(context));
        for (int i = 0; i < current.size(); i++) {
            final Collection item = current.get(i);
            if (!item.id.equals(id)) continue;
            final Map<Long, String> members = new LinkedHashMap<>(item.members);
            int added = 0;
            if (tasks != null) {
                for (DownloadService.Task task : tasks) {
                    if (!LibraryFavorites.eligible(task)) continue;
                    if (task.outputUri.equals(members.get(task.id))) continue;
                    if (!members.containsKey(task.id) && members.size() >= MAX_MEMBERS) break;
                    members.put(task.id, task.outputUri);
                    added++;
                }
            }
            if (added > 0) {
                current.set(i, new Collection(id, item.name, members));
                persist(context, current);
            }
            return added;
        }
        throw new IllegalArgumentException("Collection no longer exists");
    }

    /** Detach labels, not media or queue entries. A reused task id is ignored. */
    static synchronized int removeMembers(Context context, String id,
                                          List<DownloadService.Task> tasks) {
        idOf(id);
        final List<Collection> current = new ArrayList<>(list(context));
        for (int i = 0; i < current.size(); i++) {
            final Collection item = current.get(i);
            if (!item.id.equals(id)) continue;
            final Map<Long, String> members = new LinkedHashMap<>(item.members);
            int removed = 0;
            if (tasks != null) {
                for (DownloadService.Task task : tasks) {
                    if (task != null && task.outputUri != null
                            && task.outputUri.equals(members.get(task.id))) {
                        members.remove(task.id);
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                current.set(i, new Collection(id, item.name, members));
                persist(context, current);
            }
            return removed;
        }
        throw new IllegalArgumentException("Collection no longer exists");
    }

    static Collection byId(List<Collection> entries, String id) {
        if (entries == null || id == null) return null;
        for (Collection item : entries) if (item.id.equals(id)) return item;
        return null;
    }
}
