package me.devsaki.hentoid.services;

import android.app.IntentService;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.squareup.moshi.JsonDataException;

import org.greenrobot.eventbus.EventBus;
import org.threeten.bp.Instant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import me.devsaki.hentoid.R;
import me.devsaki.hentoid.activities.bundles.ImportActivityBundle;
import me.devsaki.hentoid.database.CollectionDAO;
import me.devsaki.hentoid.database.ObjectBoxDAO;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.Group;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.database.domains.QueueRecord;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Grouping;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.events.DownloadEvent;
import me.devsaki.hentoid.events.ProcessEvent;
import me.devsaki.hentoid.events.ServiceDestroyedEvent;
import me.devsaki.hentoid.json.ContentV1;
import me.devsaki.hentoid.json.DoujinBuilder;
import me.devsaki.hentoid.json.JsonContent;
import me.devsaki.hentoid.json.JsonContentCollection;
import me.devsaki.hentoid.json.URLBuilder;
import me.devsaki.hentoid.notification.import_.ImportCompleteNotification;
import me.devsaki.hentoid.notification.import_.ImportProgressNotification;
import me.devsaki.hentoid.notification.import_.ImportStartNotification;
import me.devsaki.hentoid.util.Consts;
import me.devsaki.hentoid.util.ContentHelper;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.ImageHelper;
import me.devsaki.hentoid.util.ImportHelper;
import me.devsaki.hentoid.util.JsonHelper;
import me.devsaki.hentoid.util.LogUtil;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.exception.ParseException;
import me.devsaki.hentoid.util.notification.ServiceNotificationManager;
import timber.log.Timber;

/**
 * Service responsible for importing an existing Hentoid library.
 */
@Deprecated
public class ImportService extends IntentService {

    private static final int NOTIFICATION_ID = 1;

    public static final int STEP_GROUPS = 0;
    public static final int STEP_1 = 1;
    public static final int STEP_2_BOOK_FOLDERS = 2;
    public static final int STEP_3_BOOKS = 3;
    public static final int STEP_4_QUEUE_FINAL = 4;

    private static boolean running;
    private ServiceNotificationManager notificationManager;


    public ImportService() {
        super(ImportService.class.getName());
    }

    public static Intent makeIntent(@NonNull Context context) {
        return new Intent(context, ImportService.class);
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        running = true;
        notificationManager = new ServiceNotificationManager(this, NOTIFICATION_ID);
        notificationManager.cancel();
        notificationManager.startForeground(new ImportStartNotification());

        Timber.w("Service created");
    }

    @Override
    public void onDestroy() {
        running = false;
        if (notificationManager != null) notificationManager.cancel();
        EventBus.getDefault().post(new ServiceDestroyedEvent(ServiceDestroyedEvent.Service.IMPORT));
        Timber.w("Service destroyed");

        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        // True if the user has asked for a cleanup when calling import from Preferences
        boolean doRename = false;
        boolean doCleanNoJson = false;
        boolean doCleanNoImages = false;

        if (intent != null && intent.getExtras() != null) {
            ImportActivityBundle.Parser parser = new ImportActivityBundle.Parser(intent.getExtras());
            doRename = parser.getRefreshRename();
            doCleanNoJson = parser.getRefreshCleanNoJson();
            doCleanNoImages = parser.getRefreshCleanNoImages();
        }
        startImport(doRename, doCleanNoJson, doCleanNoImages);
    }

    private void eventProgress(int step, int nbBooks, int booksOK, int booksKO) {
        EventBus.getDefault().post(new ProcessEvent(ProcessEvent.EventType.PROGRESS, step, booksOK, booksKO, nbBooks));
    }

    private void eventComplete(int step, int nbBooks, int booksOK, int booksKO, DocumentFile cleanupLogFile) {
        EventBus.getDefault().post(new ProcessEvent(ProcessEvent.EventType.COMPLETE, step, booksOK, booksKO, nbBooks, cleanupLogFile));
    }

    private void trace(int priority, int chapter, List<LogUtil.LogEntry> memoryLog, String s, String... t) {
        s = String.format(s, (Object[]) t);
        Timber.log(priority, s);
        boolean isError = (priority > Log.INFO);
        if (null != memoryLog) memoryLog.add(new LogUtil.LogEntry(s, chapter, isError));
    }


    /**
     * Import books from known source folders
     *
     * @param rename        True if the user has asked for a folder renaming when calling import from Preferences
     * @param cleanNoJSON   True if the user has asked for a cleanup of folders with no JSONs when calling import from Preferences
     * @param cleanNoImages True if the user has asked for a cleanup of folders with no images when calling import from Preferences
     */
    private void startImport(boolean rename, boolean cleanNoJSON, boolean cleanNoImages) {
        int booksOK = 0;                        // Number of books imported
        int booksKO = 0;                        // Number of folders found with no valid book inside
        int nbFolders = 0;                      // Number of folders found with no content but subfolders
        Content content = null;
        List<LogUtil.LogEntry> log = new ArrayList<>();

        // Stop downloads; it can get messy if downloading _and_ refresh / import happen at the same time
        EventBus.getDefault().post(new DownloadEvent(DownloadEvent.EV_PAUSE));

        final FileHelper.NameFilter imageNames = displayName -> ImageHelper.isImageExtensionSupported(FileHelper.getExtension(displayName));

        DocumentFile rootFolder = FileHelper.getFolderFromTreeUriString(this, Preferences.getStorageUri());
        if (null == rootFolder) {
            Timber.e("Root folder is not defined (%s)", Preferences.getStorageUri());
            return;
        }

        ContentProviderClient client = this.getContentResolver().acquireContentProviderClient(Uri.parse(Preferences.getStorageUri()));
        if (null == client) return;

        List<DocumentFile> bookFolders = new ArrayList<>();
        CollectionDAO dao = new ObjectBoxDAO(this);

        try {
            // 1st pass : Import groups JSON

            // Flag existing groups for cleanup
            dao.flagAllGroups(Grouping.CUSTOM);

            DocumentFile groupsFile = FileHelper.findFile(this, rootFolder, client, Consts.GROUPS_JSON_FILE_NAME);
            if (groupsFile != null) importGroups(groupsFile, dao, log);
            else trace(Log.INFO, STEP_GROUPS, log, "No groups file found");

            // 2nd pass : count subfolders of every site folder
            List<DocumentFile> siteFolders = FileHelper.listFolders(this, rootFolder, client);
            int foldersProcessed = 1;
            for (DocumentFile f : siteFolders) {
                bookFolders.addAll(FileHelper.listFolders(this, f, client));
                eventProgress(STEP_2_BOOK_FOLDERS, siteFolders.size(), foldersProcessed++, 0);
            }
            eventComplete(STEP_2_BOOK_FOLDERS, siteFolders.size(), siteFolders.size(), 0, null);
            notificationManager.startForeground(new ImportProgressNotification(this.getResources().getString(R.string.starting_import), 0, 0));

            // 3rd pass : scan every folder for a JSON file or subdirectories
            String enabled = getApplication().getResources().getString(R.string.enabled);
            String disabled = getApplication().getResources().getString(R.string.disabled);
            trace(Log.DEBUG, 0, log, "Import books starting - initial detected count : %s", bookFolders.size() + "");
            trace(Log.INFO, 0, log, "Rename folders %s", (rename ? enabled : disabled));
            trace(Log.INFO, 0, log, "Remove folders with no JSONs %s", (cleanNoJSON ? enabled : disabled));
            trace(Log.INFO, 0, log, "Remove folders with no images %s", (cleanNoImages ? enabled : disabled));

            // Flag DB content for cleanup
            dao.flagAllInternalBooks();
            dao.flagAllErrorBooksWithJson();

            for (int i = 0; i < bookFolders.size(); i++) {
                DocumentFile bookFolder = bookFolders.get(i);

                // Detect the presence of images if the corresponding cleanup option has been enabled
                if (cleanNoImages) {
                    List<DocumentFile> imageFiles = FileHelper.listFiles(this, bookFolder, client, imageNames);
                    List<DocumentFile> subfolders = FileHelper.listFolders(this, bookFolder, client);
                    if (imageFiles.isEmpty() && subfolders.isEmpty()) { // No supported images nor subfolders
                        booksKO++;
                        boolean success = bookFolder.delete();
                        trace(Log.INFO, STEP_1, log, "[Remove no image %s] Folder %s", success ? "OK" : "KO", bookFolder.getUri().toString());
                        continue;
                    }
                }

                // Find the corresponding flagged book in the library
                Content existingFlaggedContent = dao.selectContentByStorageUri(bookFolder.getUri().toString(), true);

                // Detect JSON and try to parse it
                try {
                    content = importJson(bookFolder, client, dao);
                    if (content != null) {
                        // If the book exists and is flagged for deletion, delete it to make way for a new import (as intended)
                        if (existingFlaggedContent != null)
                            dao.deleteContent(existingFlaggedContent);

                        // If the very same book still exists in the DB at this point, it means it's present in the queue
                        // => don't import it even though it has a JSON file; it has been re-queued after being downloaded or viewed once
                        Content existingDuplicate = dao.selectContentBySourceAndUrl(content.getSite(), content.getUrl(), content.getCoverImageUrl());
                        if (existingDuplicate != null && !existingDuplicate.isFlaggedForDeletion()) {
                            booksKO++;
                            String location = ContentHelper.isInQueue(existingDuplicate.getStatus()) ? "queue" : "collection";
                            trace(Log.INFO, STEP_2_BOOK_FOLDERS, log, "Import book KO! (already in " + location + ") : %s", bookFolder.getUri().toString());
                            continue;
                        }

                        List<ImageFile> contentImages;
                        if (content.getImageFiles() != null)
                            contentImages = content.getImageFiles();
                        else contentImages = new ArrayList<>();

                        if (rename) {
                            String canonicalBookFolderName = ContentHelper.formatBookFolderName(content);

                            List<String> currentPathParts = bookFolder.getUri().getPathSegments();
                            String[] bookUriParts = currentPathParts.get(currentPathParts.size() - 1).split(":");
                            String[] bookPathParts = bookUriParts[bookUriParts.length - 1].split("/");
                            String bookFolderName = bookPathParts[bookPathParts.length - 1];

                            if (!canonicalBookFolderName.equalsIgnoreCase(bookFolderName)) {
                                if (renameFolder(bookFolder, content, client, canonicalBookFolderName)) {
                                    trace(Log.INFO, STEP_2_BOOK_FOLDERS, log, "[Rename OK] Folder %s renamed to %s", bookFolderName, canonicalBookFolderName);
                                } else {
                                    trace(Log.WARN, STEP_2_BOOK_FOLDERS, log, "[Rename KO] Could not rename file %s to %s", bookFolderName, canonicalBookFolderName);
                                }
                            }
                        }

                        // Attach image file Uri's to the book's images
                        List<DocumentFile> imageFiles = FileHelper.listFiles(this, bookFolder, client, imageNames);
                        if (!imageFiles.isEmpty()) {
                            // No images described in the JSON -> recreate them
                            if (contentImages.isEmpty()) {
                                contentImages = ContentHelper.createImageListFromFiles(imageFiles);
                                content.setImageFiles(contentImages);
                                content.getCover().setUrl(content.getCoverImageUrl());
                            } else { // Existing images described in the JSON -> map them
                                contentImages = ContentHelper.matchFilesToImageList(imageFiles, contentImages);
                                content.setImageFiles(contentImages);
                            }
                        }

                        // If content has an external-library tag, remove it because we're importing for the primary library now
                        ImportHelper.removeExternalAttribute(content);

                        content.computeSize();
                        ContentHelper.addContent(this, dao, content);
                        trace(Log.INFO, STEP_2_BOOK_FOLDERS, log, "Import book OK : %s", bookFolder.getUri().toString());
                    } else { // JSON not found
                        List<DocumentFile> subfolders = FileHelper.listFolders(this, bookFolder, client);
                        if (!subfolders.isEmpty()) // Folder doesn't contain books but contains subdirectories
                        {
                            bookFolders.addAll(subfolders);
                            trace(Log.INFO, STEP_2_BOOK_FOLDERS, log, "Subfolders found in : %s", bookFolder.getUri().toString());
                            nbFolders++;
                            continue;
                        } else { // No JSON nor any subdirectory
                            trace(Log.WARN, STEP_2_BOOK_FOLDERS, log, "Import book KO! (no JSON found) : %s", bookFolder.getUri().toString());
                            // Deletes the folder if cleanup is active
                            if (cleanNoJSON) {
                                boolean success = bookFolder.delete();
                                trace(Log.INFO, STEP_2_BOOK_FOLDERS, log, "[Remove no JSON %s] Folder %s", success ? "OK" : "KO", bookFolder.getUri().toString());
                            }
                        }
                    }

                    if (null == content) booksKO++;
                    else booksOK++;
                } catch (ParseException jse) {
                    // If the book is still present in the DB, regenerate the JSON and unflag the book
                    if (existingFlaggedContent != null) {
                        try {
                            DocumentFile newJson = JsonHelper.jsonToFile(this, JsonContent.fromEntity(existingFlaggedContent), JsonContent.class, bookFolder);
                            existingFlaggedContent.setJsonUri(newJson.getUri().toString());
                            existingFlaggedContent.setFlaggedForDeletion(false);
                            dao.insertContent(existingFlaggedContent);
                            trace(Log.INFO, STEP_2_BOOK_FOLDERS, log, "Import book OK (JSON regenerated) : %s", bookFolder.getUri().toString());
                            booksOK++;
                        } catch (IOException | JsonDataException e) {
                            Timber.w(e);
                            trace(Log.ERROR, STEP_2_BOOK_FOLDERS, log, "Import book ERROR while regenerating JSON : %s for Folder %s", jse.getMessage(), bookFolder.getUri().toString());
                            booksKO++;
                        }
                    } else { // If not, rebuild the book and regenerate the JSON according to stored data
                        try {
                            List<String> parentFolder = new ArrayList<>();
                            // Try and detect the site according to the parent folder
                            String[] parents = bookFolder.getUri().getPath().split("/"); // _not_ File.separator but the universal Uri separator
                            if (parents.length > 1) {
                                for (Site s : Site.values())
                                    if (parents[parents.length - 2].equalsIgnoreCase(s.getFolder())) {
                                        parentFolder.add(s.getFolder());
                                        break;
                                    }
                            }
                            // Scan the folder
                            Content storedContent = ImportHelper.scanBookFolder(this, bookFolder, client, parentFolder, StatusContent.DOWNLOADED, dao, null, null);
                            DocumentFile newJson = JsonHelper.jsonToFile(this, JsonContent.fromEntity(storedContent), JsonContent.class, bookFolder);
                            storedContent.setJsonUri(newJson.getUri().toString());
                            ContentHelper.addContent(this, dao, storedContent);
                            trace(Log.INFO, STEP_2_BOOK_FOLDERS, log, "Import book OK (Content regenerated) : %s", bookFolder.getUri().toString());
                            booksOK++;
                        } catch (IOException | JsonDataException e) {
                            Timber.w(e);
                            trace(Log.ERROR, STEP_2_BOOK_FOLDERS, log, "Import book ERROR while regenerating Content : %s for Folder %s", jse.getMessage(), bookFolder.getUri().toString());
                            booksKO++;
                        }
                    }
                } catch (Exception e) {
                    Timber.w(e);
                    if (null == content)
                        content = new Content().setTitle("none").setSite(Site.NONE).setUrl("");
                    booksKO++;
                    trace(Log.ERROR, STEP_2_BOOK_FOLDERS, log, "Import book ERROR : %s for Folder %s", e.getMessage(), bookFolder.getUri().toString());
                }
                String bookName = (null == bookFolder.getName()) ? "" : bookFolder.getName();
                notificationManager.notify(new ImportProgressNotification(bookName, booksOK + booksKO, bookFolders.size() - nbFolders));
                eventProgress(STEP_3_BOOKS, bookFolders.size() - nbFolders, booksOK, booksKO);
            }
            trace(Log.INFO, STEP_3_BOOKS, log, "Import books complete - %s OK; %s KO; %s final count", booksOK + "", booksKO + "", bookFolders.size() - nbFolders + "");
            eventComplete(STEP_3_BOOKS, bookFolders.size(), booksOK, booksKO, null);

            // 4th pass : Import queue JSON
            DocumentFile queueFile = FileHelper.findFile(this, rootFolder, client, Consts.QUEUE_JSON_FILE_NAME);
            if (queueFile != null) importQueue(queueFile, dao, log);
            else trace(Log.INFO, STEP_4_QUEUE_FINAL, log, "No queue file found");
        } finally {
            // Write log in root folder
            DocumentFile logFile = LogUtil.writeLog(this, buildLogInfo(rename || cleanNoJSON || cleanNoImages, log));

            // ContentProviderClient.close only available on API level 24+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                client.close();
            else
                client.release();

            dao.deleteAllFlaggedBooks(true);
            dao.deleteAllFlaggedGroups();
            dao.cleanup();

            eventComplete(STEP_4_QUEUE_FINAL, bookFolders.size(), booksOK, booksKO, logFile);
            notificationManager.notify(new ImportCompleteNotification(booksOK, booksKO));
        }

        stopForeground(true);
        stopSelf();
    }

    private LogUtil.LogInfo buildLogInfo(boolean cleanup, @NonNull List<LogUtil.LogEntry> log) {
        LogUtil.LogInfo logInfo = new LogUtil.LogInfo();
        logInfo.setLogName(cleanup ? "Cleanup" : "Import");
        logInfo.setFileName(cleanup ? "cleanup_log" : "import_log");
        logInfo.setNoDataMessage("No content detected.");
        logInfo.setLog(log);
        return logInfo;
    }

    private boolean renameFolder(@NonNull DocumentFile folder, @NonNull final Content content, @NonNull ContentProviderClient client, @NonNull final String newName) {
        try {
            if (folder.renameTo(newName)) {
                // 1- Update the book folder's URI
                content.setStorageUri(folder.getUri().toString());
                // 2- Update the JSON's URI
                DocumentFile jsonFile = FileHelper.findFile(this, folder, client, Consts.JSON_FILE_NAME_V2);
                if (jsonFile != null) content.setJsonUri(jsonFile.getUri().toString());
                // 3- Update the image's URIs -> will be done by the next block back in startImport
                return true;
            }
        } catch (Exception e) {
            Timber.e(e);
        }
        return false;
    }

    private void importQueue(@NonNull DocumentFile queueFile, @NonNull CollectionDAO dao, @NonNull List<LogUtil.LogEntry> log) {
        trace(Log.INFO, STEP_4_QUEUE_FINAL, log, "Queue JSON found");
        eventProgress(STEP_4_QUEUE_FINAL, -1, 0, 0);
        JsonContentCollection contentCollection = deserialiseCollectionJson(queueFile);
        if (null != contentCollection) {
            int queueSize = (int) dao.countAllQueueBooks();
            List<Content> queuedContent = contentCollection.getQueue();
            eventProgress(STEP_4_QUEUE_FINAL, queuedContent.size(), 0, 0);
            trace(Log.INFO, STEP_4_QUEUE_FINAL, log, "Queue JSON deserialized : %s books detected", queuedContent.size() + "");
            List<QueueRecord> lst = new ArrayList<>();
            int count = 1;
            for (Content c : queuedContent) {
                Content duplicate = dao.selectContentBySourceAndUrl(c.getSite(), c.getUrl(), c.getCoverImageUrl());
                if (null == duplicate) {
                    if (c.getStatus().equals(StatusContent.ERROR)) {
                        // Add error books as library entries, not queue entries
                        c.computeSize();
                        ContentHelper.addContent(this, dao, c);
                    } else {
                        // Only add at the end of the queue if it isn't a duplicate
                        long newContentId = ContentHelper.addContent(this, dao, c);
                        lst.add(new QueueRecord(newContentId, queueSize++));
                    }
                }
                eventProgress(STEP_4_QUEUE_FINAL, queuedContent.size(), count++, 0);
            }
            dao.updateQueue(lst);
            trace(Log.INFO, STEP_4_QUEUE_FINAL, log, "Import queue succeeded");
        } else {
            trace(Log.INFO, STEP_4_QUEUE_FINAL, log, "Import queue failed : Queue JSON unreadable");
        }
    }

    private void importGroups(@NonNull DocumentFile groupsFile, @NonNull CollectionDAO dao, @NonNull List<LogUtil.LogEntry> log) {
        trace(Log.INFO, STEP_GROUPS, log, "Custom groups JSON found");
        eventProgress(STEP_GROUPS, -1, 0, 0);
        JsonContentCollection contentCollection = deserialiseCollectionJson(groupsFile);
        if (null != contentCollection) {
            List<Group> groups = contentCollection.getCustomGroups();
            eventProgress(STEP_GROUPS, groups.size(), 0, 0);
            trace(Log.INFO, STEP_GROUPS, log, "Custom groups JSON deserialized : %s custom groups detected", groups.size() + "");
            int count = 1;
            for (Group g : groups) {
                // Only add if it isn't a duplicate
                Group duplicate = dao.selectGroupByName(Grouping.CUSTOM.getId(), g.name);
                if (null == duplicate)
                    dao.insertGroup(g);
                else { // If it is, unflag existing group
                    duplicate.setFlaggedForDeletion(false);
                    dao.insertGroup(duplicate);
                }
                eventProgress(STEP_GROUPS, groups.size(), count++, 0);
            }
            trace(Log.INFO, STEP_GROUPS, log, "Import custom groups succeeded");
        } else {
            trace(Log.INFO, STEP_GROUPS, log, "Import custom groups failed : Custom groups JSON unreadable");
        }
    }

    private JsonContentCollection deserialiseCollectionJson(@NonNull DocumentFile jsonFile) {
        JsonContentCollection result;
        try {
            result = JsonHelper.jsonToObject(this, jsonFile, JsonContentCollection.class);
        } catch (IOException | JsonDataException e) {
            Timber.w(e);
            return null;
        }
        return result;
    }

    @Nullable
    private Content importJson(
            @NonNull DocumentFile folder,
            @NonNull ContentProviderClient client,
            @NonNull CollectionDAO dao) throws ParseException {
        DocumentFile file = FileHelper.findFile(this, folder, client, Consts.JSON_FILE_NAME_V2);
        if (file != null) return importJsonV2(file, folder, dao);

        file = FileHelper.findFile(this, folder, client, Consts.JSON_FILE_NAME);
        if (file != null) return importJsonV1(file, folder);

        file = FileHelper.findFile(this, folder, client, Consts.JSON_FILE_NAME_OLD);
        if (file != null) return importJsonLegacy(file, folder);

        return null;
    }

    @SuppressWarnings({"deprecation", "squid:CallToDeprecatedMethod"})
    private static List<Attribute> from(List<URLBuilder> urlBuilders, Site site) {
        List<Attribute> attributes = null;
        if (urlBuilders == null) {
            return null;
        }
        if (!urlBuilders.isEmpty()) {
            attributes = new ArrayList<>();
            for (URLBuilder urlBuilder : urlBuilders) {
                Attribute attribute = from(urlBuilder, AttributeType.TAG, site);
                if (attribute != null) {
                    attributes.add(attribute);
                }
            }
        }

        return attributes;
    }

    @SuppressWarnings({"deprecation", "squid:CallToDeprecatedMethod"})
    private static Attribute from(URLBuilder urlBuilder, AttributeType type, Site site) {
        if (urlBuilder == null) {
            return null;
        }
        try {
            if (urlBuilder.getDescription() == null) {
                throw new ParseException("Problems loading attribute v2.");
            }

            return new Attribute(type, urlBuilder.getDescription(), urlBuilder.getId(), site);
        } catch (Exception e) {
            Timber.e(e, "Parsing URL to attribute");
            return null;
        }
    }

    @CheckResult
    @SuppressWarnings({"deprecation", "squid:CallToDeprecatedMethod"})
    private Content importJsonLegacy(@NonNull final DocumentFile json, @NonNull final DocumentFile parentFolder) throws ParseException {
        try {
            DoujinBuilder doujinBuilder =
                    JsonHelper.jsonToObject(this, json, DoujinBuilder.class);
            ContentV1 content = new ContentV1();
            content.setUrl(doujinBuilder.getId());
            content.setHtmlDescription(doujinBuilder.getDescription());
            content.setTitle(doujinBuilder.getTitle());
            content.setSeries(from(doujinBuilder.getSeries(),
                    AttributeType.SERIE, content.getSite()));
            Attribute artist = from(doujinBuilder.getArtist(),
                    AttributeType.ARTIST, content.getSite());
            List<Attribute> artists = null;
            if (artist != null) {
                artists = new ArrayList<>(1);
                artists.add(artist);
            }

            content.setArtists(artists);
            content.setCoverImageUrl(doujinBuilder.getUrlImageTitle());
            content.setQtyPages(doujinBuilder.getQtyPages());

            content.setTags(from(doujinBuilder.getLstTags(), content.getSite()));
            content.setLanguage(from(doujinBuilder.getLanguage(), AttributeType.LANGUAGE, content.getSite()));

            content.setMigratedStatus();
            content.setDownloadDate(Instant.now().toEpochMilli());
            Content contentV2 = content.toV2Content();

            contentV2.setStorageUri(parentFolder.getUri().toString());

            DocumentFile newJson = JsonHelper.jsonToFile(this, JsonContent.fromEntity(contentV2), JsonContent.class, parentFolder);
            contentV2.setJsonUri(newJson.getUri().toString());

            return contentV2;
        } catch (IOException | JsonDataException e) {
            Timber.e(e, "Error reading JSON (old) file");
            throw new ParseException("Error reading JSON (old) file : " + e.getMessage());
        }
    }

    @CheckResult
    @SuppressWarnings({"deprecation", "squid:CallToDeprecatedMethod"})
    private Content importJsonV1(@NonNull final DocumentFile json, @NonNull final DocumentFile parentFolder) throws ParseException {
        try {
            ContentV1 content = JsonHelper.jsonToObject(this, json, ContentV1.class);
            if (content.getStatus() != StatusContent.DOWNLOADED
                    && content.getStatus() != StatusContent.ERROR) {
                content.setMigratedStatus();
            }
            Content contentV2 = content.toV2Content();

            contentV2.setStorageUri(parentFolder.getUri().toString());

            DocumentFile newJson = JsonHelper.jsonToFile(this, JsonContent.fromEntity(contentV2), JsonContent.class, parentFolder);
            contentV2.setJsonUri(newJson.getUri().toString());

            return contentV2;
        } catch (IOException | JsonDataException e) {
            Timber.e(e, "Error reading JSON (v1) file");
            throw new ParseException("Error reading JSON (v1) file : " + e.getMessage());
        }
    }

    @CheckResult
    private Content importJsonV2(
            @NonNull final DocumentFile json,
            @NonNull final DocumentFile parentFolder,
            @NonNull final CollectionDAO dao) throws ParseException {
        try {
            JsonContent content = JsonHelper.jsonToObject(this, json, JsonContent.class);
            Content result = content.toEntity(dao);
            result.setJsonUri(json.getUri().toString());
            result.setStorageUri(parentFolder.getUri().toString());

            if (result.getStatus() != StatusContent.DOWNLOADED
                    && result.getStatus() != StatusContent.ERROR) {
                result.setStatus(StatusContent.MIGRATED);
            }

            return result;
        } catch (IOException | JsonDataException e) {
            Timber.e(e, "Error reading JSON (v2) file");
            throw new ParseException("Error reading JSON (v2) file : " + e.getMessage(), e);
        }
    }
}
