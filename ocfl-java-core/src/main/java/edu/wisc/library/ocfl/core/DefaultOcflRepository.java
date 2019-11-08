package edu.wisc.library.ocfl.core;

import edu.wisc.library.ocfl.api.*;
import edu.wisc.library.ocfl.api.exception.NotFoundException;
import edu.wisc.library.ocfl.api.exception.ObjectOutOfSyncException;
import edu.wisc.library.ocfl.api.exception.RuntimeIOException;
import edu.wisc.library.ocfl.api.model.*;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.cache.Cache;
import edu.wisc.library.ocfl.core.concurrent.ExecutorTerminator;
import edu.wisc.library.ocfl.core.concurrent.ParallelProcess;
import edu.wisc.library.ocfl.core.inventory.InventoryMapper;
import edu.wisc.library.ocfl.core.inventory.InventoryUpdater;
import edu.wisc.library.ocfl.core.inventory.MutableHeadInventoryCommitter;
import edu.wisc.library.ocfl.core.lock.ObjectLock;
import edu.wisc.library.ocfl.core.model.DigestAlgorithm;
import edu.wisc.library.ocfl.core.model.Inventory;
import edu.wisc.library.ocfl.core.model.InventoryType;
import edu.wisc.library.ocfl.core.model.RevisionId;
import edu.wisc.library.ocfl.core.storage.OcflStorage;
import edu.wisc.library.ocfl.core.util.DigestUtil;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.ResponseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Primary implementation of the OcflRepository API. It is storage agnostic. It is typically instantiated using
 * OcflRepositoryBuilder.
 *
 * @see OcflRepositoryBuilder
 */
public class DefaultOcflRepository implements MutableOcflRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOcflRepository.class);

    private OcflStorage storage;
    private InventoryMapper inventoryMapper;
    private Path workDir;
    private ObjectLock objectLock;
    private Cache<String, Inventory> inventoryCache;
    private ResponseMapper responseMapper;

    private Set<DigestAlgorithm> fixityAlgorithms;
    private InventoryType inventoryType;
    private DigestAlgorithm digestAlgorithm;
    private String contentDirectory;

    private ParallelProcess parallelProcess;
    private ParallelProcess copyParallelProcess;

    private Clock clock;

    private boolean closed = false;

    public DefaultOcflRepository(OcflStorage storage, Path workDir,
                                 ObjectLock objectLock,
                                 Cache<String, Inventory> inventoryCache,
                                 InventoryMapper inventoryMapper,
                                 Set<DigestAlgorithm> fixityAlgorithms,
                                 InventoryType inventoryType, DigestAlgorithm digestAlgorithm,
                                 String contentDirectory, int digestThreadPoolSize, int copyThreadPoolSize) {
        this.storage = Enforce.notNull(storage, "storage cannot be null");
        this.workDir = Enforce.notNull(workDir, "workDir cannot be null");
        this.objectLock = Enforce.notNull(objectLock, "objectLock cannot be null");
        this.inventoryCache = Enforce.notNull(inventoryCache, "inventoryCache cannot be null");
        this.inventoryMapper = Enforce.notNull(inventoryMapper, "inventoryMapper cannot be null");
        this.fixityAlgorithms = Enforce.notNull(fixityAlgorithms, "fixityAlgorithms cannot be null");
        this.inventoryType = Enforce.notNull(inventoryType, "inventoryType cannot be null");
        this.digestAlgorithm = Enforce.notNull(digestAlgorithm, "digestAlgorithm cannot be null");
        this.contentDirectory = Enforce.notBlank(contentDirectory, "contentDirectory cannot be blank");
        Enforce.expressionTrue(digestThreadPoolSize > 0, digestThreadPoolSize, "digestThreadPoolSize must be greater than 0");
        Enforce.expressionTrue(copyThreadPoolSize > 0, copyThreadPoolSize, "copyThreadPoolSize must be greater than 0");

        responseMapper = new ResponseMapper();
        clock = Clock.systemUTC();

        parallelProcess = new ParallelProcess(ExecutorTerminator.addShutdownHook(Executors.newFixedThreadPool(digestThreadPoolSize)));
        copyParallelProcess = new ParallelProcess(ExecutorTerminator.addShutdownHook(Executors.newFixedThreadPool(copyThreadPoolSize)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId putObject(ObjectVersionId objectVersionId, Path path, CommitInfo commitInfo, OcflOption... ocflOptions) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");
        Enforce.notNull(path, "path cannot be null");

        var options = new HashSet<>(Arrays.asList(ocflOptions));

        var inventory = loadInventory(objectVersionId);
        var updater = createInventoryUpdater(objectVersionId, inventory, true);
        updater.addCommitInfo(commitInfo);

        var stagingDir = FileUtil.createTempDir(workDir, objectVersionId.getObjectId());
        var contentDir = FileUtil.createDirectories(resolveContentDir(inventory, stagingDir));

        var newInventory = stageNewVersion(updater, path, contentDir, options);

        try {
            writeNewVersion(newInventory, stagingDir);
            return ObjectVersionId.version(objectVersionId.getObjectId(), newInventory.getHead());
        } finally {
            FileUtil.safeDeletePath(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId updateObject(ObjectVersionId objectVersionId, CommitInfo commitInfo, Consumer<OcflObjectUpdater> objectUpdater) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");
        Enforce.notNull(objectUpdater, "objectUpdater cannot be null");

        var inventory = loadInventory(objectVersionId);
        var updater = createInventoryUpdater(objectVersionId, inventory, false);
        updater.addCommitInfo(commitInfo);

        var stagingDir = FileUtil.createTempDir(workDir, objectVersionId.getObjectId());
        var contentDir = FileUtil.createDirectories(resolveContentDir(inventory, stagingDir));

        try {
            objectUpdater.accept(new DefaultOcflObjectUpdater(updater, contentDir, parallelProcess, copyParallelProcess));
            var newInventory = updater.finalizeUpdate();
            writeNewVersion(newInventory, stagingDir);
            return ObjectVersionId.version(objectVersionId.getObjectId(), newInventory.getHead());
        } finally {
            FileUtil.safeDeletePath(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getObject(ObjectVersionId objectVersionId, Path outputPath) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");
        Enforce.notNull(outputPath, "outputPath cannot be null");
        Enforce.expressionTrue(Files.exists(outputPath), outputPath, "outputPath must exist");
        Enforce.expressionTrue(Files.isDirectory(outputPath), outputPath, "outputPath must be a directory");

        var inventory = requireInventory(objectVersionId);
        requireVersion(objectVersionId, inventory);
        var versionId = resolveVersion(objectVersionId, inventory);

        getObjectInternal(inventory, versionId, outputPath);
    }

    // TODO think about how the file separator problem applies to reads
    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectVersion getObject(ObjectVersionId objectVersionId) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");

        var inventory = requireInventory(objectVersionId);
        requireVersion(objectVersionId, inventory);
        var versionId = resolveVersion(objectVersionId, inventory);

        var versionDetails = createVersionDetails(inventory, versionId);
        var objectStreams = storage.getObjectStreams(inventory, versionId);

        var files = versionDetails.getFiles().stream()
                .map(file -> {
                    return new OcflObjectVersionFile(file, objectStreams.get(file.getPath()));
                })
                .collect(Collectors.toMap(OcflObjectVersionFile::getPath, v -> v));

        versionDetails.setFileMap(null);

        return new OcflObjectVersion(versionDetails, files);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, OcflFileRetriever> getObjectStreams(ObjectVersionId objectVersionId) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");

        var inventory = requireInventory(objectVersionId);
        requireVersion(objectVersionId, inventory);
        var versionId = resolveVersion(objectVersionId, inventory);

        return storage.getObjectStreams(inventory, versionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readObject(ObjectVersionId objectVersionId, Consumer<OcflObjectReader> objectReader) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");
        Enforce.notNull(objectReader, "objectReader cannot be null");

        var inventory = requireInventory(objectVersionId);

        requireVersion(objectVersionId, inventory);

        var stagingDir = FileUtil.createTempDir(workDir, inventory.getId());
        var versionId = resolveVersion(objectVersionId, inventory);

        try {
            objectReader.accept(new DefaultOcflObjectReader(storage, inventory, versionId));
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            FileUtil.safeDeletePath(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectDetails describeObject(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");

        var inventory = requireInventory(ObjectVersionId.head(objectId));
        var objectRootPath = Paths.get(storage.objectRootPath(objectId));

        return responseMapper.mapInventory(inventory, objectRootPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VersionDetails describeVersion(ObjectVersionId objectVersionId) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectId cannot be null");

        var inventory = requireInventory(objectVersionId);
        requireVersion(objectVersionId, inventory);
        var versionId = resolveVersion(objectVersionId, inventory);

        return createVersionDetails(inventory, versionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsObject(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");
        return storage.containsObject(objectId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purgeObject(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");

        objectLock.doInWriteLock(objectId, () -> {
            try {
                storage.purgeObject(objectId);
            } finally {
                inventoryCache.invalidate(objectId);
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId stageChanges(ObjectVersionId objectId, CommitInfo commitInfo, Consumer<OcflObjectUpdater> objectUpdater) {
        ensureOpen();

        Enforce.notNull(objectId, "objectId cannot be null");
        Enforce.notNull(objectUpdater, "objectUpdater cannot be null");

        var inventory = loadInventory(objectId);

        if (inventory == null) {
            // TODO if the mutable HEAD creation fails, this should be purged.
            inventory = createAndPersistEmptyVersion(objectId);
        }

        enforceObjectVersionForUpdate(objectId, inventory);
        var updater = InventoryUpdater.mutateHead(inventory, fixityAlgorithms, now());
        updater.addCommitInfo(commitInfo);

        var stagingDir = FileUtil.createTempDir(workDir, objectId.getObjectId());
        var revisionDir = FileUtil.createDirectories(resolveRevisionDir(inventory, stagingDir));

        try {
            objectUpdater.accept(new DefaultOcflObjectUpdater(updater, revisionDir, parallelProcess, copyParallelProcess));
            var newInventory = updater.finalizeUpdate();
            writeNewVersion(newInventory, stagingDir);
            return ObjectVersionId.version(objectId.getObjectId(), newInventory.getHead());
        } finally {
            FileUtil.safeDeletePath(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId commitStagedChanges(String objectId, CommitInfo commitInfo) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");

        var inventory = requireInventory(ObjectVersionId.head(objectId));

        if (inventory.hasMutableHead()) {
            var newInventory = new MutableHeadInventoryCommitter().commit(inventory, now(), commitInfo);
            var stagingDir = FileUtil.createTempDir(workDir, objectId);
            writeInventory(newInventory, stagingDir);

            try {
                objectLock.doInWriteLock(inventory.getId(), () -> {
                    try {
                        storage.commitMutableHead(inventory, newInventory, stagingDir);
                        cacheInventory(newInventory);
                    } catch (ObjectOutOfSyncException e) {
                        inventoryCache.invalidate(inventory.getId());
                        throw e;
                    }
                });
            } finally {
                FileUtil.safeDeletePath(stagingDir);
            }
        }

        return ObjectVersionId.version(objectId, inventory.getHead());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purgeStagedChanges(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");

        objectLock.doInWriteLock(objectId, () -> {
            try {
                storage.purgeMutableHead(objectId);
            } finally {
                inventoryCache.invalidate(objectId);
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasStagedChanges(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");
        var inventory = requireInventory(ObjectVersionId.head(objectId));
        return inventory.hasMutableHead();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        closed = true;
        parallelProcess.shutdown();
        copyParallelProcess.shutdown();
        storage.close();
    }

    private Inventory loadInventory(ObjectVersionId objectId) {
        return objectLock.doInReadLock(objectId.getObjectId(), () ->
                inventoryCache.get(objectId.getObjectId(), storage::loadInventory));
    }

    private void cacheInventory(Inventory inventory) {
        inventoryCache.put(inventory.getId(), inventory);
    }

    private Inventory requireInventory(ObjectVersionId objectId) {
        var inventory = loadInventory(objectId);
        if (inventory == null) {
            throw new NotFoundException(String.format("Object %s was not found.", objectId));
        }
        return inventory;
    }

    private InventoryUpdater createInventoryUpdater(ObjectVersionId objectId, Inventory inventory, boolean isInsert) {
        InventoryUpdater updater;

        if (inventory != null) {
            enforceObjectVersionForUpdate(objectId, inventory);
            if (isInsert) {
                updater = InventoryUpdater.newVersionForInsert(inventory, fixityAlgorithms, now());
            } else {
                updater = InventoryUpdater.newVersionForUpdate(inventory, fixityAlgorithms, now());
            }
        } else {
            updater = InventoryUpdater.newInventory(
                    objectId.getObjectId(),
                    inventoryType,
                    digestAlgorithm,
                    contentDirectory,
                    fixityAlgorithms,
                    now());
        }

        return updater;
    }

    private Inventory stageNewVersion(InventoryUpdater updater, Path sourcePath, Path contentDir, Set<OcflOption> options) {
        var files = FileUtil.findFiles(sourcePath);
        var newFiles = new HashSet<Path>();

        var filesWithDigests = parallelProcess.collection(files, file -> {
            var digest = updater.computeDigest(file);
            return Map.entry(file, digest);
        });

        // Because the InventoryUpdater is not thread safe, this MUST happen synchronously
        for (var fileWithDigest : filesWithDigests) {
            var file = fileWithDigest.getKey();
            var digest = fileWithDigest.getValue();
            var logicalPath = createLogicalPath(sourcePath, file);

            var isNewFile = updater.addFile(digest, file, logicalPath);

            if (isNewFile) {
                newFiles.add(file);
            }
        }

        copyParallelProcess.collection(newFiles, file -> {
            var logicalPath = createLogicalPath(sourcePath, file);
            if (options.contains(OcflOption.MOVE_SOURCE)) {
                FileUtil.moveFileMakeParents(file, contentDir.resolve(logicalPath));
            } else {
                FileUtil.copyFileMakeParents(file, contentDir.resolve(logicalPath));
            }
        });

        if (options.contains(OcflOption.MOVE_SOURCE)) {
            // Cleanup empty dirs
            FileUtil.safeDeletePath(sourcePath);
        }

        return updater.finalizeUpdate();
    }

    private Path createLogicalPath(Path sourcePath, Path file) {
        if (Files.isRegularFile(sourcePath) && sourcePath.equals(file)) {
            return file.getFileName();
        }
        return sourcePath.relativize(file);
    }

    private void getObjectInternal(Inventory inventory, VersionId versionId, Path outputPath) {
        var stagingDir = FileUtil.createTempDir(workDir, inventory.getId());

        try {
            storage.reconstructObjectVersion(inventory, versionId, stagingDir);
            FileUtil.moveDirectory(stagingDir, outputPath);
        } finally {
            FileUtil.safeDeletePath(stagingDir);
        }
    }

    private void writeNewVersion(Inventory inventory, Path stagingDir) {
        writeInventory(inventory, stagingDir);
        objectLock.doInWriteLock(inventory.getId(), () -> {
            try {
                storage.storeNewVersion(inventory, stagingDir);
                cacheInventory(inventory);
            } catch (ObjectOutOfSyncException e) {
                inventoryCache.invalidate(inventory.getId());
                throw e;
            }
        });
    }

    private void writeInventory(Inventory inventory, Path stagingDir) {
        try {
            var inventoryPath = ObjectPaths.inventoryPath(stagingDir);
            inventoryMapper.write(inventoryPath, inventory);
            String inventoryDigest = DigestUtil.computeDigest(inventory.getDigestAlgorithm(), inventoryPath);
            Files.writeString(ObjectPaths.inventorySidecarPath(stagingDir, inventory),
                    inventoryDigest + "\t" + OcflConstants.INVENTORY_FILE);
        } catch (IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    private Inventory createAndPersistEmptyVersion(ObjectVersionId objectId) {
        LOG.info("Creating object {} with an empty version.", objectId.getObjectId());

        var stagingDir = FileUtil.createTempDir(workDir, objectId.getObjectId());
        FileUtil.createDirectories(resolveContentDir(null, stagingDir));

        try {
            var updater = createInventoryUpdater(objectId, null, true);

            updater.addCommitInfo(new CommitInfo()
                    .setMessage("Auto-generated empty object version.")
                    .setUser(new edu.wisc.library.ocfl.api.model.User().setName("ocfl-java")));

            var inventory = updater.finalizeUpdate();
            writeNewVersion(inventory, stagingDir);
            return inventory;
        } finally {
            FileUtil.safeDeletePath(stagingDir);
        }
    }

    private void enforceObjectVersionForUpdate(ObjectVersionId objectId, Inventory inventory) {
        if (!objectId.isHead() && !objectId.getVersionId().equals(inventory.getHead())) {
            throw new ObjectOutOfSyncException(String.format("Cannot update object %s because the HEAD version is %s, but version %s was specified.",
                    objectId.getObjectId(), inventory.getHead(), objectId.getVersionId()));
        }
    }

    private VersionId resolveVersion(ObjectVersionId objectId, Inventory inventory) {
        var versionId = inventory.getHead();

        if (!objectId.isHead()) {
            versionId = objectId.getVersionId();
        }

        return versionId;
    }

    private void requireVersion(ObjectVersionId objectId, Inventory inventory) {
        if (objectId.isHead()) {
            return;
        }

        if (inventory.getVersion(objectId.getVersionId()) == null) {
            throw new NotFoundException(String.format("Object %s version %s was not found.",
                    objectId.getObjectId(), objectId.getVersionId()));
        }
    }

    private Path resolveContentDir(Inventory inventory, Path parent) {
        var content = this.contentDirectory;
        if (inventory != null) {
            content = inventory.resolveContentDirectory();
        }
        return parent.resolve(content);
    }

    private Path resolveRevisionDir(Inventory inventory, Path parent) {
        var contentDir = resolveContentDir(inventory, parent);
        var newRevision = inventory.getRevisionId() == null ?
                new RevisionId(1) : inventory.getRevisionId().nextRevisionId();
        return contentDir.resolve(newRevision.toString());
    }

    private VersionDetails createVersionDetails(Inventory inventory, VersionId versionId) {
        var version = inventory.getVersion(versionId);
        var objectRootPath = Paths.get(storage.objectRootPath(inventory.getId()));

        return responseMapper.mapVersion(inventory, versionId, version, objectRootPath);
    }

    private OffsetDateTime now() {
        // OCFL spec has timestamps reported at second granularity. Unfortunately, it's difficult to make Jackson
        // interact with ISO 8601 at anything other than nanosecond granularity.
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(DefaultOcflRepository.class.getName() + " is closed.");
        }
    }

    /**
     * This is used to manipulate the clock for testing purposes.
     */
    public void setClock(Clock clock) {
        this.clock = Enforce.notNull(clock, "clock cannot be null");
    }

}
