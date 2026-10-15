package com.abfilepro.app.core;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/** Real framework DocumentsProvider; only disposable test-app cache files are exposed. */
public final class MaintenanceDocumentsProvider extends DocumentsProvider {
    private File root;
    private String fault = "";
    private boolean faultFired;

    @Override public boolean onCreate() {
        root = new File(getContext().getCacheDir(), "maintenance_documents");
        root.mkdirs();
        return true;
    }

    private static void write(File file, String text) {
        try { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException error) { throw new IllegalStateException(error); }
    }

    private static boolean removeTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (!removeTree(child)) return false;
        return !file.exists() || file.delete();
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"reset_fixtures".equals(method)) return super.call(method, arg, extras);
        if (!removeTree(root) || !root.mkdirs()) throw new IllegalStateException("Cannot reset fixtures");
        new File(root, "source").mkdirs();
        write(new File(root, "source/seed.txt"), "ORIGINAL_CHILD");
        write(new File(root, "input.txt"), "ORIGINAL_INPUT");
        new File(root, "destination").mkdirs();
        fault = arg == null ? "" : arg;
        faultFired = false;
        Bundle result = new Bundle();
        result.putBoolean("ready", true);
        return result;
    }

    private File file(String id) {
        File document = "root".equals(id) ? root : new File(root, id);
        try {
            String path = document.getCanonicalPath();
            String base = root.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base + File.separator)) throw new IllegalArgumentException("Outside fixtures");
            return document;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    private String id(File file) { return root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'); }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[] {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (Root.COLUMN_ROOT_ID.equals(column)) values[index] = "maintenance";
            else if (Root.COLUMN_DOCUMENT_ID.equals(column)) values[index] = "root";
            else if (Root.COLUMN_TITLE.equals(column)) values[index] = "Maintenance fixtures";
            else if (Root.COLUMN_FLAGS.equals(column)) values[index] = Root.FLAG_SUPPORTS_CREATE;
        }
        cursor.addRow(values);
        return cursor;
    }

    private MatrixCursor documentCursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] {Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS} : projection);
    }

    private void row(MatrixCursor cursor, String documentId) {
        File document = file(documentId);
        if (!document.exists()) return;
        String[] columns = cursor.getColumnNames();
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (Document.COLUMN_DOCUMENT_ID.equals(column)) values[index] = documentId;
            else if (Document.COLUMN_DISPLAY_NAME.equals(column)) values[index] = document.getName();
            else if (Document.COLUMN_MIME_TYPE.equals(column)) values[index] = document.isDirectory() ? Document.MIME_TYPE_DIR : "text/plain";
            else if (Document.COLUMN_SIZE.equals(column)) values[index] = document.length();
            else if (Document.COLUMN_LAST_MODIFIED.equals(column)) values[index] = document.lastModified();
            else if (Document.COLUMN_FLAGS.equals(column)) values[index] = Document.FLAG_SUPPORTS_DELETE |
                    (document.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE);
        }
        cursor.addRow(values);
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) {
        File queried = file(documentId);
        if (!faultFired && documentId.startsWith("destination/") && queried.isFile() && queried.length() > 0) {
            if ("mutate_input_after_copy".equals(fault)) { faultFired = true; write(new File(root, "input.txt"), "UPDATED_INPUT"); }
            else if ("add_child_after_copy".equals(fault)) { faultFired = true; write(new File(root, "source/late.txt"), "LATE_CHILD"); }
            else if ("corrupt_destination".equals(fault)) { faultFired = true; write(queried, "BROKEN"); }
        }
        MatrixCursor cursor = documentCursor(projection);
        row(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        if ("null_source_listing".equals(fault) && "source".equals(parentDocumentId)) return null;
        MatrixCursor cursor = documentCursor(projection);
        File[] children = file(parentDocumentId).listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) row(cursor, id(child));
        }
        return cursor;
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try { return file(documentId).getCanonicalPath().startsWith(file(parentDocumentId).getCanonicalPath() + File.separator); }
        catch (IOException error) { throw new IllegalStateException(error); }
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        if (displayName.contains("/") || displayName.contains("\\")) throw new IllegalArgumentException("Invalid fixture name");
        File child = new File(file(parentDocumentId), displayName);
        try {
            if (child.exists() || !(Document.MIME_TYPE_DIR.equals(mimeType) ? child.mkdir() : child.createNewFile())) throw new IOException("Cannot create fixture");
            return id(child);
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        if (!removeTree(file(documentId))) throw new FileNotFoundException("Cannot delete fixture");
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }
}
