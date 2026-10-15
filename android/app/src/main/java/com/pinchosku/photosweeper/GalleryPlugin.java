package com.pinchosku.photosweeper;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Gallery: lists the device's photos through MediaStore and deletes them
 * through the system delete request (the user confirms once per batch).
 */
@CapacitorPlugin(
    name = "Gallery",
    permissions = {
        @Permission(alias = "photos", strings = { Manifest.permission.READ_MEDIA_IMAGES }),
        @Permission(alias = "storage", strings = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        })
    }
)
public class GalleryPlugin extends Plugin {

    private androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> deleteLauncher;
    private PluginCall pendingDelete;

    @Override
    public void load() {
        deleteLauncher = getBridge().registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
            result -> {
                PluginCall call = pendingDelete; pendingDelete = null;
                deleteResult(call, result);
            });
    }

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "photos" : "storage";
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void checkAccess(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", hasAccess());
        call.resolve(r);
    }

    @PluginMethod
    public void requestAccess(PluginCall call) {
        if (hasAccess()) { checkAccess(call); return; }
        requestPermissionForAlias(alias(), call, "accessCallback");
    }

    @PermissionCallback
    private void accessCallback(PluginCall call) {
        checkAccess(call);
    }

    /** listPhotos({ offset, limit, bucket? }) -> { items: [...], total } */
    @PluginMethod
    public void listPhotos(PluginCall call) {
        if (!hasAccess()) { call.reject("NO_PERMISSION"); return; }
        int offset = call.getInt("offset", 0);
        int limit = call.getInt("limit", 50);
        String bucket = call.getString("bucket");

        ContentResolver cr = getContext().getContentResolver();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            Build.VERSION.SDK_INT >= 30 ? MediaStore.Images.Media.DATE_EXPIRES : MediaStore.Images.Media.MIME_TYPE
        };
        List<String> conds = new ArrayList<>(); List<String> argl = new ArrayList<>();
        if (bucket != null && !bucket.isEmpty()) { conds.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " = ?"); argl.add(bucket); }
        Long minAdded = call.getLong("minAdded"); // ms
        Long maxAdded = call.getLong("maxAdded"); // ms
        if (minAdded != null) { conds.add(MediaStore.Images.Media.DATE_ADDED + " >= ?"); argl.add(String.valueOf(minAdded / 1000L)); }
        if (maxAdded != null) { conds.add(MediaStore.Images.Media.DATE_ADDED + " < ?"); argl.add(String.valueOf(maxAdded / 1000L)); }
        JSArray onlyIds = call.getArray("ids");
        if (onlyIds != null && onlyIds.length() > 0) {
            StringBuilder in = new StringBuilder();
            try { for (int i = 0; i < onlyIds.length(); i++) { if (i > 0) in.append(","); in.append(Long.parseLong(onlyIds.getString(i))); } }
            catch (Exception e) { call.reject("BAD_IDS"); return; }
            conds.add(MediaStore.Images.Media._ID + " IN (" + in + ")");
        }
        boolean trashed = Boolean.TRUE.equals(call.getBoolean("trashed", false));
        String sel = conds.isEmpty() ? null : android.text.TextUtils.join(" AND ", conds);
        String[] args = argl.isEmpty() ? null : argl.toArray(new String[0]);
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC";

        JSArray items = new JSArray();
        int total = 0;
        Cursor c0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.os.Bundle q = new android.os.Bundle();
            if (sel != null) q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel);
            if (args != null) q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
            q.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order);
            q.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, trashed ? MediaStore.MATCH_ONLY : MediaStore.MATCH_EXCLUDE);
            c0 = cr.query(collection, proj, q, null);
        } else {
            c0 = cr.query(collection, proj, sel, args, order);
        }
        try (Cursor c = c0) {
            if (c != null) {
                total = c.getCount();
                if (c.moveToPosition(offset)) {
                    int n = 0;
                    do {
                        long id = c.getLong(0);
                        Uri uri = ContentUris.withAppendedId(collection, id);
                        JSObject o = new JSObject();
                        o.put("id", String.valueOf(id));
                        o.put("uri", uri.toString());
                        o.put("name", c.getString(1));
                        o.put("size", c.getLong(2));
                        o.put("dateAdded", c.getLong(3) * 1000L);
                        o.put("dateTaken", c.getLong(4));
                        o.put("width", c.getInt(5));
                        o.put("height", c.getInt(6));
                        o.put("bucket", c.getString(7));
                        o.put("mime", c.getString(8));
                        if (Build.VERSION.SDK_INT >= 30) o.put("expires", c.getLong(9) * 1000L);
                        items.put(o);
                        n++;
                    } while (n < limit && c.moveToNext());
                }
            }
        } catch (Exception e) {
            call.reject("QUERY_FAILED: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("items", items);
        r.put("total", total);
        call.resolve(r);
    }

    /** listBuckets() -> { buckets: [{name, count}] } */
    @PluginMethod
    public void listBuckets(PluginCall call) {
        if (!hasAccess()) { call.reject("NO_PERMISSION"); return; }
        ContentResolver cr = getContext().getContentResolver();
        String[] proj = { MediaStore.Images.Media.BUCKET_DISPLAY_NAME };
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext()) {
                String b = c.getString(0); if (b == null) b = "";
                Integer v = counts.get(b); counts.put(b, v == null ? 1 : v + 1);
            }
        }
        JSArray arr = new JSArray();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            JSObject o = new JSObject(); o.put("name", e.getKey()); o.put("count", e.getValue()); arr.put(o);
        }
        JSObject r = new JSObject(); r.put("buckets", arr); call.resolve(r);
    }

    /** capabilities() -> { systemTrash: bool } */
    @PluginMethod
    public void capabilities(PluginCall call) {
        JSObject r = new JSObject(); r.put("systemTrash", Build.VERSION.SDK_INT >= 30); r.put("sdk", Build.VERSION.SDK_INT); call.resolve(r);
    }

    /** trashPhotos({ ids }) -> moves to the system trash (Android 11+), one confirmation for the batch. */
    @PluginMethod
    public void trashPhotos(PluginCall call) { trashOrRestore(call, true); }

    /** restorePhotos({ ids }) -> restores from the system trash (Android 11+). */
    @PluginMethod
    public void restorePhotos(PluginCall call) { trashOrRestore(call, false); }

    private void trashOrRestore(PluginCall call, boolean toTrash) {
        if (Build.VERSION.SDK_INT < 30) { if (toTrash) { deletePhotos(call); } else { call.reject("UNSUPPORTED"); } return; }
        JSArray ids = call.getArray("ids");
        if (ids == null || ids.length() == 0) { call.reject("NO_IDS"); return; }
        List<Uri> uris = new ArrayList<>();
        try {
            for (int i = 0; i < ids.length(); i++)
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.parseLong(ids.getString(i))));
        } catch (JSONException e) { call.reject("BAD_IDS"); return; }
        PendingIntent pi = MediaStore.createTrashRequest(getContext().getContentResolver(), uris, toTrash);
        call.setKeepAlive(true);
        startIntentSenderForResult(call, pi.getIntentSender(), "deleteResult");
    }

    /** deletePhotos({ ids: ["123", ...] }) -> { deleted: n } */
    @PluginMethod
    public void deletePhotos(PluginCall call) {
        JSArray ids = call.getArray("ids");
        if (ids == null || ids.length() == 0) { call.reject("NO_IDS"); return; }
        List<Uri> uris = new ArrayList<>();
        try {
            for (int i = 0; i < ids.length(); i++) {
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    Long.parseLong(ids.getString(i))));
            }
        } catch (JSONException e) { call.reject("BAD_IDS"); return; }

        ContentResolver cr = getContext().getContentResolver();

        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: one system dialog for the whole batch.
            PendingIntent pi = MediaStore.createDeleteRequest(cr, uris);
            call.setKeepAlive(true);
            startIntentSenderForResult(call, pi.getIntentSender(), "deleteResult");
            return;
        }

        int deleted = 0;
        for (Uri u : uris) {
            try {
                deleted += cr.delete(u, null, null);
            } catch (SecurityException se) {
                if (Build.VERSION.SDK_INT >= 29 && se instanceof RecoverableSecurityException) {
                    // Android 10: the system asks per file; we surface the first one and let JS retry.
                    IntentSender s = ((RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender();
                    call.setKeepAlive(true);
                    startIntentSenderForResult(call, s, "deleteResult");
                    return;
                }
                call.reject("DELETE_DENIED: " + se.getMessage());
                return;
            }
        }
        JSObject r = new JSObject(); r.put("deleted", deleted); call.resolve(r);
    }

    private void startIntentSenderForResult(PluginCall call, IntentSender sender, String callback) {
        try {
            pendingDelete = call;
            deleteLauncher.launch(new androidx.activity.result.IntentSenderRequest.Builder(sender).build());
        } catch (Exception e) {
            pendingDelete = null;
            call.reject("DELETE_REQUEST_FAILED: " + e.getMessage());
        }
    }

    private void deleteResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSObject r = new JSObject();
        boolean ok = result.getResultCode() == Activity.RESULT_OK;
        r.put("confirmed", ok);
        JSArray ids = call.getArray("ids");
        r.put("deleted", ok && ids != null ? ids.length() : 0);
        call.resolve(r);
    }
}
